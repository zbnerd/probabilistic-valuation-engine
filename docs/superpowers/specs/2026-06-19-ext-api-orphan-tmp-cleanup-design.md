# Design: ext-api orphan `gzip-chunk-*.tmp` cleanup hook (#1296)

- Status: Accepted
- Date: 2026-06-19
- Owner: backend
- Related issue: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1296
- Related code: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt`

---

## 1. Background / Problem

### Background

`GzipJsonlChunkWriter` streams snapshot records into a gzip JSONL file
under `java.io.tmpdir` (typically `/tmp` on Linux), uploads it via
`ObjectStorage.putFileAsync`, and deletes the temp file in
`uploadFuture.whenComplete { ... Files.deleteIfExists(tempFile) }`.

On clean upload success, the temp file is removed. On JVM death
(SIGKILL, OOM, container restart) between upload completion and the
`whenComplete` callback running, the callback never executes and the
temp file remains on disk.

### Problem

Observed 2026-06-16 on a long-running pipeline: 293 orphan files
accumulated, 2.4 GB total, mtime 1–7 days old. `/tmp` is tmpfs sized at
`RAM / 2` on the production host (~30 GB). At 8 MB × 30 000 chunks/day,
the leak rate is ~240 MB/day, which will eventually fill `/tmp` and
crash the pipeline.

There is no reclaim path today.

### Goal

A boot-time self-healing mechanism in `module-external-api` that
reclaims orphan `gzip-chunk-*.tmp` files from `java.io.tmpdir` without
interfering with active chunk writers.

---

## 2. Decision

We add an `ApplicationRunner` bean in `module-external-api` that runs
once at boot. It walks `java.io.tmpdir`, deletes files matching
`gzip-chunk-.*\.tmp` whose last-modified time is older than 1 hour, and
logs a summary. The actual scan runs on the existing `loopExecutor`
(virtual-thread pool defined in `LoopExecutorConfig`) bounded by a
30-second deadline so a hung `Files.list()` (e.g., NFS mount) cannot
block boot indefinitely.

```text
Spring boot
  └─ ApplicationRunner.run(args)
       └─ LogicExecutor.executeVoidJava(Runnable { runWithDeadline() }, ctx)
            └─ runWithDeadline() {
                 future = CompletableFuture.runAsync(::cleanupOrphans, loopExecutor)
                 try { future.get(30s) }
                 catch TimeoutException { future.cancel(true); log.warn }
                 catch ExecutionException { log.error; boot proceeds }
               }
            └─ cleanupOrphans() {
                 cutoff = clock.instant() - 1h
                 Files.list(tmpdir)
                    .filter { name matches "gzip-chunk-.*\.tmp" }
                    .forEach { file ->
                       if (mtime < cutoff) try-delete else skip
                    }
                 log.info summary
               }
```

### Why `loopExecutor` not `applicationTaskExecutor`

Spring Boot's `TaskExecutionAutoConfiguration` only registers
`applicationTaskExecutor` when **no** `Executor` bean exists
(`@ConditionalOnMissingBean(Executor.class)`). `LoopExecutorConfig`
already exposes a virtual-thread `loopExecutor` (`AsyncTaskExecutor`),
which suppresses the auto-config. Reusing `loopExecutor` avoids
introducing a third executor pool for a once-per-boot operation and
keeps the boot path on virtual threads (consistent with PhaseLoopController,
Issue #1291).

### Why 1h

Active writers (`GzipJsonlChunkWriter`) create files with mtime `now`
and delete them within seconds of `putFileAsync` completing. Even a
slow MinIO upload is bounded by the S3 transfer manager timeout
(10 min default). 1h gives a 6× safety margin over the worst active
case and prevents the hook from racing live writers.

### Why `java.io.tmpdir` not hardcoded `/tmp`

`java.io.tmpdir` is the JDK-documented location used by
`Files.createTempFile()` (which `GzipJsonlChunkWriter` calls). It
defaults to `/tmp` on Linux, but respects `TMPDIR` overrides and
non-Linux platforms. Using it keeps the hook aligned with the writer
and makes the test path trivial (override via `-Djava.io.tmpdir` or
inject the path).

---

## 3. Trade-offs

### Sensitivity

* Number of orphans per boot (today: ~300, worst-case observed: ~300;
  upper bound: 30 000 / day × N days uncleaned)
* `/tmp` filesystem kind (tmpfs is small; xfs/ext4 is large but still
  bounded)
* JVM boot-time deadline (no hard deadline today; tests expect <1s)
* Container restart rate (low today; increases if memory pressure rises)

### Trade-off

| Choice                       | Gain                              | Cost                                  |
| ---------------------------- | --------------------------------- | ------------------------------------- |
| Sync (block boot)            | simple, predictable, no async shutdown coordination | adds <1s to boot in worst case |
| 1h hardcoded cutoff          | no YAML surface area, test easy via Clock injection | ops cannot tune per environment |
| Always-on, no opt-out flag   | cannot be disabled by accident    | local repro of "leave files" impossible without JVM-arg |
| Per-file fail-soft           | one held-open file does not block cleanup of others | requires WARN summary log |
| `java.io.tmpdir` not `/tmp`  | portable, test-injectable         | one extra path-resolution call        |
| CompletableFuture + 30s deadline | NFS-hung Files.list is interrupted; partial cleanup still possible | +30 LOC, executor injection, internal-function for testability |
| Best-effort cleanup on log failure (instead of boot abort) | pipeline never blocked by hook | broken tmpfs leaves files un-reclaimed until next boot |

### Risk

* A pathological case where an active writer holds open a file > 1h
  would be deleted by the hook. The current writer's `close()` fires
  the upload within seconds and `whenComplete` deletes within
  milliseconds, so this window is essentially zero. Risk accepted.
* `Files.list` failure on a broken tmpfs: now logged + boot proceeds.
  Trade-off: simpler recovery vs. stricter failure mode. Accepted
  because a broken tmpfs also breaks active writers, so the pipeline
  is already in a degraded state.
* Deadline is best-effort: `Files.list` itself is not interruptible.
  JVM-level NFS I/O timeout (typically 60s+) is the floor for a fully
  hung mount. Deadline bounds the *iteration* loop, not the open syscall.

### Non-Risk

* Active chunk write paths: mtime < 1h, never matched by the cutoff.
* Success-path `whenComplete` cleanup: untouched, still primary path.
* MinIO partial uploads: untouched (MinIO owns its scratch space).
* Other snapshot temp files (urgent-chunk, manifest tmp): out of
  scope; their owners handle their own cleanup.

---

## 4. Result / Evidence

### Metrics

| Metric                                 | Source                                |
| -------------------------------------- | ------------------------------------- |
| `logic_executor_total{component=...}`  | LogicExecutor metric tag              |

Component tag value: `OrphanTempFileCleanup`. Operation tag value:
`BootScan`.

### Observed Result (post-implementation)

To be filled after deploy + first unclean reboot.

---

## 5. Summary

> Boot-time sync scan of `java.io.tmpdir` deletes `gzip-chunk-*.tmp`
> files older than 1h; active writers are protected by mtime; per-file
> failures are logged and skipped; ~50 LOC + 5 unit tests.

---

## Appendix A — Implementation sketch

```kotlin
@Component
class OrphanTempFileCleanupHook(
    private val executor: LogicExecutor,
    @Qualifier("loopExecutor")
    private val asyncExecutor: Executor,
    private val clock: Clock = Clock.systemUTC(),
    private val scanDir: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    private val timeoutSeconds: Long = 30,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        executor.executeVoidJava(
            Runnable { runWithDeadline() },
            TaskContext.of("OrphanTempFileCleanup", "BootScan"),
        )
    }

    internal fun runWithDeadline() {
        val future = CompletableFuture.runAsync(Runnable { cleanupOrphans() }, asyncExecutor)
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (ex: TimeoutException) {
            log.warn(
                "[OrphanTempFileCleanup] cleanup exceeded {}s; cancelling, will retry next boot",
                timeoutSeconds,
            )
            future.cancel(true)
        } catch (ex: ExecutionException) {
            val cause = ex.cause ?: ex
            log.error("[OrphanTempFileCleanup] cleanup failed: {}", cause.message, cause)
        }
    }

    private fun cleanupOrphans() {
        val cutoff = Instant.now(clock).minus(CUTOFF)
        var scanned = 0L
        var deleted = 0L
        var bytesFreed = 0L
        var failed = 0L

        Files.list(scanDir).use { stream ->
            stream
                .filter { ORPHAN_PATTERN.matches(it.fileName.toString()) }
                .forEach { file ->
                    scanned++
                    val mtime = try {
                        Files.getLastModifiedTime(file).toInstant()
                    } catch (ex: IOException) {
                        log.warn("[OrphanTempFileCleanup] read mtime failed for {}: {}", file, ex.message)
                        failed++
                        return@forEach
                    }
                    if (mtime.isBefore(cutoff)) {
                        try {
                            bytesFreed += Files.size(file)
                            Files.delete(file)
                            deleted++
                        } catch (ex: IOException) {
                            log.warn("[OrphanTempFileCleanup] delete failed for {}: {}", file, ex.message)
                            failed++
                        }
                    }
                }
        }

        log.info(
            "[OrphanTempFileCleanup] scanned={} deleted={} bytes_freed={} failed={}",
            scanned, deleted, bytesFreed, failed,
        )
        if (failed > 0) {
            log.warn("[OrphanTempFileCleanup] {} files failed to clean; will retry next boot", failed)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java)
        private val ORPHAN_PATTERN = Regex("gzip-chunk-.*\\.tmp")
        private val CUTOFF: Duration = Duration.ofHours(1)
    }
}
```

## Appendix B — Test sketch

```kotlin
class OrphanTempFileCleanupHookTest {
    @TempDir lateinit var tmp: Path

    // Stub LogicExecutor that invokes the passed task synchronously.
    // Matches the contract: execute(task, ctx) -> invokes task and returns its result.
    private val executor: LogicExecutor = mock {
        on { execute(any<Runnable>(), any<TaskContext>()) } doAnswer { invocation ->
            (invocation.arguments[0] as Runnable).run(); null
        }
    }

    private fun makeHook(clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC), scanDir: Path = tmp) =
        OrphanTempFileCleanupHook(executor, clock, scanDir)

    @Test fun `deletes files older than 1 hour`() {
        val file = createOrphan("gzip-chunk-uuid1-part-000001-.jsonl.gz.tmp", ageHours = 2)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isFalse
    }

    @Test fun `preserves files newer than 1 hour (active writer)`() {
        val file = createOrphan("gzip-chunk-uuid2-part-000002-.jsonl.gz.tmp", ageHours = 0)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isTrue
    }

    @Test fun `ignores non-matching filenames`() {
        val file = createOther("unrelated.txt", ageHours = 24)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isTrue
    }

    @Test fun `continues after individual delete failure`() {
        val good = createOrphan("gzip-chunk-uuid3-part-000003-.jsonl.gz.tmp", ageHours = 2)
        val held = createOrphan("gzip-chunk-uuid4-part-000004-.jsonl.gz.tmp", ageHours = 2)
        held.toFile().setReadable(false) // delete will fail on Linux
        makeHook().run(mock())
        assertThat(Files.exists(good)).isFalse       // sibling cleaned up
        assertThat(Files.exists(held)).isTrue        // held one skipped
    }

    @Test fun `logs count and bytes freed at INFO`() {
        createOrphan("gzip-chunk-uuid5-part-000005-.jsonl.gz.tmp", size = 1024, ageHours = 2)
        makeHook().run(mock())
        verify(log).info(
            argThat { msg: String -> msg.contains("scanned=1") && msg.contains("deleted=1") && msg.contains("bytes_freed=1024") },
            any<Any>(), any<Any>(), any<Any>(), any<Any>(),
        )
    }
}
```

Test notes:
- `@TempDir` provides the injected `scanDir`. No `/tmp` access in tests.
- `LogicExecutor` mocked because the hook's actual executor logs metrics we don't want in unit tests. The stub preserves the contract: `execute(task, ctx)` runs the task.
- `setReadable(false)` is a portable way to provoke an `IOException` on `Files.delete` without monkey-patching the filesystem.
- No integration test (issue #207 policy). Runtime verification: `./gradlew :module-external-api:bootRun` + grep log for the summary line.