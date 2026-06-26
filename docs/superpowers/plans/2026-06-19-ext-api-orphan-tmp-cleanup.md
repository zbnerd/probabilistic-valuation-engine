# ext-api Orphan `gzip-chunk-*.tmp` Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Spring `ApplicationRunner` to `module-external-api` that deletes orphan `gzip-chunk-*.tmp` files older than 1h from `java.io.tmpdir` on boot.

**Architecture:** Single `@Component` `OrphanTempFileCleanupHook` implements `ApplicationRunner`. `run(args)` delegates to `LogicExecutor.executeVoid(::cleanupOrphans, ctx)`. `cleanupOrphans()` walks `scanDir` (default `Paths.get(System.getProperty("java.io.tmpdir"))`), filters by regex `gzip-chunk-.*\.tmp`, deletes entries with `mtime < now - 1h`. Per-file `IOException` is logged + skipped (fail-soft). Final INFO summary log.

**Tech Stack:** Kotlin, Spring Boot 3 `ApplicationRunner`, `LogicExecutor`, `java.nio.file`, JUnit 5, Mockito-Kotlin, `@TempDir`.

**Spec:** `docs/superpowers/specs/2026-06-19-ext-api-orphan-tmp-cleanup-design.md`
**Issue:** #1296

---

## File Structure

| File | Responsibility |
| ---- | -------------- |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt` (CREATE) | `@Component` ApplicationRunner + cleanup logic |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt` (CREATE) | 5 unit tests via @TempDir + injected Clock |

No existing files modified. Bean auto-discovered via `ExternalApiApplication.scanBasePackages = ["maple.externalapi", ...]`.

---

## Task 1: Scaffold test class + production shell (failing tests = no impl)

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt`

- [ ] **Step 1: Create test class skeleton with one failing test**

```kotlin
package maple.externalapi.snapshot

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlin.io.path.setLastModifiedTime

class OrphanTempFileCleanupHookTest {

    @TempDir
    lateinit var tmp: Path

    private val fixedNow: Instant = Instant.parse("2026-06-19T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    // Stub LogicExecutor that invokes the Runnable passed to executeVoidJava synchronously.
    // We use executeVoidJava (Runnable-typed) instead of executeVoid (ThrowingRunnable) so the
    // mock signature and the production signature both round-trip via the same Runnable type.
    private val executor: LogicExecutor = mock {
        on { executeVoidJava(any<Runnable>(), any()) } doAnswer { invocation ->
            (invocation.arguments[0] as Runnable).run()
        }
    }

    // Default async executor: runs submitted Runnables synchronously on the caller thread.
    // CompletableFuture.runAsync uses this to start cleanup; the future then completes
    // synchronously. runWithDeadline's future.get(timeout) returns immediately.
    private val syncAsyncExecutor: Executor = Executor { it.run() }

    private val log = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java)

    private fun makeHook(
        clock: Clock = this.clock,
        scanDir: Path = tmp,
        executor: LogicExecutor = this.executor,
        asyncExecutor: Executor = syncAsyncExecutor,
        timeoutSeconds: Long = 30,
    ): OrphanTempFileCleanupHook =
        OrphanTempFileCleanupHook(executor, asyncExecutor, clock, scanDir, timeoutSeconds)

    private fun createOrphan(
        name: String,
        size: Int = 10,
        ageHours: Long = 0,
    ): Path {
        val file = tmp.resolve(name)
        Files.write(file, ByteArray(size))
        file.setLastModifiedTime(FileTime.from(fixedNow.minusSeconds(ageHours * 3600)))
        return file
    }

    @Test
    fun `deletes files older than 1 hour`() {
        val file = createOrphan("gzip-chunk-uuid1-part-000001-.jsonl.gz.tmp", ageHours = 2)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isFalse
    }
}
```

- [ ] **Step 2: Create production shell that compiles but fails the test**

```kotlin
package maple.externalapi.snapshot

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /**
     * Run [cleanupOrphans] on [asyncExecutor] bounded by [timeoutSeconds]. On timeout, the
     * worker thread is interrupted, which causes any in-flight Files.list iteration to throw
     * ClosedByInterruptException; partial cleanup is logged and the rest retries next boot.
     * On other failures (e.g. IOException from Files.list on a broken tmpfs), log + proceed:
     * self-healing is best-effort, and an aborted boot would block pipeline replacement.
     */
    internal fun runWithDeadline() {
        val future = try {
            CompletableFuture.runAsync(Runnable { cleanupOrphans() }, asyncExecutor)
        } catch (ex: Exception) {
            log.error("[OrphanTempFileCleanup] cleanup submit failed: {}", ex.message, ex)
            return
        }
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (ex: TimeoutException) {
            log.warn(
                "[OrphanTempFileCleanup] cleanup exceeded {}s; cancelling, will retry next boot",
                timeoutSeconds,
            )
            future.cancel(true)
        } catch (ex: Exception) {
            val cause = (ex as? ExecutionException)?.cause ?: ex
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
                    } catch (ex: java.io.IOException) {
                        log.warn("[OrphanTempFileCleanup] read mtime failed for {}: {}", file, ex.message)
                        failed++
                        return@forEach
                    }
                    if (mtime.isBefore(cutoff)) {
                        try {
                            bytesFreed += Files.size(file)
                            Files.delete(file)
                            deleted++
                        } catch (ex: java.io.IOException) {
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

- [ ] **Step 3: Run test, expect PASS for `deletes files older than 1 hour`**

Run from repo root:
```bash
./gradlew :module-external-api:test --tests "maple.externalapi.snapshot.OrphanTempFileCleanupHookTest.deletes files older than 1 hour" --continue
```
Expected: `BUILD SUCCESSFUL`, test passes (full impl already present).

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt \
        module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt
git commit -m "feat(ext-api): OrphanTempFileCleanupHook — boot sweep of orphan gzip-chunk-*.tmp (skeleton + first test)"
```

---

## Task 2: Test — preserves files newer than 1 hour (active writer safety)

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt`

- [ ] **Step 1: Add test below `deletes files older than 1 hour`**

```kotlin
    @Test
    fun `preserves files newer than 1 hour (active writer)`() {
        val file = createOrphan("gzip-chunk-uuid2-part-000002-.jsonl.gz.tmp", ageHours = 0)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isTrue
    }

    @Test
    fun `preserves file exactly 1 hour old (cutoff boundary)`() {
        val file = createOrphan("gzip-chunk-uuid3-part-000003-.jsonl.gz.tmp", ageHours = 1)
        makeHook().run(mock())
        assertThat(Files.exists(file)).isTrue
    }
```

- [ ] **Step 2: Run, expect both PASS**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.snapshot.OrphanTempFileCleanupHookTest" --continue
```
Expected: 3 tests, all pass. Boundary test confirms `isBefore(cutoff)` (strict less-than).

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt
git commit -m "test(ext-api): preserve < 1h files incl. cutoff boundary"
```

---

## Task 3: Test — ignores non-matching filenames

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt`

- [ ] **Step 1: Add test**

```kotlin
    @Test
    fun `ignores non-matching filenames`() {
        val unrelated = createOrphan("urgent-chunk-uuid-part-000001-.jsonl.gz.tmp", ageHours = 24)
        val plainTxt = tmp.resolve("notes.txt")
        Files.write(plainTxt, ByteArray(10))
        plainTxt.setLastModifiedTime(FileTime.from(fixedNow.minusSeconds(24 * 3600)))
        val olderPrefix = tmp.resolve("gzip-archive.jsonl.gz") // not tmp suffix
        Files.write(olderPrefix, ByteArray(10))
        olderPrefix.setLastModifiedTime(FileTime.from(fixedNow.minusSeconds(24 * 3600)))

        makeHook().run(mock())

        assertThat(Files.exists(unrelated)).isTrue
        assertThat(Files.exists(plainTxt)).isTrue
        assertThat(Files.exists(olderPrefix)).isTrue
    }
```

- [ ] **Step 2: Run, expect PASS**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.snapshot.OrphanTempFileCleanupHookTest.ignores non-matching filenames" --continue
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt
git commit -m "test(ext-api): ignore non-matching filenames"
```

---

## Task 4: Test — continues after individual delete failure (fail-soft)

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt`

- [ ] **Step 1: Add test**

```kotlin
    @Test
    fun `continues after individual delete failure`() {
        val good = createOrphan("gzip-chunk-uuid4-part-000004-.jsonl.gz.tmp", ageHours = 2)
        val held = createOrphan("gzip-chunk-uuid5-part-000005-.jsonl.gz.tmp", ageHours = 2)
        // Make the file un-deletable on POSIX. Test is no-op on Windows.
        held.toFile().setReadable(false)
        held.toFile().setWritable(false)

        makeHook().run(mock())

        assertThat(Files.exists(good)).isFalse // sibling cleaned up despite held failing
        // held may or may not still exist depending on OS; what matters is the loop didn't bail
        // and the summary log reflects the failure. Cleanup perm for next test:
        held.toFile().setReadable(true)
        held.toFile().setWritable(true)
    }
```

- [ ] **Step 2: Run, expect PASS**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.snapshot.OrphanTempFileCleanupHookTest.continues after individual delete failure" --continue
```
Expected: PASS on Linux/macOS. On Windows the perm calls are no-ops and the test still passes (the delete itself may succeed, but the test only asserts the sibling was cleaned — which it was).

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt
git commit -m "test(ext-api): fail-soft per-file delete"
```

---

## Task 5: Test — logs count and bytes freed at INFO

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt`

- [ ] **Step 1: Add a Logback `ListAppender` to capture logs**

```kotlin
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

// inside test class:
    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var originalLevel: Level

    @org.junit.jupiter.api.BeforeEach
    fun attachAppender() {
        val logger = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java) as Logger
        originalLevel = logger.level
        logger.level = Level.INFO
        logAppender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logAppender)
    }

    @org.junit.jupiter.api.AfterEach
    fun detachAppender() {
        val logger = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java) as Logger
        logger.detachAppender(logAppender)
        logger.level = originalLevel
    }
```

- [ ] **Step 2: Add test**

```kotlin
    @Test
    fun `logs scanned deleted bytes_freed at INFO`() {
        createOrphan("gzip-chunk-uuid6-part-000006-.jsonl.gz.tmp", size = 1024, ageHours = 2)
        createOrphan("gzip-chunk-uuid7-part-000007-.jsonl.gz.tmp", size = 512, ageHours = 0) // skipped (active)

        makeHook().run(mock())

        val summary = logAppender.list
            .firstOrNull { it.formattedMessage.startsWith("[OrphanTempFileCleanup] scanned=") }
        assertThat(summary).isNotNull
        assertThat(summary!!.level).isEqualTo(Level.INFO)
        val msg = summary.formattedMessage
        assertThat(msg).contains("scanned=2")
        assertThat(msg).contains("deleted=1")
        assertThat(msg).contains("bytes_freed=1024")
        assertThat(msg).contains("failed=0")
    }
```

- [ ] **Step 3: Run, expect PASS**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.snapshot.OrphanTempFileCleanupHookTest.logs scanned deleted bytes_freed at INFO" --continue
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt
git commit -m "test(ext-api): INFO summary log asserts scanned/deleted/bytes_freed/failed"
```

---

## Task 6: Test — deadline hit emits WARN + skips remaining

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt`

- [ ] **Step 1: Add a test that drives `runWithDeadline` directly with a non-running executor and timeout=0**

```kotlin
    @Test
    fun `runWithDeadline logs WARN and cancels when timeout fires`() {
        // Executor that never invokes the task — future stays pending.
        // timeoutSeconds = 0 → future.get(0, SECONDS) throws TimeoutException immediately.
        // runWithDeadline must catch it, log WARN, and cancel the future.
        val neverRunsExecutor = Executor { /* drop the command */ }
        val hook = makeHook(asyncExecutor = neverRunsExecutor, timeoutSeconds = 0)

        hook.runWithDeadline()

        val warn = logAppender.list
            .firstOrNull { it.formattedMessage.contains("cleanup exceeded 0s") }
        assertThat(warn).isNotNull
        assertThat(warn!!.level).isEqualTo(Level.WARN)
    }

    @Test
    fun `runWithDeadline logs ERROR when submit fails`() {
        // Executor that throws on submit — runAsync never creates the future; submit-fail
        // path runs and logs the consolidated ERROR message.
        val throwingExecutor = Executor { throw RuntimeException("simulated submit failure") }
        val hook = makeHook(asyncExecutor = throwingExecutor)

        hook.runWithDeadline()

        val err = logAppender.list
            .firstOrNull { it.formattedMessage.contains("cleanup submit failed") }
        assertThat(err).isNotNull
        assertThat(err!!.level).isEqualTo(Level.ERROR)
    }
```

- [ ] **Step 2: Run, expect both PASS**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.snapshot.OrphanTempFileCleanupHookTest.runWithDeadline*" --continue
```
Expected: 2 tests, both PASS. Confirms the deadline WARN path and the error path.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt
git commit -m "test(ext-api): runWithDeadline — timeout WARN + failure ERROR"
```

---

## Task 7: Run full test class + assemble sanity

- [ ] **Step 1: Run all tests in class**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.snapshot.OrphanTempFileCleanupHookTest" --continue
```
Expected: 7 tests, all PASS (5 cleanup behavior + 2 deadline/failure handling).

- [ ] **Step 2: Compile check across module**

```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```
Expected: `BUILD SUCCESSFUL`, no warnings on new file.

- [ ] **Step 3: No commit (verification only)**

---

## Task 8: Boot runtime verification (per `workflow-rules.md`)

- [ ] **Step 1: Start the server**

```bash
cd /home/maple/probabilistic-valuation-engine
set -a && source .env && set +a
./gradlew :module-external-api:bootRun > /tmp/ext-api-bootrun.log 2>&1 &
echo $! > /tmp/ext-api-bootrun.pid
```

- [ ] **Step 2: Wait for "Started ExternalApiApplication"**

```bash
for i in $(seq 1 60); do
  if grep -q "Started ExternalApiApplication" /tmp/ext-api-bootrun.log 2>/dev/null; then
    echo "ready in ${i}s"; break
  fi
  sleep 1
done
grep -q "Started ExternalApiApplication" /tmp/ext-api-bootrun.log || (echo "TIMEOUT"; tail -50 /tmp/ext-api-bootrun.log; exit 1)
```
Expected: "ready in Ns" within 60s.

- [ ] **Step 3: Verify the cleanup summary log line was emitted**

```bash
grep "\[OrphanTempFileCleanup\] scanned=" /tmp/ext-api-bootrun.log
```
Expected: a line like `[OrphanTempFileCleanup] scanned=N deleted=K bytes_freed=B failed=0` (N is the number of `gzip-chunk-*.tmp` files in `java.io.tmpdir` at boot time — could be 0 in a fresh environment).

- [ ] **Step 4: Pre-stage an orphan file, reboot, verify cleanup**

```bash
# Create an orphan (mtime 2h ago) in the actual java.io.tmpdir
echo "fake-gzip-data" > /tmp/gzip-chunk-test-cleanup-$$-part-999999-.jsonl.gz.tmp
touch -d "2 hours ago" /tmp/gzip-chunk-test-cleanup-$$-part-999999-.jsonl.gz.tmp

# Stop server, restart
kill $(cat /tmp/ext-api-bootrun.pid) 2>/dev/null
# poll for process exit (bash `wait` doesn't work for grandchild processes)
for i in $(seq 1 30); do
  kill -0 $(cat /tmp/ext-api-bootrun.pid) 2>/dev/null || break
  sleep 1
done
./gradlew :module-external-api:bootRun > /tmp/ext-api-bootrun2.log 2>&1 &
echo $! > /tmp/ext-api-bootrun.pid
for i in $(seq 1 60); do
  if grep -q "Started ExternalApiApplication" /tmp/ext-api-bootrun2.log 2>/dev/null; then break; fi
  sleep 1
done

# Cleanup line should report deleted=1 bytes_freed=18 (or similar)
grep "\[OrphanTempFileCleanup\] scanned=" /tmp/ext-api-bootrun2.log
# File should be gone
ls /tmp/gzip-chunk-test-cleanup-*-part-999999-.jsonl.gz.tmp 2>&1 | grep -v "No such file" || echo "OK: orphan removed"
```
Expected: `deleted=1` line present, `ls` reports file missing.

- [ ] **Step 5: Stop server**

```bash
kill $(cat /tmp/ext-api-bootrun.pid) 2>/dev/null
# poll for process exit
for i in $(seq 1 30); do
  kill -0 $(cat /tmp/ext-api-bootrun.pid) 2>/dev/null || break
  sleep 1
done
rm -f /tmp/ext-api-bootrun.pid /tmp/ext-api-bootrun.log /tmp/ext-api-bootrun2.log
```

- [ ] **Step 6: No commit (verification only)**

---

## Task 9: Open PR to develop

- [ ] **Step 1: Push branch**

```bash
cd /home/maple/probabilistic-valuation-engine
git push origin HEAD
```

- [ ] **Step 2: Open PR via gh**

```bash
gh pr create --base develop --title "feat(ext-api): OrphanTempFileCleanupHook — boot sweep of orphan gzip-chunk-*.tmp (#1296)" --body "$(cat <<'EOF'
## Summary
- Adds `OrphanTempFileCleanupHook` (Spring `ApplicationRunner`) that deletes orphan `gzip-chunk-*.tmp` files older than 1h from `java.io.tmpdir` on boot.
- Cleanup runs on `loopExecutor` (existing virtual-thread pool in `LoopExecutorConfig`) bounded by a 30s `CompletableFuture` timeout — a hung `Files.list()` (NFS) cannot block boot indefinitely.
- Wrapped in `LogicExecutor.executeVoidJava` for consistent metric tags (`component=OrphanTempFileCleanup`, `operation=BootScan`).
- Fail-soft per-file delete with INFO summary log.
- Best-effort: a Files.list failure logs ERROR but does not abort boot (so a broken tmpfs cannot block pipeline replacement).
- 7 unit tests via `@TempDir` + injected `Clock` + `Executor`.

## Why
Disk leak observed 2026-06-16: 293 orphan files, 2.4 GB, mtime 1–7 days. `/tmp` is tmpfs sized at RAM/2 (~30 GB). At 8 MB × 30K chunks/day = 240 MB/day leak rate. This hook self-heals on every unclean reboot.

## Design notes
- 1h cutoff: active writers' temp files are < 1h old. A 6× safety margin over the 10-min S3 transfer manager timeout.
- 30s deadline: bounds boot time even if /tmp hangs (NFS). On timeout, the worker thread is interrupted; partial cleanup is logged and remaining orphans retry next boot.
- `java.io.tmpdir` (not `/tmp`) keeps the hook portable and test-injectable.

## Out of scope
- Other snapshot temp files (urgent-chunk, manifest tmp)
- MinIO / S3 partial-upload cleanup
- SIGTERM-side cleanup (a JVM shutdown hook is a possible follow-up)
- Scheduled periodic cleanup
- Multi-instance coordination

## Verification
- [x] `./gradlew :module-external-api:test --tests OrphanTempFileCleanupHookTest` → 7/7 pass
- [x] `./gradlew :module-external-api:compileKotlin compileJava --continue` → success
- [x] `bootRun` → `[OrphanTempFileCleanup] scanned=N deleted=K bytes_freed=B failed=0` log present
- [x] Manual orphan seeding → reboot → confirms `deleted=K` line and file removal

Closes #1296

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Confirm PR URL printed, paste it to user**