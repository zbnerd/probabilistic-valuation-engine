# Pipeline Artifact Identity and Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract pipeline artifact identity, storage, write/finalize lifecycle, retention, and the cleanup durable inbox from `module-infra` into `module-pipeline-artifact` without changing existing object keys or event JSON.

**Architecture:** Keep `ObjectStorage` as the backend-neutral port in `module-common`; put typed artifact identities, storage adapters, lifecycle rules, and cleanup inbox persistence in the new module. Workload modules construct their own event DTOs, but can do so only from a completed `ArtifactReceipt` or finalized manifest. `module-infra` retains an app/web compatibility import facade.

**Tech Stack:** Kotlin/JDK 21, Gradle Groovy DSL, Spring Boot 3.5.4, AWS SDK v2/S3 Transfer Manager, Jackson, Micrometer, JUnit 5, AssertJ, Mockito-Kotlin.

**Spec:** `docs/superpowers/specs/2026-07-19-pipeline-artifact-lifecycle-design.md`

**Depends on:** none. Complete this before the cleanup-consumer slice in `2026-07-19-kafka-delivery-outcome.md`.

## Global Constraints

- Preserve every production key and every Kafka event field. The first migration must not rename or copy stored objects.
- Keep `module-app` and `module-web` compatibility through `module-infra`; active ETL modules must import the new module directly.
- Do not add Testcontainers or a new integration-test source set. Preserve the existing opt-in real-MinIO check and use the repository's Docker MinIO for runtime verification.
- Do not use `join()`, `get()`, `runBlocking`, `Thread.sleep`, or coroutine `delay` in new production/test code. Compose `CompletionStage` values and use Awaitility for asynchronous tests.
- Use `runCatching`/result composition instead of new `try-catch` blocks. Never swallow upload or required-publish failures.
- A caller-created upload file is immutable and borrowed until its future completes. Storage never moves or deletes it; the writer removes it after both success and failure.
- No destructive database commands or reset flags are part of this plan.
- Preserve before/after module runtime-classpath size, bootJar size, startup time, and artifact throughput in `docs/05_Reports/2026-07-19-pipeline-artifact-extraction-evidence.md`.
- Before each task commit, run the focused tests shown in that task and `git diff --check`.

---

## Task 1: Record the baseline, write ADR-745, and scaffold the module

**Files:**

- Create: `docs/01_ADR/ADR-745_pipeline-artifact-ownership.md`
- Create: `docs/05_Reports/2026-07-19-pipeline-artifact-extraction-evidence.md`
- Modify: `build.gradle`
- Create: `gradle/artifact-runtime-classpath-metrics.init.gradle`
- Modify: `settings.gradle`
- Create: `module-pipeline-artifact/build.gradle`
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriterTest.kt`

**Interfaces:**

- Consumes: the approved artifact design and current Gradle/runtime measurements.
- Produces: an accepted ownership decision, reproducible baseline evidence, and a compilable empty `module-pipeline-artifact`.

- [ ] **Step 1: Capture the clean baseline before changing dependencies**

Measure from a detached worktree at the exact base commit. Keep the measurement script in the task worktree so the same implementation can be reused for the final comparison:

```bash
artifact_task_worktree=$(pwd)
artifact_base_dir=$(mktemp -d /tmp/artifact-base-worktree.XXXXXX)
rmdir "$artifact_base_dir"
git worktree add --detach "$artifact_base_dir" a35809235de1f92cd7a7c546bd3bed060f62abab
(
  cd "$artifact_base_dir"
  ./gradlew --no-daemon \
    :module-external-api:bootJar :module-calculator:bootJar \
    :module-synchronizer:bootJar :module-cleanup:bootJar
  ./gradlew --no-daemon \
    --init-script "$artifact_task_worktree/gradle/artifact-runtime-classpath-metrics.init.gradle" \
    :module-external-api:artifactRuntimeClasspathMetrics \
    :module-calculator:artifactRuntimeClasspathMetrics \
    :module-synchronizer:artifactRuntimeClasspathMetrics \
    :module-cleanup:artifactRuntimeClasspathMetrics \
    > /tmp/artifact-base-runtime-classpath.txt
  stat -c '%n %s' \
    module-external-api/build/libs/*.jar module-calculator/build/libs/*.jar \
    module-synchronizer/build/libs/*.jar module-cleanup/build/libs/*.jar
)
```

The init script must fail on a non-regular resolved entry and print every sorted entry plus numeric `entryCount` and `totalBytes`. Also capture the four dependency trees used by the original protocol. Record exact commands, output paths, hashes, exit codes, and JAR byte sizes rather than estimates.

In that detached worktree, source the repository `.env` without printing values, start only the existing `postgres`, `redis`, `kafka`, `minio`, and `minio-bootstrap` dependencies when needed, and run the unchanged `runtime_closure_boot_check` from `2026-07-19-etl-runtime-ownership-closure.md` independently for ports 8081-8084. Use the configured MinIO profile and service-account secret files through short-lived mode-0600 copies; record variable names and credential source but never secret values. Preserve each command output, application log path/hash, complete health JSON/hash, startup/shutdown seconds, and any failed profile honestly. Kill only the helper's captured application PID. Remove the exact credential copies and detached worktree after evidence is copied outside it.

Add evidence-only methods, enabled by `ARTIFACT_EVIDENCE_ENABLED=1`, to the two listed existing tests. `LocalFsObjectStorageTest` uses a seeded 1-MiB byte fixture (record its SHA-256), writes 32 warmup objects then 256 measured objects at concurrency 8, repeats the measured phase five times into a fresh test-owned directory, and reports every repetition plus median MiB/s. The exact fixed pool of 8 is a narrow test-only measurement exception: the production registry pools have different sizing/queue semantics, the existing bounded semaphore is suspend-only, and `ExecutorService.use` must guarantee shutdown. `GzipJsonlChunkWriterTest` uses a real test-owned LocalFS adapter rather than a mock, 10,000 deterministic 1-KiB JSON lines, the current compression level, 3 warmup chunks, 20 measured chunks, and five repetitions; report records/sec, compressed MiB/sec, compressed byte count, and matching temp-path sets before/after warmup and every repetition. Assert/report empty added and removed sets (and counts/delta), but do not serialize unrelated ambient paths.

Attach a completion continuation to each aggregate future and capture `System.nanoTime()` plus failure in that continuation. Awaitility may only observe the captured completion state; elapsed time ends at the captured completion timestamp and excludes polling delay. Never call blocking future retrieval. Write machine-readable JSON to each module's `build/reports/artifact-evidence/`, including the effective worker input arguments and both `Runtime` and `MemoryMXBean` heap values, and keep timing thresholds out of JUnit assertions.

Run the fixed baseline with a fresh JVM:

```bash
ARTIFACT_EVIDENCE_ENABLED=1 \
./gradlew --no-daemon -PartifactEvidence :module-infra:test \
  --tests '*LocalFsObjectStorageTest' --rerun-tasks
ARTIFACT_EVIDENCE_ENABLED=1 \
./gradlew --no-daemon -PartifactEvidence :module-external-api:test \
  --tests '*GzipJsonlChunkWriterTest' --rerun-tasks
sha256sum \
  module-infra/build/reports/artifact-evidence/*.json \
  module-external-api/build/reports/artifact-evidence/*.json
```

`-PartifactEvidence` is the only switch that replaces the normal worker defaults with effective `-Xms1g -Xmx1g -XX:+UseG1GC`; the tests must reject any other heap. Record fixture hashes, commit/JDK/CPU/effective worker flags and heap bytes, filesystem type/free space, all repetitions/medians, commands/exit codes, and output hashes in the evidence report. The optional real-MinIO throughput baseline uses the same 1-MiB fixture/count/concurrency when its existing environment gate is enabled; otherwise record it as not measured rather than estimating it.

- [ ] **Step 2: Create ADR-745 with the five required sections**

Write `docs/01_ADR/ADR-745_pipeline-artifact-ownership.md` using the repository convention:

- metadata includes `Status`, `Date`, and `Owner`;
- `1. Background / Problem` contains explicit Background, Problem, and Goal;
- `2. Decision` records the accepted module/port/compatibility decision;
- `3. Trade-offs` contains Sensitivity, Trade-off, Risk, and Non-Risk;
- `4. Result / Evidence` contains numeric classpath/JAR/runtime/throughput evidence and honest exceptions;
- `5. Summary` states the durable decision.

- [ ] **Step 3: Add the module to `settings.gradle`**

Add exactly:

```groovy
// Typed object-storage artifacts, lifecycle, retention, and cleanup inbox
include 'module-pipeline-artifact'
```

- [ ] **Step 4: Create `module-pipeline-artifact/build.gradle`**

```groovy
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation project(':module-common')

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging.jvm)

    implementation(libs.spring.boot)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.s3.transfer.manager)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.regions)
    implementation(libs.aws.sdk.apache.client)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.assertj.core)
}

tasks.named('jar') {
    enabled = true
    archiveClassifier = 'plain'
}
```

- [ ] **Step 5: Verify the empty boundary**

Run: `./gradlew :module-pipeline-artifact:compileKotlin :module-pipeline-artifact:compileJava --continue`

Expected: `BUILD SUCCESSFUL`; the dependency report for this module contains `module-common` and does not contain `module-infra`.

- [ ] **Step 6: Commit the scaffold and decision**

```bash
git add build.gradle settings.gradle gradle/artifact-runtime-classpath-metrics.init.gradle module-pipeline-artifact/build.gradle docs/01_ADR/ADR-745_pipeline-artifact-ownership.md docs/05_Reports/2026-07-19-pipeline-artifact-extraction-evidence.md module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt module-external-api/src/test/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriterTest.kt
git commit -m "build: add pipeline artifact module"
```

---

## Task 2: Introduce validated keys and exact legacy layouts

**Files:**

- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/ArtifactKey.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/ArtifactPrefix.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/ArtifactSegment.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/ArtifactReplayEventId.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/SourceArtifactLayout.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/CalculatorArtifactLayout.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/OcidMappingArtifactLayout.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/identity/CleanupInboxLayout.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/identity/ArtifactKeyTest.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/identity/ArtifactLayoutGoldenTest.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt`

**Interfaces:**

- Consumes: `runId`, endpoint, chunk filename/chunk ID from existing events.
- Produces: validated relative `ArtifactKey` values whose `value` is exactly the current storage string.

- [ ] **Step 1: Write failing key-validation and golden-key tests**

The golden test must assert these literal results:

```kotlin
assertThat(SourceArtifactLayout.runRoot("20260719-120000-1").value)
    .isEqualTo("runs/20260719-120000-1")
assertThat(SourceArtifactLayout.chunk("r1", "item-equipment", "part-000001").value)
    .isEqualTo("runs/r1/item-equipment/chunks/part-000001.jsonl.gz")
assertThat(SourceArtifactLayout.legacyRankingRunning("r1").value)
    .isEqualTo("runs/r1/_RUNNING")
assertThat(SourceArtifactLayout.endpointRunning("r1", "character-basic").value)
    .isEqualTo("runs/r1/character-basic/_RUNNING")
assertThat(SourceArtifactLayout.endpointSuccess("r1", "character-basic").value)
    .isEqualTo("runs/r1/character-basic/_SUCCESS")
assertThat(CalculatorArtifactLayout.resultChunk("r1", "item-equipment", "part-000001").value)
    .isEqualTo("calculator/runs/r1/item-equipment/chunks/result-part-000001.jsonl.gz")
assertThat(SourceArtifactLayout.failedRecords("r1", "item-equipment").value)
    .isEqualTo("runs/r1/item-equipment/failed.jsonl")
assertThat(OcidMappingArtifactLayout.mapping("r1").value)
    .isEqualTo("ocid-mapping/ocid-mapping-r1.jsonl.gz")
assertThat(OcidMappingArtifactLayout.parquetSidecar("r1").value)
    .isEqualTo("ocid-mapping-parquet/ocid-mapping-r1.parquet")
assertThat(CleanupInboxLayout.entry("event-1").value)
    .isEqualTo("cleanup/inbox/event-1.json")
assertThat(SourceArtifactLayout.runPrefix.value).isEqualTo("runs/")
assertThat(CalculatorArtifactLayout.runPrefix.value).isEqualTo("calculator/runs/")
assertThat(CleanupInboxLayout.prefix.value).isEqualTo("cleanup/inbox/")
assertThat(ArtifactReplayEventId.forChunk("SNAPSHOT_CHUNK_READY", "r1", "item-equipment", "part-000001").toString())
    .isEqualTo("89656389-43bb-5b93-b042-8cd4e66290fc")
assertThat(ArtifactReplayEventId.forRun("SNAPSHOT_RUN_COMPLETED", "r1", "item-equipment").toString())
    .isEqualTo("e1b7bcf0-1246-543c-926b-ab91ef37a635")
```

Also assert rejection of `""`, `/absolute`, `a/../b`, `a\\b`, and slash-bearing segments.

Run: `./gradlew :module-pipeline-artifact:test --tests '*ArtifactKeyTest' --tests '*ArtifactLayoutGoldenTest'`

Expected: compilation fails because the identity types do not exist.

- [ ] **Step 2: Implement the value objects**

`ArtifactKey.kt`:

```kotlin
package maple.pipeline.artifact.identity

@JvmInline
value class ArtifactKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<ArtifactKey> = runCatching {
            require(raw.isNotBlank()) { "artifact key must not be blank" }
            require(!raw.startsWith('/')) { "artifact key must be relative" }
            require('\\' !in raw) { "artifact key must use forward slashes" }
            require(raw.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "artifact key contains an invalid segment"
            }
            ArtifactKey(raw)
        }

        fun require(raw: String): ArtifactKey = parse(raw).getOrThrow()
    }

    override fun toString(): String = value
}
```

`ArtifactSegment.kt`:

```kotlin
package maple.pipeline.artifact.identity

@JvmInline
value class ArtifactSegment private constructor(val value: String) {
    companion object {
        fun require(raw: String): ArtifactSegment {
            require(raw.isNotBlank()) { "artifact segment must not be blank" }
            require('/' !in raw && '\\' !in raw && raw != "." && raw != "..") {
                "artifact segment must not contain path separators"
            }
            return ArtifactSegment(raw)
        }
    }
}
```

`ArtifactPrefix` validates nonblank relative segments plus exactly one trailing slash; `ArtifactKey.asPrefix()` is the only key-to-prefix conversion. Implement the layouts as pure objects; every method must construct segments first and call `ArtifactKey.require` once. Layout methods cover source endpoint root/chunk/manifest/failed/running/success, legacy ranking running, calculator result paths, OCID JSONL, the existing Parquet sidecar, and cleanup inbox entry/prefix. Calculator currently has result chunks but no marker/manifest, so do not introduce either during identity extraction. Do not expose string concatenation helpers outside the layout files.

`ArtifactReplayEventId` implements RFC 4122 UUIDv5 with DNS namespace `6ba7b810-9dad-11d1-80b4-00c04fd430c8` and the exact UTF-8 names `pipeline-artifact:{eventType}:{runId}:{endpoint}:{chunkId}` or `pipeline-artifact:{eventType}:{runId}:{endpoint}:run`. It validates every dynamic segment first and gives repeated recovery attempts the same identity without changing the existing initial-publication UUID behavior or adding fields to the manifest.

- [ ] **Step 3: Switch the first two duplicate builders**

`ResultChunkEventPathBuilder.sourceObjectKey` must return `SourceArtifactLayout.chunk(runId, sourceEndpoint, chunkId).value`. `CalculatorChunkProcessingCoordinator.resultObjectKeyFor` must return `CalculatorArtifactLayout.resultChunk(event.runId, event.endpoint, event.chunkId).value`.

- [ ] **Step 4: Run identity and caller tests**

Run:

```bash
./gradlew :module-pipeline-artifact:test --tests '*ArtifactKeyTest' --tests '*ArtifactLayoutGoldenTest'
./gradlew :module-synchronizer:test --tests '*ResultChunkEventPathBuilderTest'
./gradlew :module-calculator:test --tests '*CalculatorChunkProcessingCoordinatorTest'
```

Expected: all tests pass and existing expected string values are unchanged.

- [ ] **Step 5: Commit typed identity**

```bash
git add module-pipeline-artifact module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt
git commit -m "refactor: centralize pipeline artifact keys"
```

---

## Task 3: Move storage adapters and make file ownership/backend identity consistent

**Files:**

- Modify: `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/storage/ConditionalObjectStorage.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/storage/LocalFsObjectStorage.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/storage/MinioObjectStorage.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/storage/MinioProperties.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/storage/ArtifactUploadResources.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/storage/MinioStorageResources.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/config/ArtifactStorageAutoConfiguration.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/config/ArtifactStorageHealthIndicator.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/storage/ObjectStorageContract.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/storage/ArtifactUploadResourcesTest.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/storage/MinioStorageResourcesTest.kt`
- Move and expand: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt` to `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/storage/LocalFsObjectStorageTest.kt`
- Move and expand: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsPutStreamMultipartTest.kt` to `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/storage/LocalFsPutStreamMultipartTest.kt`
- Move and expand: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicatorTest.kt` to `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/config/ArtifactStorageHealthIndicatorTest.kt`
- Move without behavior expansion: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt` to `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/storage/MinioObjectStorageIT.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`
- Delete: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt`
- Delete: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`
- Delete: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioProperties.kt`
- Delete: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt`
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StorageConfigTest.kt`
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/ExtApiBootSmokeIT.kt`
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/CalculatorBootSmokeIT.kt`
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/SynchronizerBootSmokeIT.kt`
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/CleanupBootSmokeIT.kt`
- Modify: `module-infra/build.gradle`

**Interfaces:**

- Consumes: string keys at the `ObjectStorage` compatibility port and immutable caller file paths.
- Produces: `PutResult` for ordinary writes and atomic `PutIfAbsentResult` for durable inbox writes.

- [ ] **Step 1: Write failing storage ownership and conditional-create tests**

Cover:

```kotlin
val source = Files.createTempFile("caller-owned-", ".bin")
Files.writeString(source, "payload")
val upload = storage.putFileAsync("a/b.bin", source).toCompletableFuture()
await().until(upload::isDone)
assertThat(upload).isCompleted
assertThat(upload).isNotCompletedExceptionally
assertThat(Files.readString(source)).isEqualTo("payload")
assertThat(storage.get("a/b.bin")).isEqualTo("payload".toByteArray())
```

Neither production nor new tests call blocking future retrieval. The moved opt-in MinIO test is also converted to Awaitility/future assertions while preserving its environment gate. Add a close-tracking stream proving `putStream` and `putStreamMultipart` obey the caller-owned input contract. Add cases for a source under `/dev/shm` guarded by `assumeTrue(Files.isDirectory(Path.of("/dev/shm")))`, same-content conditional replay, conflicting conditional replay, 1,001 listed objects, and cleanup after failed copy/upload.

Run: `./gradlew :module-pipeline-artifact:test --tests '*LocalFsObjectStorageTest'`

Expected: tests fail because the adapters and conditional contract do not exist.

- [ ] **Step 2: Define conditional creation explicitly**

```kotlin
package maple.pipeline.artifact.storage

import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.ObjectInfo
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix

interface ConditionalObjectStorage : ObjectStorage {
    fun putIfAbsent(key: String, data: ByteArray): CompletionStage<PutIfAbsentResult>
    fun listPage(prefix: ArtifactPrefix, afterKey: ArtifactKey?, limit: Int): StorageObjectPage
}

sealed interface PutIfAbsentResult {
    data class Created(val backendTag: String?) : PutIfAbsentResult
    data class Existing(val bytes: ByteArray, val backendTag: String?) : PutIfAbsentResult
}

data class StorageObjectPage(
    val objects: List<ObjectInfo>,
    val nextAfterKey: ArtifactKey?,
)
```

Validate `limit in 1..1000` and require `afterKey`, when present, to be a descendant of `prefix`. LocalFS lists regular files, maps to keys, sorts lexically, filters strictly after the cursor, and reads at most `limit + 1` to determine `nextAfterKey`. MinIO uses `ListObjectsV2Request.prefix`, `startAfter`, and `maxKeys(limit)`; `isTruncated` determines whether the last emitted key becomes the next cursor, so no request exceeds S3's 1,000-key page maximum. It must not call the eager `listByPrefix` implementation. `StorageObjectPage` defensively copies its list and returns the last emitted key as the next cursor only when another object exists. Tests page through 1,001 objects with no gap/duplicate.

LocalFS must run conditional work on the owned upload executor. Replacement writes copy to a destination-sibling temporary file, force the completed file, use `ATOMIC_MOVE`, and force the parent directory before completing the future. Conditional writes fully write/force a destination-sibling temporary file, atomically publish it with `Files.createLink(finalPath, tempPath)`, force the parent directory, map only `FileAlreadyExistsException` to an async read of the existing bytes, and always remove the temporary link owner; this prevents overwrite races, a reader-visible partial inbox object, and a successful future before the directory entry is durable. Implement the directory force through a small package-internal LocalFS helper and cover its failure as an exceptional completion. MinIO conditional writes use the async client with `If-None-Match: *`; a precondition failure composes an async read of the existing bytes, while every other SDK failure remains exceptional. Neither adapter blocks a caller thread waiting for the result.

- [ ] **Step 3: Correct the `ObjectStorage` ownership documentation**

Change both `putFile` and `putFileAsync` contracts to state:

```kotlin
/**
 * Borrows an immutable caller-owned file until this call or returned future completes.
 * Implementations never move, rewrite, or delete [path]. The caller owns cleanup.
 */
```

Keep `PutResult.checksum` backend-diagnostic only. Do not rename the field in this task because it is a compatibility port.

- [ ] **Step 4: Move configuration ownership**

`ArtifactStorageAutoConfiguration` must expose qualified, owned resources:

```kotlin
@Bean(destroyMethod = "close")
fun artifactUploadResources(meterRegistry: ObjectProvider<MeterRegistry>): ArtifactUploadResources

@Bean(name = ["artifactUploadExecutor"], destroyMethod = "")
fun artifactUploadExecutor(resources: ArtifactUploadResources): ExecutorService = resources.executor

@Bean
@ConditionalOnProperty(name = ["storage.backend"], havingValue = "local", matchIfMissing = true)
fun localObjectStorage(
    @Value("\${storage.local.base-path:../data}") basePath: String,
    @Qualifier("artifactUploadExecutor") uploadExecutor: Executor,
    meterRegistry: ObjectProvider<MeterRegistry>,
): ConditionalObjectStorage = LocalFsObjectStorage(basePath, uploadExecutor, meterRegistry.ifAvailable)
```

`ArtifactUploadResources.close` performs shutdown, awaits five seconds, restores interruption, and forces only unfinished work while recording a static-tag forced-shutdown counter. The MinIO profile exposes one `MinioStorageResources` bean with `destroyMethod="close"`; it closes `S3TransferManager`, `S3AsyncClient`, `S3Client`, and its stream-reader executor exactly once in that order. Tests close each Spring context twice and assert each underlying resource closes once. `ArtifactStorageHealthIndicator` must use `runCatching`; it must not introduce a new `try-catch`.

- [ ] **Step 5: Turn infra configuration into an app/web facade**

`module-infra` adds `implementation project(':module-pipeline-artifact')`. Replace the old storage bean construction with:

```kotlin
@Configuration
@Import(ArtifactStorageAutoConfiguration::class)
class StorageConfig
```

Delete the four old implementation/property/health sources listed in this task: the repository scan shows no app/web production caller of their concrete FQNs. Keep `StorageConfig` as the sole legacy import facade. Update `StorageConfigTest` to assert its `ObjectStorage` is the new `maple.pipeline.artifact.storage.LocalFsObjectStorage`; update the four existing smoke tests to import new properties/health types without creating new integration tests.

- [ ] **Step 6: Verify local and opt-in real-MinIO semantics**

Run default verification:

```bash
./gradlew :module-pipeline-artifact:test
./gradlew :module-infra:test --tests '*StorageConfigTest'
```

Expected: `BUILD SUCCESSFUL`; no external service is required.

With the repository MinIO already running, run the preserved opt-in check without adding a Testcontainers dependency:

```bash
INTEGRATION_MINIO=true ./gradlew :module-pipeline-artifact:test --tests '*MinioObjectStorageIT'
```

Expected: the borrowed source survives success/failure, conditional creation is atomic, pagination returns 1,001 objects, and the test prefix is removed.

- [ ] **Step 7: Commit storage ownership**

```bash
git add module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt module-pipeline-artifact module-infra/src/main/kotlin/maple/expectation/infrastructure/storage module-infra/src/test/kotlin/maple/expectation/infrastructure/storage module-infra/build.gradle
git commit -m "refactor: move artifact storage adapters"
```

---

## Task 4: Add `ArtifactReceipt` and centralize temp-file/gzip ownership

**Files:**

- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/write/ArtifactReceipt.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/write/ArtifactWriter.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/write/GzipArtifactSession.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/write/ArtifactWriterTest.kt`
- Modify: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/config/ArtifactStorageAutoConfiguration.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriter.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/EndpointSinkFactory.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriter.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/artifact/OcidMappingArtifactWriter.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriterTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkFileManagerAsyncTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkFileManagerTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriterTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/EndpointSinkFactoryTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriterTest.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/artifact/OcidMappingArtifactWriterTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/cache/OcidCacheProviderTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt`

**Interfaces:**

- Consumes: typed key plus bytes streamed into one writer-owned gzip session.
- Produces: a future `ArtifactReceipt` with backend-independent SHA-256 and backend diagnostic tag; no event can be constructed before completion.

- [ ] **Step 1: Write failing lifetime and receipt tests**

Assert that serialization failure, synchronous storage rejection, and asynchronous upload failure all remove the writer temp file; success also removes it. Assert equal `contentSha256` for identical stored bytes when `PutResult.checksum` differs.

Run: `./gradlew :module-pipeline-artifact:test --tests '*ArtifactWriterTest'`

Expected: compilation fails because the writer types do not exist.

- [ ] **Step 2: Implement the public receipt/session contract**

```kotlin
package maple.pipeline.artifact.write

import maple.pipeline.artifact.identity.ArtifactKey

data class ArtifactReceipt(
    val key: ArtifactKey,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val contentSha256: String,
    val backendTag: String?,
)
```

```kotlin
interface GzipArtifactSession : AutoCloseable {
    val output: OutputStream
    fun complete(uncompressedBytes: Long): CompletableFuture<ArtifactReceipt>
    fun abort(cause: Throwable): CompletableFuture<ArtifactReceipt>
}

interface ArtifactWriter {
    fun openGzip(key: ArtifactKey): GzipArtifactSession
}
```

The concrete session must hash the exact compressed object bytes using `DigestOutputStream`, close gzip before measuring, call `putFileAsync`, and delete the session temp file in one `whenComplete` attached to the returned future. Guard the session with an atomic `OPEN → COMPLETING|ABORTED` transition: `complete` and `abort` are each legal once, a second terminal call fails, and `close()` on an open session aborts/cleans it. `abort` closes streams, deletes the file, and returns `CompletableFuture.failedFuture(cause)`.

Register exactly one `ArtifactWriter` bean in `ArtifactStorageAutoConfiguration`, backed by the selected `ConditionalObjectStorage` and owned upload executor.

- [ ] **Step 3: Migrate the external chunk writer**

Change `ChunkStats` to carry:

```kotlin
val uploadFuture: CompletableFuture<ArtifactReceipt>
```

`GzipJsonlChunkWriter` opens one `GzipArtifactSession`, writes to `session.output`, and calls `session.complete(uncompressedBytes)` exactly once. `ChunkFileManager` now accepts `runId` and `endpoint`, obtains every key from `SourceArtifactLayout`, and never parses a raw `runKey` with `substringAfter`. `SnapshotFailedRecordWriter` accepts the typed failed-record key. `EndpointSinkFactory` accepts `runId`, not a prebuilt prefix. Remove every comment or branch that says storage owns the source file or retains failed temp files.

- [ ] **Step 4: Migrate the calculator writer**

`CalculationResultWriter.WriteResult` becomes a workload projection of `ArtifactReceipt`; `etag` is replaced internally by `contentSha256` and `backendTag`, while serialized Kafka events remain unchanged. The writer must no longer create/delete its own temp file.

- [ ] **Step 5: Migrate urgent and OCID artifacts to receipt-first publication**

`UrgentChunkArtifactWriter.writeChunk` returns `CompletionStage<ArtifactReceipt>` and derives its UUID-bearing key through `SourceArtifactLayout`; no caller receives a key before upload completion.

`OcidMappingArtifactWriter` owns one `GzipArtifactSession` for `OcidMappingArtifactLayout.mapping(runId)`. `OcidLookupPhase` writes each UTF-8 JSONL line to that session while counting uncompressed bytes and awaits its completion by coroutine suspension, not blocking future retrieval. Its run-completed event takes `receipt.key.value` as `manifestPath`. Preserve the optional Parquet sidecar by teeing each already-serialized JSON line to its existing best-effort writer during the same writer coroutine; upload that caller-owned Parquet temp path with the existing borrowed-file API, derive its key through `OcidMappingArtifactLayout.parquetSidecar`, and delete it only after its future completes. `OcidCacheProvider` obtains its listing prefix and specific mapping key from the same layout.

- [ ] **Step 6: Verify write ordering and resource cleanup**

Run:

```bash
./gradlew :module-pipeline-artifact:test --tests '*ArtifactWriterTest'
./gradlew :module-external-api:test --tests '*GzipJsonlChunkWriterTest' --tests '*ChunkFileManagerAsyncTest' --tests '*ChunkFileManagerTest' --tests '*SnapshotFailedRecordWriterTest' --tests '*EndpointSinkFactoryTest'
./gradlew :module-external-api:test --tests '*UrgentChunkArtifactWriterTest' --tests '*OcidMappingArtifactWriterTest' --tests '*OcidCacheProviderTest' --tests '*OcidLookupPhaseTest'
./gradlew :module-calculator:test --tests '*CalculationResultWriterTest'
```

Expected: all tests pass; a failed upload yields no receipt and leaves no `gzip-chunk-*` or `calc-result-*` temp file.

- [ ] **Step 7: Commit the write boundary**

```bash
git add module-pipeline-artifact module-external-api/src/main/kotlin/maple/externalapi/snapshot module-external-api/src/main/kotlin/maple/externalapi/artifact module-external-api/src/main/kotlin/maple/externalapi/cache module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt module-external-api/src/test/kotlin/maple/externalapi module-calculator/src/main/kotlin/maple/calculator/writer module-calculator/src/test/kotlin/maple/calculator/writer
git commit -m "refactor: centralize artifact write lifetime"
```

---

## Task 5: Make source finalization publication-aware and replayable

**Files:**

- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/lifecycle/RunState.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/lifecycle/RunLifecycle.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/lifecycle/RunLifecycleTest.kt`
- Modify: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/config/ArtifactStorageAutoConfiguration.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/PendingPublicationRecovery.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/PendingPublicationRecoveryTest.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SinkEventPublisher.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/EndpointSinkFactory.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkManifest.kt`
- Delete: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotChunkManifestWriterTest.kt`
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriter.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisherTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSinkTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/EndpointSinkFactoryTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhaseTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhaseTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`

**Interfaces:**

- Consumes: completed chunk receipts, finalized manifest bytes, and one callback returning the future for all required sends.
- Produces: `PUBLISHED` only after manifest, `_SUCCESS`, all required sends, and running-marker deletion complete.

- [ ] **Step 1: Write the failure-order matrix as tests**

Cover these exact assertions:

```text
manifest write failure       => _RUNNING present, _SUCCESS absent, publish not called
success marker failure       => _RUNNING present, publish not called
required publish failure     => _RUNNING present, _SUCCESS present
publish success/delete fail  => PUBLISHED_WITH_ORPHAN_MARKER
replay from success+running  => manifest read, sends repeated, running marker deleted
ranking finalize             => legacy runs/{runId}/_RUNNING deleted; no new endpoint marker
```

Run: `./gradlew :module-pipeline-artifact:test --tests '*RunLifecycleTest'`

Expected: compilation fails because the lifecycle types do not exist.

- [ ] **Step 2: Implement the closed run states**

```kotlin
package maple.pipeline.artifact.lifecycle

sealed interface RunState {
    data object Absent : RunState
    data object Running : RunState
    data object ArtifactSucceededPublicationPending : RunState
    data object Published : RunState
    data object PublishedWithOrphanMarker : RunState
    data class Incomplete(val reason: String) : RunState
    data class Invalid(val reason: String) : RunState
}
```

`RunLifecycle.finalizeEndpoint` must have this exact boundary:

```kotlin
fun finalizeEndpoint(
    runId: String,
    endpoint: String,
    manifestBytes: ByteArray,
    requiredPublish: () -> CompletionStage<Void>,
): CompletableFuture<RunState>
```

Add `fun startEndpoint(runId: String, endpoint: String): CompletableFuture<RunState>`. It preserves current topology: ranking writes only the legacy root `_RUNNING`; character-basic/item-equipment write only their endpoint `_RUNNING`. It must not add a new ranking endpoint marker. `finalizeEndpoint` writes endpoint `manifest.json`, then endpoint `_SUCCESS`, invokes `requiredPublish`, and only then deletes the marker selected by that same topology. A replay entry point reads an existing manifest and receives a callback that reconstructs workload events outside this module:

```kotlin
fun replayPublicationPending(
    runId: String,
    endpoint: String,
    requiredPublishFromManifest: (ByteArray) -> CompletionStage<Void>,
): CompletableFuture<RunState>
```

`RunLifecycle` receives the owned `artifactUploadExecutor`; sync `ObjectStorage` marker/manifest/read/delete calls run on that executor and are composed with `requiredPublish`. No caller/Kafka thread blocks on a storage future.

Register exactly one `RunLifecycle` bean in `ArtifactStorageAutoConfiguration`.

- [ ] **Step 3: Stop swallowing required send failures**

Replace `SinkEventPublisher.publishSafely` with methods returning `CompletableFuture<Void>` that preserve synchronous and asynchronous failure:

```kotlin
private fun publish(send: () -> CompletableFuture<*>): CompletableFuture<Void> =
    runCatching { send().thenApply { null } }
        .getOrElse { CompletableFuture.failedFuture(it) }
```

`SnapshotSinkEventPublisher.publishChunkReady`, `publishRunCompleted`, and `publishRunFailed` must return their futures. Preserve the current initial event-ID generation; object/manifest paths come from `ArtifactReceipt`/`SourceArtifactLayout`, not raw construction. They may record metrics before returning, but may not convert failure to success.

- [ ] **Step 4: Track every required publish future**

For each closed chunk, `ChunkedSnapshotSink` composes `receiptFuture.thenCompose { receipt -> publishChunkReady(receipt, stats) }`; event construction is inside that continuation, never before upload completion. `ChunkFileManager` stores the completed `ArtifactReceipt` for each manifest entry. The sink accumulates those chunk-ready chains, composes them with `CompletableFuture.allOf`, appends the run-completed future, and passes that aggregate to `RunLifecycle` without blocking. Remove the current order that deletes `_RUNNING` before publishing run completion.

Keep `SnapshotChunkManifest`/`ChunkEntry` JSON fields byte-compatible: do not add receipt hashes or event IDs. Preserve the existing `SnapshotChunkReadyEvent.sha256=null` value in this extraction even though the receipt owns a content hash. Delete the now-unused `SnapshotChunkManifestWriter` class/test because `RunLifecycle` is the sole manifest writer.

On a chunk/serialization failure, remove only incomplete chunk/manifest/failed objects under the exact typed endpoint prefix and retain the active marker for retry/stale-run classification. Compose the run-failed publication future with the scheduler failure path; it may not be fire-and-forget or replace the original failure.

- [ ] **Step 5: Add manifest-based publication recovery**

Add an external-api component at `module-external-api/src/main/kotlin/maple/externalapi/snapshot/PendingPublicationRecovery.kt`. In this task it exposes explicit `recover(runId, endpoint)` and delegates manifest reading/state transition to `RunLifecycle.replayPublicationPending`; it reconstructs deterministic chunk/run event payloads with `ArtifactReplayEventId`, sends all required events, and asks `RunLifecycle` to remove the running marker. Recovery determinism covers the whole event, not only its ID: derive `chunkId` from the validated manifest filename, keep `sha256=null`, use each `ChunkEntry.finishedAt` as chunk `createdAt`, and use `manifest.finishedAt` as run-completed `createdAt`. Therefore two recovery attempts produce semantically identical JSON for the same stable event ID and cannot conflict in the cleanup inbox. Reject an epoch/malformed timestamp or path as an incomplete manifest rather than substituting `Clock.now()`.

Do not duplicate a raw prefix scanner here. Task 6 adds exhaustive startup discovery through `ArtifactRunCatalog` and calls this same method for each publication-pending run. This intermediate commit is safe because `_SUCCESS + _RUNNING` remains protected and manually replayable, but it is not the slice completion point. Do not add event IDs to the manifest or change its JSON schema. Initial publication keeps its current random ID; every recovery attempt for the same legacy/new manifest uses the literal stable UUID fixtures from Task 2.

Delete `RunMarkerWriter`. Ranking/character-basic/item-equipment phases compose `startEndpoint` before creating/submitting any fetch work, then pass `runId`/endpoint to `EndpointSinkFactory`; none passes a prebuilt run-key string. A marker-write failure prevents fetch submission and remains exceptional. `RankingFetchPhase` and `ExternalApiScheduler` pass the run ID to `OcidLookupPhase`, whose ranking input prefix comes from `SourceArtifactLayout`. Update all listed phase/scheduler tests to assert the unchanged literal object keys.

- [ ] **Step 6: Verify lifecycle and dataflow contracts**

Run:

```bash
./gradlew :module-pipeline-artifact:test --tests '*RunLifecycleTest'
./gradlew :module-external-api:test --tests '*ChunkedSnapshotSinkTest' --tests '*SnapshotSinkEventPublisherTest' --tests '*PendingPublicationRecoveryTest' --tests '*EndpointSinkFactoryTest' --tests '*DataflowContractTest'
./gradlew :module-external-api:test --tests '*RankingFetchPhaseTest' --tests '*CharacterBasicFetchPhaseTest' --tests '*ItemEquipmentFetchPhaseTest' --tests '*OcidLookupPhaseTest' --tests '*ExternalApiSchedulerTest'
```

Expected: all tests pass; no required send is fire-and-forget and the legacy key fixtures remain identical.

- [ ] **Step 7: Commit publication-aware finalization**

```bash
git add module-pipeline-artifact module-external-api/src/main/kotlin/maple/externalapi/snapshot module-external-api/src/main/kotlin/maple/externalapi/scheduler module-external-api/src/test/kotlin/maple/externalapi/snapshot module-external-api/src/test/kotlin/maple/externalapi/scheduler module-external-api/src/test/kotlin/maple/externalapi/dataflow
git commit -m "fix: retain source run until publication completes"
```

---

## Task 6: Replace raw cleanup listing with a typed run catalog

**Files:**

- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/retention/ArtifactRunCatalog.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/retention/ArtifactRunInfo.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/retention/ArtifactEndpointInfo.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/retention/ArtifactRetentionService.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/retention/ArtifactRunCatalogTest.kt`
- Modify: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/config/ArtifactStorageAutoConfiguration.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/PendingPublicationRecovery.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/PendingPublicationRecoveryMetrics.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/PendingPublicationRecoveryTest.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt`
- Modify: `module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt`

**Interfaces:**

- Consumes: complete paginated `ObjectInfo` listings for `runs/` and `calculator/runs/`.
- Produces: parsed run identity/state/size and only safe retention candidates.

- [ ] **Step 1: Write failing marker-topology tests**

Build fixtures for root `_RUNNING`, endpoint `_RUNNING`, nested descendant `_RUNNING`, `_SUCCESS + _RUNNING`, published endpoint success, incomplete manifest, and invalid run ID. Assert every active/publication-pending/invalid run is excluded from deletion.

Run: `./gradlew :module-pipeline-artifact:test --tests '*ArtifactRunCatalogTest'`

Expected: compilation fails because the catalog does not exist.

- [ ] **Step 2: Implement catalog classification**

```kotlin
data class ArtifactRunInfo(
    val runId: String,
    val prefix: ArtifactKey,
    val createdAt: Instant,
    val sizeBytes: Long,
    val state: RunState,
    val endpoints: List<ArtifactEndpointInfo>,
)

data class ArtifactEndpointInfo(
    val endpoint: String,
    val manifestKey: ArtifactKey?,
    val state: RunState,
)
```

Group the full listing by the first segment after the requested root, then classify each endpoint before deriving the aggregate run state. Associate the legacy root `_RUNNING` only with `ranking-overall`; endpoint markers associate with their exact endpoint. Classify `_RUNNING` plus that endpoint's `_SUCCESS` as publication pending, `_SUCCESS` without its matching running marker as published, a running marker without success as active, unparseable keys/run IDs/endpoints as invalid, and remaining stale combinations as incomplete. `ArtifactRunInfo.state` is the most protective aggregate (`Invalid`, publication pending, active, incomplete, then published), while `endpoints` retains the exact manifest/state pairs needed by recovery. Candidate calculation begins only after the full listing succeeds.

- [ ] **Step 3: Preserve all retention safeguards**

`ArtifactRunCatalog.list(root: ArtifactPrefix)` accepts only `SourceArtifactLayout.runPrefix` or `CalculatorArtifactLayout.runPrefix`; callers cannot pass raw roots. `ArtifactRetentionService` must accept the current values for `keepRecent`, `keepWithinHours`, `maxDeleteRunsPerCycle`, `maxDeleteBytesPerCycle`, and `maxRuntimeSeconds`, then delegate the final bounded deletion to the existing `RunCleanupExecutor`. Only typed exact run prefixes can reach `deleteByPrefix`.

Register `ArtifactRunCatalog` and `ArtifactRetentionService` in `ArtifactStorageAutoConfiguration`; do not rely on executable-module component scanning to discover library internals.

- [ ] **Step 4: Activate exhaustive startup publication recovery**

Inject `ArtifactRunCatalog`, `@Qualifier("artifactUploadExecutor") Executor`, and `PendingPublicationRecoveryMetrics` into `PendingPublicationRecovery`. On the first `ApplicationReadyEvent`, offload the complete source-root listing with `CompletableFuture.supplyAsync` to that owned executor, flatten endpoint entries whose state is `ArtifactSucceededPublicationPending`, and compose calls to the existing `recover(runId, endpoint)` method with `CompletableFuture.allOf`. Calculator artifacts are not source-event publications and are not replayed by external-api. The event callback returns immediately; recovery is observable background work and does not block readiness. Use one atomic started flag so duplicate ready events cannot start a second scan. A list-page failure or replay failure remains observable, leaves every marker untouched, increments `artifact_publication_recovery_failures_total{stage="list|replay"}` with only those two static values, and never prevents the application from reaching readiness. Record recovered endpoint count separately without run/endpoint tags. Add restart fixtures for legacy ranking root markers and endpoint markers; both must replay from manifest with the stable UUIDv5 identities and then remove only their matching marker.

- [ ] **Step 5: Switch `RunCleanupService`**

Delete its `listRunIds` and root-only `_RUNNING` check. Inject `ArtifactRunCatalog`/`ArtifactRetentionService`, preserve `cleanupRuns()` and `cleanupCalculatorRuns()` controller behavior, and emit protected/invalid counts.

- [ ] **Step 6: Verify 1,001-object, active-run, and recovery safety**

Run:

```bash
./gradlew :module-pipeline-artifact:test --tests '*ArtifactRunCatalogTest'
./gradlew :module-external-api:test --tests '*PendingPublicationRecoveryTest'
./gradlew :module-cleanup:test --tests '*RunCleanupServiceTest'
```

Expected: 1,001 objects are all visible, descendant-active runs are never passed to delete, invalid runs remain untouched, and startup recovery discovers both marker topologies without raw prefix logic in external-api.

- [ ] **Step 7: Commit typed retention and recovery discovery**

```bash
git add module-pipeline-artifact module-external-api/src/main/kotlin/maple/externalapi/snapshot/PendingPublicationRecovery.kt module-external-api/src/main/kotlin/maple/externalapi/snapshot/PendingPublicationRecoveryMetrics.kt module-external-api/src/test/kotlin/maple/externalapi/snapshot/PendingPublicationRecoveryTest.kt module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt
git commit -m "refactor: classify artifact runs before cleanup"
```

---

## Task 7: Provide the atomic cleanup inbox store for messaging activation

**Files:**

- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/inbox/CleanupInboxEntry.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/inbox/CleanupInboxStore.kt`
- Create: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/inbox/ObjectStorageCleanupInboxStore.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/inbox/ObjectStorageCleanupInboxStoreTest.kt`
- Modify: `module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/config/ArtifactStorageAutoConfiguration.kt`

**Interfaces:**

- Consumes: `ChunkConsumedEvent`, Kafka topic/partition/offset, and received time.
- Produces: durable `cleanup/inbox/{eventId}.json`, replay/conflict classification, and page-based read/delete operations. Kafka ACK and HTTP drain activation stay atomic in the dependent messaging task.

- [ ] **Step 1: Write failing durability/concurrency/restart tests**

Test two concurrent same-event writes, same `eventId` with different canonical payloads, process recreation using the same storage directory, stable pagination, and delete/re-list behavior.

Run:

```bash
./gradlew :module-pipeline-artifact:test --tests '*ObjectStorageCleanupInboxStoreTest'
```

Expected: tests fail because the durable store is absent.

- [ ] **Step 2: Implement the durable envelope and result**

```kotlin
data class CleanupInboxEntry(
    val eventId: String,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val receivedAt: Instant,
    val event: ChunkConsumedEvent,
)

sealed interface InboxPutResult {
    data object Created : InboxPutResult
    data object Replay : InboxPutResult
    data class IntegrityConflict(val eventId: String) : InboxPutResult
}

interface CleanupInboxStore {
    fun putIfAbsent(entry: CleanupInboxEntry): CompletionStage<InboxPutResult>
    fun listPage(afterKey: ArtifactKey?, limit: Int): CleanupInboxPage
    fun delete(key: ArtifactKey)
    fun pendingCount(): Long
}

data class CleanupInboxPage(
    val entries: List<Pair<ArtifactKey, CleanupInboxEntry>>,
    val nextAfterKey: ArtifactKey?,
)
```

Derive the object key with `CleanupInboxLayout.entry(eventId)`, which validates `eventId` as one `ArtifactSegment`; reject blank/slash-bearing IDs before storage access. Listing uses `CleanupInboxLayout.prefix`. Replay comparison canonicalizes only the semantic `event` JSON (including its stable event ID), not `receivedAt` or Kafka delivery coordinates. Therefore the same event redelivered later remains `Replay` and retains the first envelope, while the same `eventId` with different event content returns `IntegrityConflict` without overwriting the original.

Register exactly one `CleanupInboxStore` bean backed by `ConditionalObjectStorage` in `ArtifactStorageAutoConfiguration`.

- [ ] **Step 3: Keep the legacy listener untouched until partition-lane activation**

Do not modify `ConsumedChunkInbox` or `CleanupController` in this task. Returning from the legacy listener before persistence and acknowledging later would create an unsafe intermediate deployment. `2026-07-19-kafka-delivery-outcome.md` Task 6 switches the listener, partition-lane ACK owner, store, and HTTP drain together.

- [ ] **Step 4: Verify restart, pagination, and conflict behavior**

Run: `./gradlew :module-pipeline-artifact:test --tests '*ObjectStorageCleanupInboxStoreTest'`

Expected: all tests pass; restart sees pending entries, pagination has no gaps/duplicates, deletion is reflected on re-list, and a conflicting event never replaces the first payload.

- [ ] **Step 5: Commit durable inbox storage**

```bash
git add module-pipeline-artifact
git commit -m "feat: add durable cleanup inbox store"
```

---

## Task 8: Wire active services directly, preserve the facade, and verify the slice

**Files:**

- Modify: `module-external-api/build.gradle`
- Modify: `module-calculator/build.gradle`
- Modify: `module-synchronizer/build.gradle`
- Modify: `module-cleanup/build.gradle`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt`
- Create: `module-pipeline-artifact/src/test/kotlin/maple/pipeline/artifact/identity/ArtifactIdentitySourceGuardTest.kt`
- Modify: `docs/05_Reports/2026-07-19-pipeline-artifact-extraction-evidence.md`

**Interfaces:**

- Consumes: the new artifact auto-configuration from four executable modules.
- Produces: direct artifact-module wiring; storage-related infra imports are zero while unrelated infra dependencies remain until later plans.

- [ ] **Step 1: Add direct artifact dependencies and imports**

Each active module adds:

```groovy
implementation project(':module-pipeline-artifact')
```

Replace imports of `StorageConfig`, `MinioHealthIndicator`, and storage implementations with `ArtifactStorageAutoConfiguration`, `ArtifactStorageHealthIndicator`, and `maple.pipeline.artifact.storage` types. Do not remove the whole `module-infra` dependency in this plan; the runtime-ownership plan does that after all seams are extracted.

- [ ] **Step 2: Add a focused source guard**

`ArtifactIdentitySourceGuardTest` scans all four active `src/main` trees and fails if this package appears:

```text
maple.expectation.infrastructure.storage
```

The guard must not ban all infra imports yet, because messaging/calculation/Nexon/runtime tasks are intentionally pending. The same test also scans quoted string templates for `runs/`, `calculator/runs/`, `ocid-mapping/`, `ocid-mapping-parquet/`, `cleanup/inbox/`, `/_RUNNING`, or `/_SUCCESS`. The expected match set is empty; comments/tests/golden fixture literals are outside this production-only scan. Every key/prefix must come from an artifact layout.

- [ ] **Step 3: Run compilation and all affected tests**

Run:

```bash
./gradlew :module-pipeline-artifact:test :module-infra:test :module-external-api:test :module-calculator:test :module-synchronizer:test :module-cleanup:test
./gradlew compileKotlin compileJava --continue
```

Expected: both commands exit `0`; no Testcontainers suite is introduced.

- [ ] **Step 4: Run non-destructive service smoke checks**

Build the four boot JARs, then execute the exact captured-application-PID `runtime_closure_boot_check` shell block from [ETL Runtime Ownership Closure, Task 6 Step 4](2026-07-19-etl-runtime-ownership-closure.md#task-6-run-focused-regression-runtime-boot-and-beforeafter-evidence):

```bash
./gradlew :module-external-api:bootJar :module-calculator:bootJar :module-synchronizer:bootJar :module-cleanup:bootJar
```

Use the helper unchanged for ports `8081` through `8084`; it captures logs, waits for health, and terminates only the application PID it started. Do not set `RESET_VIEWS` or `RESET_ACTIVE_JOBS`.

Expected: external-api `8081`, calculator `8082`, synchronizer `8083`, and cleanup `8084` report `UP`; no duplicate `ObjectStorage`, S3 client, transfer manager, or health-indicator bean appears.

- [ ] **Step 5: Capture after evidence**

Repeat Task 1's runtimeClasspath/JAR measurements and add:

- exact LocalFS write throughput using the same fixture, record count, compression level, JVM, and warmup as baseline;
- MinIO multipart throughput from the same opt-in contract/runtime setup;
- temp-file count before and after injected upload failure;
- confirmation that legacy object-key fixtures and Kafka DTO fixtures did not change.

Expected: no unexplained throughput regression; any material regression blocks this slice rather than being waived.

Rerun the two exact `ARTIFACT_EVIDENCE_ENABLED=1` commands from Task 1 after the test move/writer migration. The LocalFS JSON now comes from `module-pipeline-artifact/build/reports/artifact-evidence/`; the external writer JSON path is unchanged. Compare every repetition and medians, not the fastest sample, and preserve both before/after JSON hashes in the report.

- [ ] **Step 6: Commit wiring and evidence**

```bash
git add module-external-api module-calculator module-synchronizer module-cleanup docs/05_Reports/2026-07-19-pipeline-artifact-extraction-evidence.md
git commit -m "refactor: wire ETL services to artifact module"
```

## Plan Completion Gate

- [ ] `git diff --check` is clean.
- [ ] `rg -n 'maple\.expectation\.infrastructure\.storage' module-external-api/src/main module-calculator/src/main module-synchronizer/src/main module-cleanup/src/main` returns no matches.
- [ ] Raw `runs/`, `calculator/runs/`, `_RUNNING`, and `_SUCCESS` construction exists only in `module-pipeline-artifact` plus unchanged golden fixtures.
- [ ] The artifact spec's acceptance criteria are checked against tests/evidence; cleanup consumer/HTTP-drain activation and DLT routing are explicitly marked for the dependent messaging plan, not silently treated as complete here.
- [ ] Working tree contains only the intended implementation/docs changes before handoff.
