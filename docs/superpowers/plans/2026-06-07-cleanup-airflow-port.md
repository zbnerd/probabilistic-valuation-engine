# Cleanup → Airflow Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all 3 cleanup schedulers (ArtifactCleanupScheduler, CalculatorResultCleanupScheduler, ConsumedChunkCleanupScheduler) out of ext+calc Spring modules into a new `module-cleanup` Spring Boot app, with Airflow DAGs as the trigger.

**Architecture:**
- New `module-cleanup` Spring Boot app hosts the cleanup logic + REST endpoints.
- `module-infra` is NOT modified (zero new code).
- 2 cleanup paths: `runs/` (ext) and `calculator/runs/` (calc result). 1h Airflow cron each.
- 1 event-driven path: `synchronizer.chunk.consumed` → in-memory queue (Spring) → Airflow drain trigger (1h).
- docker-compose changes: deferred to separate PR (out of scope here).

**Tech Stack:** Kotlin 2.1, Spring Boot 3.5.4, Jackson, Kafka clients via module-infra (KafkaConsumerConfig only), Python 3.12 (Airflow), JUnit 5.

**Migration sequence (one-shot, big-bang):**
1. Deploy `module-cleanup` with `cleanup-inbox.auto-start=false` (consumer disabled).
2. Manually trigger old ext + calc cleanup endpoints to drain historical data.
3. Flip `cleanup-inbox.auto-start=true` in YAML and restart module-cleanup.
4. Deploy Airflow DAG `cleanup_pipeline.py`.
5. Delete old cleanup code from ext + calc modules.

---

## File Structure

**Created:**
- `docs/superpowers/specs/2026-06-07-cleanup-airflow-port-design.md` (design doc, per brainstorming flow)
- `module-cleanup/build.gradle`
- `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt`
- `module-cleanup/src/main/kotlin/maple/cleanup/config/CleanupProperties.kt`
- `module-cleanup/src/main/kotlin/maple/cleanup/inbox/InboxProperties.kt`
- `module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt`
- `module-cleanup/src/main/kotlin/maple/cleanup/inbox/ConsumedChunkInbox.kt`
- `module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt`
- `module-cleanup/src/main/kotlin/maple/cleanup/controller/InboxCleanupResponse.kt`
- `module-cleanup/src/main/resources/application.yml`
- `module-cleanup/src/main/resources/logback-spring.xml`
- `module-cleanup/src/test/kotlin/maple/cleanup/config/CleanupPropertiesTest.kt`
- `module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt`
- `module-cleanup/src/test/kotlin/maple/cleanup/inbox/ConsumedChunkInboxTest.kt`
- `module-cleanup/src/test/kotlin/maple/cleanup/controller/CleanupControllerTest.kt`
- `airflow/dags/cleanup_pipeline.py`

**Modified:**
- `settings.gradle` (add `module-cleanup`)

**Deleted:**
- `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` (cleanup endpoints only, surgical edit)
- `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt`
- `module-calculator/src/main/kotlin/maple/calculator/cleanup/InternalApiController.kt` (whole file)
- `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorCleanupProperties.kt`

**Unit boundaries:**
- `RunCleanupService` — single responsibility: invoke `RunCleanupExecutor` for a given path prefix. No state. Stateless. Raw `Files` API (YAGNI — MinIO 도입 시 ObjectStorage로 swap).
- `ConsumedChunkInbox` — single responsibility: collect Kafka events, expose drain. In-memory `ConcurrentLinkedQueue` + `AtomicInteger` for O(1) overflow check.
- `CleanupController` — single responsibility: HTTP ↔ service/inbox. No business logic. Injects `InboxProperties` for path resolution.
- `CleanupProperties` / `InboxProperties` — single responsibility: bind YAML to typed config.

---

### Task 1: Write design doc

**Files:**
- Create: `docs/superpowers/specs/2026-06-07-cleanup-airflow-port-design.md`

- [ ] **Step 1: Write design doc**

Decisions to record:
- New `module-cleanup` Spring Boot app (port 8084)
- module-infra unchanged
- 2 paths: `runs/`, `calculator/runs/`
- 3 schedulers all moved
- Event-driven pattern: Spring queue + Airflow drain trigger, consumer has `auto-start` toggle for migration
- All 1h frequency
- Big-bang migration with 5-step sequence (auto-start=false deploy, drain old, flip, Airflow, delete old code)
- ocid-mapping excluded (next OcidLookup cycle overwrites)
- docker-compose deferred

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-06-07-cleanup-airflow-port-design.md
git commit -m "docs(specs): add cleanup-airflow-port design"
```

---

### Task 2: Create module-cleanup Gradle skeleton

**Files:**
- Create: `module-cleanup/build.gradle`
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt`
- Modify: `settings.gradle`

- [ ] **Step 1: Create `module-cleanup/build.gradle`**

```gradle
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-infra"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

springBoot {
    mainClass.set("maple.cleanup.CleanupApplicationKt")
}
```

- [ ] **Step 2: Create `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt`**

```kotlin
package maple.cleanup

import maple.cleanup.config.CleanupProperties
import maple.cleanup.inbox.InboxProperties
import maple.expectation.infrastructure.config.KafkaConsumerConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication(
    exclude = [
        SecurityAutoConfiguration::class,
        ManagementWebSecurityAutoConfiguration::class,
    ]
)
@Import(KafkaConsumerConfig::class)
@EnableConfigurationProperties(CleanupProperties::class, InboxProperties::class)
class CleanupApplication

fun main(args: Array<String>) {
    runApplication<CleanupApplication>(*args)
}
```

Notes: `@Import(KafkaConsumerConfig::class)` is the ONLY infra import — no broad scan. JPA / Redis / WebClient are NOT pulled in (cleanup doesn't need them).

- [ ] **Step 3: Add to `settings.gradle`**

Append inside the `include` block:
```gradle
include "module-cleanup"
```

- [ ] **Step 4: Verify Gradle picks it up**

Run: `./gradlew :module-cleanup:tasks --quiet 2>&1 | head -10`
Expected: lists tasks for module-cleanup (no error).

- [ ] **Step 5: Commit**

```bash
git add module-cleanup/build.gradle module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt settings.gradle
git commit -m "feat(cleanup): add module-cleanup Gradle skeleton"
```

---

### Task 3: CleanupProperties (TDD)

**Files:**
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/config/CleanupProperties.kt`
- Create: `module-cleanup/src/main/resources/application.yml`

- [ ] **Step 1: Write failing test**

Create `module-cleanup/src/test/kotlin/maple/cleanup/config/CleanupPropertiesTest.kt`:

```kotlin
package maple.cleanup.config

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanupPropertiesTest {
    @Test
    fun `binds all cleanup config with sensible defaults`() {
        val props = CleanupProperties()
        assertTrue(props.dryRun)
        assertEquals(5, props.runs.keepRecent)
        assertEquals(48L, props.runs.keepWithinHours)
        assertEquals(10, props.maxDeleteRunsPerCycle)
        assertEquals(5L * 1024 * 1024 * 1024, props.maxDeleteBytesPerCycle)
        assertEquals(60L, props.maxRuntimeSeconds)
    }

    @Test
    fun `binds from yaml source with overrides`() {
        val source: ConfigurationPropertySource = MapConfigurationPropertySource(
            mapOf(
                "cleanup.dry-run" to "false",
                "cleanup.runs.keep-recent" to "3",
                "cleanup.runs.keep-within-hours" to "12",
                "cleanup.max-delete-runs-per-cycle" to "20",
            )
        )
        val bound = Binder(source).bind("cleanup", CleanupProperties::class.java)!!.value
        assertEquals(false, bound.dryRun)
        assertEquals(3, bound.runs.keepRecent)
        assertEquals(12L, bound.runs.keepWithinHours)
        assertEquals(20, bound.maxDeleteRunsPerCycle)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.config.CleanupPropertiesTest" 2>&1 | tail -20`
Expected: FAIL (class not found).

- [ ] **Step 3: Create `module-cleanup/src/main/kotlin/maple/cleanup/config/CleanupProperties.kt`**

```kotlin
package maple.cleanup.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cleanup")
data class CleanupProperties(
    val dryRun: Boolean = true,
    val runs: Runs = Runs(),
    val maxDeleteRunsPerCycle: Int = 10,
    /** 5 GB hard cap on bytes deleted per cleanup cycle. */
    val maxDeleteBytesPerCycle: Long = 5L * 1024 * 1024 * 1024,
    val maxRuntimeSeconds: Long = 60,
) {
    data class Runs(
        val keepRecent: Int = 5,
        val keepWithinHours: Long = 48,
    )
}
```

- [ ] **Step 4: Create `module-cleanup/src/main/resources/application.yml`**

```yaml
server:
  port: 8084

spring:
  application:
    name: cleanup
  profiles:
    active: local

cleanup:
  dry-run: true
  runs:
    keep-recent: 5
    keep-within-hours: 48
  max-delete-runs-per-cycle: 10
  max-delete-bytes-per-cycle: 5368709120
  max-runtime-seconds: 60

cleanup-inbox:
  topic: synchronizer.chunk.consumed
  consumer-group: cleanup-inbox
  base-path: ../data
  max-pending: 10000
  auto-start: true   # set to false during migration step 1; flip to true after historical drain

logging:
  level:
    maple.cleanup: INFO
    maple.expectation.infrastructure: INFO
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.config.CleanupPropertiesTest" 2>&1 | tail -15`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add module-cleanup/src/main/kotlin/maple/cleanup/config/CleanupProperties.kt \
        module-cleanup/src/main/resources/application.yml \
        module-cleanup/src/test/kotlin/maple/cleanup/config/CleanupPropertiesTest.kt
git commit -m "feat(cleanup): add CleanupProperties with tests"
```

---

### Task 4: RunCleanupService (TDD)

**Files:**
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt`
- Create: `module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.cleanup.service

import maple.common.cleanup.RunCleanupResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunCleanupServiceTest {
    @Test
    fun `cleanup of empty path returns ZERO result`(@TempDir tmp: Path) {
        val service = RunCleanupService(basePath = tmp.toString(), properties = props())
        val result = service.cleanupRuns()
        assertEquals(RunCleanupResult.ZERO, result)
    }

    @Test
    fun `cleanup deletes old runs and keeps recent ones`(@TempDir tmp: Path) {
        val runsDir = Files.createDirectory(tmp.resolve("runs"))
        val now = Instant.now()
        repeat(5) { i -> createRunDir(runsDir, "recent-$i", now.minusSeconds(3600L * (i + 1))) }
        createRunDir(runsDir, "old-1", now.minusSeconds(3600L * 72))
        createRunDir(runsDir, "old-2", now.minusSeconds(3600L * 100))

        val service = RunCleanupService(basePath = tmp.toString(), properties = props(dryRun = false))
        val result = service.cleanupRuns()

        assertEquals(2, result.runsDeleted)
        assertEquals(5, Files.list(runsDir).use { it.count() })
        assertTrue(Files.exists(runsDir.resolve("recent-0")))
        assertFalse(Files.exists(runsDir.resolve("old-1")))
        assertFalse(Files.exists(runsDir.resolve("old-2")))
    }

    @Test
    fun `cleanupCalculatorRuns targets calculator subdirectory`(@TempDir tmp: Path) {
        val calcRuns = Files.createDirectory(tmp.resolve("calculator/runs"))
        val now = Instant.now()
        createRunDir(calcRuns, "old-1", now.minusSeconds(3600L * 100))

        val service = RunCleanupService(basePath = tmp.toString(), properties = props(dryRun = false))
        val result = service.cleanupCalculatorRuns()

        assertEquals(1, result.runsDeleted)
        assertFalse(Files.exists(calcRuns.resolve("old-1")))
    }

    @Test
    fun `dryRun does not delete anything`(@TempDir tmp: Path) {
        val runsDir = Files.createDirectory(tmp.resolve("runs"))
        val now = Instant.now()
        createRunDir(runsDir, "old-1", now.minusSeconds(3600L * 100))

        val service = RunCleanupService(basePath = tmp.toString(), properties = props(dryRun = true))
        val result = service.cleanupRuns()

        assertEquals(0, result.runsDeleted)
        assertTrue(Files.exists(runsDir.resolve("old-1")))
    }

    private fun props(dryRun: Boolean = true) = maple.cleanup.config.CleanupProperties(
        dryRun = dryRun,
    )

    private fun createRunDir(parent: Path, name: String, modifiedAt: Instant) {
        val runDir = Files.createDirectory(parent.resolve(name))
        Files.setLastModifiedTime(runDir, FileTime.from(modifiedAt))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.service.RunCleanupServiceTest" 2>&1 | tail -10`
Expected: FAIL (class not found).

- [ ] **Step 3: Create `module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt`**

```kotlin
package maple.cleanup.service

import maple.cleanup.config.CleanupProperties
import maple.common.cleanup.RunCleanupExecutor
import maple.common.cleanup.RunCleanupResult
import maple.common.cleanup.RunInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Whole-run GC. Wraps the shared RunCleanupExecutor with a path prefix so the same
 * service can target either runs/ (ext source) or calculator/runs/ (calc result).
 *
 * Per-call: scan → filter active (_RUNNING sentinel) → invoke executor.
 * No @Scheduled — caller (Airflow HTTP trigger) is responsible for timing.
 */
@Service
class RunCleanupService(
    @Value("\${cleanup.base-path:../data}") private val basePath: String,
    private val properties: CleanupProperties,
) {
    private val log = LoggerFactory.getLogger(RunCleanupService::class.java)
    private val cleanupExecutor = RunCleanupExecutor("Cleanup")

    fun cleanupRuns(): RunCleanupResult = cleanupPrefix("runs")
    fun cleanupCalculatorRuns(): RunCleanupResult = cleanupPrefix("calculator/runs")

    fun cleanupPrefix(prefix: String): RunCleanupResult {
        val startedAt = Instant.now()
        log.info("[Cleanup] started prefix={} dryRun={}", prefix, properties.dryRun)

        val runDirs = listRunDirs(prefix)
        if (runDirs.isEmpty()) {
            log.info("[Cleanup] no runs found at {}/{}", basePath, prefix)
            return RunCleanupResult.ZERO
        }

        val runInfos = runDirs.mapNotNull { runId -> parseRunInfo(prefix, runId) }

        return cleanupExecutor.cleanup(
            runs = runInfos,
            dryRun = properties.dryRun,
            keepRecent = properties.runs.keepRecent,
            keepWithinHours = properties.runs.keepWithinHours,
            now = Instant.now(),
            maxDeleteRunsPerCycle = properties.maxDeleteRunsPerCycle,
            maxDeleteBytesPerCycle = properties.maxDeleteBytesPerCycle,
            maxRuntimeSeconds = properties.maxRuntimeSeconds,
            startedAt = startedAt,
            deleteRun = { run -> deleteDirectory("$prefix/${run.runId}") },
        )
    }

    private fun listRunDirs(prefix: String): List<String> {
        val path = Paths.get(basePath, prefix)
        if (!Files.exists(path)) return emptyList()
        return Files.list(path).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
    }

    private fun parseRunInfo(prefix: String, runId: String): RunInfo? {
        val fullPath = "$prefix/$runId"
        val runPath = Paths.get(basePath, fullPath)
        if (!Files.exists(runPath)) return null
        val runningMarker = Paths.get(basePath, "$fullPath/_RUNNING")
        if (Files.exists(runningMarker)) {
            log.info("[Cleanup] skipping active run: {}", runId)
            return null
        }
        val attrs = Files.readAttributes(runPath, BasicFileAttributes::class.java)
        val createdAt = Instant.ofEpochMilli(attrs.creationTime().toMillis())
        val sizeBytes = calculateDirectorySize(fullPath)
        return RunInfo(
            runId = runId,
            createdAt = createdAt,
            isRunning = false,
            sizeBytes = sizeBytes,
        )
    }

    private fun calculateDirectorySize(relativePath: String): Long {
        val path = Paths.get(basePath, relativePath)
        if (!Files.exists(path)) return 0L
        return Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToLong { Files.size(it) }
                .sum()
        }
    }

    private fun deleteDirectory(relativePath: String) {
        val path = Paths.get(basePath, relativePath)
        if (!Files.exists(path)) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.service.RunCleanupServiceTest" 2>&1 | tail -15`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt \
        module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt
git commit -m "feat(cleanup): add RunCleanupService for runs/ and calculator/runs/"
```

---

### Task 5: ConsumedChunkInbox + InboxProperties (TDD)

**Files:**
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/inbox/InboxProperties.kt`
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/inbox/ConsumedChunkInbox.kt`
- Create: `module-cleanup/src/test/kotlin/maple/cleanup/inbox/ConsumedChunkInboxTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.cleanup.inbox

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsumedChunkInboxTest {
    private val sampleEvent = """{"eventId":"e1","runId":"r1","endpoint":"basic","chunkId":"c1","objectKey":"k1","consumedAt":"2026-06-07T00:00:00Z"}"""

    @Test
    fun `consume adds event to queue`() {
        val inbox = ConsumedChunkInbox(objectMapper = jacksonMapper(), properties = props(maxPending = 100))
        inbox.consume(sampleEvent, mockAck())
        assertEquals(1, inbox.size())
    }

    @Test
    fun `consume skips malformed json and increments skip counter`() {
        val inbox = ConsumedChunkInbox(objectMapper = jacksonMapper(), properties = props(maxPending = 100))
        inbox.consume("not-json", mockAck())
        assertEquals(0, inbox.size())
        assertEquals(1L, inbox.skipped())
    }

    @Test
    fun `drain returns all queued events and clears queue`() {
        val inbox = ConsumedChunkInbox(objectMapper = jacksonMapper(), properties = props(maxPending = 100))
        inbox.consume(sampleEvent, mockAck())
        inbox.consume(sampleEvent.replace("c1", "c2"), mockAck())
        val drained = inbox.drain()
        assertEquals(2, drained.size)
        assertEquals(0, inbox.size())
    }

    @Test
    fun `drain on empty queue returns empty list`() {
        val inbox = ConsumedChunkInbox(objectMapper = jacksonMapper(), properties = props(maxPending = 100))
        assertEquals(emptyList(), inbox.drain())
    }

    @Test
    fun `pending overflow drops oldest and counts drop`() {
        val inbox = ConsumedChunkInbox(objectMapper = jacksonMapper(), properties = props(maxPending = 2))
        repeat(3) { i ->
            inbox.consume(sampleEvent.replace("c1", "c$i"), mockAck())
        }
        assertEquals(2, inbox.size())
        assertEquals(1L, inbox.dropped())
    }

    @Test
    fun `autoStart false means consume method is a no-op`() {
        val inbox = ConsumedChunkInbox(
            objectMapper = jacksonMapper(),
            properties = props(maxPending = 100, autoStart = false),
        )
        inbox.consume(sampleEvent, mockAck())
        assertEquals(0, inbox.size())
    }

    private fun props(maxPending: Int, autoStart: Boolean = true) = InboxProperties(maxPending = maxPending, autoStart = autoStart)
    private fun jacksonMapper() = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
    private fun mockAck() = org.mockito.kotlin.mock<org.springframework.kafka.support.Acknowledgment>()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.inbox.ConsumedChunkInboxTest" 2>&1 | tail -10`
Expected: FAIL.

- [ ] **Step 3: Create `module-cleanup/src/main/kotlin/maple/cleanup/inbox/InboxProperties.kt`**

```kotlin
package maple.cleanup.inbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cleanup-inbox")
data class InboxProperties(
    val topic: String = "synchronizer.chunk.consumed",
    val consumerGroup: String = "cleanup-inbox",
    val basePath: String = "../data",
    val maxPending: Int = 10_000,
    /** Set false during migration step 1 to disable Kafka consumer until historical drain is done. */
    val autoStart: Boolean = true,
)
```

- [ ] **Step 4: Create `module-cleanup/src/main/kotlin/maple/cleanup/inbox/ConsumedChunkInbox.kt`**

```kotlin
package maple.cleanup.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.ChunkConsumedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Event-driven inbox for synchronizer's CHUNK_CONSUMED events.
 * Replaces the old ConsumedChunkCleanupScheduler in module-external-api.
 *
 * The @KafkaListener populates the in-memory queue. The @Scheduled drain is
 * gone — Airflow now triggers /api/internal/cleanup/inbox every 1h.
 *
 * Bound: maxPending (default 10,000). Overflow drops oldest, increments dropped counter.
 * `autoStart=false` makes consume() a no-op (used during migration).
 */
@Component
class ConsumedChunkInbox(
    private val objectMapper: ObjectMapper,
    private val properties: InboxProperties,
) {
    private val log = LoggerFactory.getLogger(ConsumedChunkInbox::class.java)
    private val queue = ConcurrentLinkedQueue<ChunkConsumedEvent>()
    private val pendingCount = AtomicInteger(0)
    private val dropped = AtomicLong(0)
    private val skipped = AtomicLong(0)

    @KafkaListener(
        topics = ["\${cleanup-inbox.topic}"],
        groupId = ["\${cleanup-inbox.consumer-group}"],
        autoStartup = "\${cleanup-inbox.auto-start:true}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        if (!properties.autoStart) {
            acknowledgment.acknowledge()
            return
        }
        val event = runCatching {
            objectMapper.readValue(message, ChunkConsumedEvent::class.java)
        }.getOrElse { ex ->
            log.warn("[Inbox] failed to parse event: {}", ex.message)
            skipped.incrementAndGet()
            acknowledgment.acknowledge()
            return
        }
        // O(1) bound check via AtomicInteger
        if (pendingCount.incrementAndGet() > properties.maxPending) {
            queue.poll()
            pendingCount.decrementAndGet()
            dropped.incrementAndGet()
            log.warn("[Inbox] pending queue at capacity ({}), dropped oldest", properties.maxPending)
        }
        queue.add(event)
        log.debug("[Inbox] queued: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
        acknowledgment.acknowledge()
    }

    fun size(): Int = pendingCount.get()
    fun dropped(): Long = dropped.get()
    fun skipped(): Long = skipped.get()

    fun drain(): List<ChunkConsumedEvent> {
        val out = mutableListOf<ChunkConsumedEvent>()
        while (true) {
            val event = queue.poll() ?: break
            pendingCount.decrementAndGet()
            out.add(event)
        }
        return out
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.inbox.ConsumedChunkInboxTest" 2>&1 | tail -15`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add module-cleanup/src/main/kotlin/maple/cleanup/inbox/ \
        module-cleanup/src/test/kotlin/maple/cleanup/inbox/ConsumedChunkInboxTest.kt
git commit -m "feat(cleanup): add ConsumedChunkInbox with autoStart gate + overflow guard"
```

---

### Task 6: CleanupController (TDD)

**Files:**
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/controller/InboxCleanupResponse.kt`
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt`
- Create: `module-cleanup/src/test/kotlin/maple/cleanup/controller/CleanupControllerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.cleanup.controller

import com.fasterxml.jackson.databind.ObjectMapper
import maple.cleanup.inbox.ConsumedChunkInbox
import maple.cleanup.service.RunCleanupService
import maple.common.cleanup.RunCleanupResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.mockito.kotlin.whenever

@WebMvcTest(CleanupController::class)
class CleanupControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockBean lateinit var runCleanupService: RunCleanupService
    @MockBean lateinit var inbox: ConsumedChunkInbox

    @Test
    fun `POST cleanup-runs returns result`() {
        whenever(runCleanupService.cleanupRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 3, bytesDeleted = 1024, errors = 0, throttled = 0)
        )
        mockMvc.perform(post("/api/internal/cleanup/runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runsDeleted").value(3))
            .andExpect(jsonPath("$.bytesDeleted").value(1024))
    }

    @Test
    fun `POST cleanup-calculator-runs returns result`() {
        whenever(runCleanupService.cleanupCalculatorRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 5, bytesDeleted = 2048, errors = 0, throttled = 0)
        )
        mockMvc.perform(post("/api/internal/cleanup/calculator-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runsDeleted").value(5))
    }

    @Test
    fun `POST cleanup-inbox drains and returns response with deleted and failed counts`() {
        whenever(inbox.drain()).thenReturn(emptyList())
        mockMvc.perform(post("/api/internal/cleanup/inbox"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(0))
            .andExpect(jsonPath("$.failed").value(0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.controller.CleanupControllerTest" 2>&1 | tail -10`
Expected: FAIL.

- [ ] **Step 3: Create `module-cleanup/src/main/kotlin/maple/cleanup/controller/InboxCleanupResponse.kt`**

```kotlin
package maple.cleanup.controller

data class InboxCleanupResponse(
    val drained: Int,
    val deleted: Int,
    val failed: Int,
)
```

- [ ] **Step 4: Create `module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt`**

```kotlin
package maple.cleanup.controller

import maple.cleanup.inbox.ConsumedChunkInbox
import maple.cleanup.inbox.InboxProperties
import maple.cleanup.service.RunCleanupService
import maple.common.cleanup.RunCleanupResult
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * HTTP endpoints for Airflow-triggered cleanup.
 *
 * - POST /api/internal/cleanup/runs            → whole-run GC for runs/
 * - POST /api/internal/cleanup/calculator-runs  → whole-run GC for calculator/runs/
 * - POST /api/internal/cleanup/inbox           → drain event queue + delete file per event
 *
 * No @Scheduled — timing is Airflow's responsibility.
 * Inbox deleteFile uses InboxProperties.basePath (NOT hardcoded) so config changes
 * take effect without recompile.
 */
@RestController
@RequestMapping("/api/internal/cleanup")
class CleanupController(
    private val runCleanupService: RunCleanupService,
    private val inbox: ConsumedChunkInbox,
    private val inboxProperties: InboxProperties,
) {
    private val log = LoggerFactory.getLogger(CleanupController::class.java)

    @PostMapping("/runs")
    fun cleanupRuns(): ResponseEntity<RunCleanupResult> {
        log.info("[CleanupController] POST /runs")
        return ResponseEntity.ok(runCleanupService.cleanupRuns())
    }

    @PostMapping("/calculator-runs")
    fun cleanupCalculatorRuns(): ResponseEntity<RunCleanupResult> {
        log.info("[CleanupController] POST /calculator-runs")
        return ResponseEntity.ok(runCleanupService.cleanupCalculatorRuns())
    }

    @PostMapping("/inbox")
    fun cleanupInbox(): ResponseEntity<InboxCleanupResponse> {
        log.info("[CleanupController] POST /inbox, size={}", inbox.size())
        val events = inbox.drain()
        var deleted = 0
        var failed = 0
        events.forEach { event ->
            if (deleteFile(event.objectKey)) deleted++ else failed++
            event.sourceObjectKey?.let { if (deleteFile(it)) deleted++ else failed++ }
        }
        log.info("[CleanupController] inbox: drained={} deleted={} failed={}", events.size, deleted, failed)
        return ResponseEntity.ok(InboxCleanupResponse(drained = events.size, deleted = deleted, failed = failed))
    }

    private fun deleteFile(objectKey: String): Boolean = try {
        val path: Path = Paths.get(inboxProperties.basePath, objectKey)
        Files.deleteIfExists(path)
    } catch (ex: java.io.IOException) {
        log.error("[CleanupController] delete failed: {} - {}", objectKey, ex.message, ex)
        false
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-cleanup:test --tests "maple.cleanup.controller.CleanupControllerTest" 2>&1 | tail -15`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add module-cleanup/src/main/kotlin/maple/cleanup/controller/ \
        module-cleanup/src/test/kotlin/maple/cleanup/controller/
git commit -m "feat(cleanup): add CleanupController with InboxProperties injection"
```

---

### Task 7: Create logback-spring.xml

**Files:**
- Create: `module-cleanup/src/main/resources/logback-spring.xml`

- [ ] **Step 1: Create file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="APP_NAME" source="spring.application.name" defaultValue="cleanup"/>
    <springProperty scope="context" name="SERVER_PORT" source="server.port" defaultValue="8084"/>
    <property name="HOST" value="${HOSTNAME:-${HOST:-unknown}}"/>

    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}","port":"${SERVER_PORT}","host":"${HOST}"}</customFields>
            <includeMdc>true</includeMdc>
            <fieldNames><timestamp>timestamp</timestamp><version>[ignore]</version><levelValue>[ignore]</levelValue></fieldNames>
        </encoder>
    </appender>

    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/${APP_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/${APP_NAME}.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>3</maxHistory>
            <totalSizeCap>500MB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${APP_NAME}","port":"${SERVER_PORT}","host":"${HOST}"}</customFields>
        </encoder>
    </appender>

    <springProfile name="local">
        <appender name="PLAIN_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
        </appender>
        <root level="INFO"><appender-ref ref="PLAIN_CONSOLE"/></root>
    </springProfile>

    <springProfile name="!local">
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
            <appender-ref ref="JSON_FILE"/>
        </root>
    </springProfile>

    <logger name="maple.cleanup" level="INFO"/>
</configuration>
```

- [ ] **Step 2: Commit**

```bash
git add module-cleanup/src/main/resources/logback-spring.xml
git commit -m "feat(cleanup): add logback config"
```

---

### Task 8: Create Airflow DAG

**Files:**
- Create: `airflow/dags/cleanup_pipeline.py`

- [ ] **Step 1: Create DAG file**

```python
"""
Pipeline cleanup DAG (replaces Spring @Scheduled).

Triggers module-cleanup HTTP endpoints every 1h. Module-cleanup must be
reachable via host.docker.internal:8084 from the Airflow scheduler container.

Three independent tasks; each is fire-and-forget. Failures in one do not
block the others.
"""
from datetime import datetime, timedelta
from airflow import DAG
from airflow.providers.http.operators.http import SimpleHttpOperator

default_args = {
    "owner": "pipeline",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="cleanup_pipeline",
    description="Triggers module-cleanup endpoints (runs/, calculator-runs/, inbox)",
    default_args=default_args,
    schedule_interval="0 * * * *",  # hourly
    start_date=datetime(2026, 6, 7),
    catchup=False,
    tags=["cleanup", "pipeline"],
) as dag:

    cleanup_runs = SimpleHttpOperator(
        task_id="cleanup_runs",
        http_conn_id="module_cleanup",
        endpoint="/api/internal/cleanup/runs",
        method="POST",
        response_check=lambda r: r.status_code == 200,
        log_response=True,
    )

    cleanup_calculator_runs = SimpleHttpOperator(
        task_id="cleanup_calculator_runs",
        http_conn_id="module_cleanup",
        endpoint="/api/internal/cleanup/calculator-runs",
        method="POST",
        response_check=lambda r: r.status_code == 200,
        log_response=True,
    )

    cleanup_inbox = SimpleHttpOperator(
        task_id="cleanup_inbox",
        http_conn_id="module_cleanup",
        endpoint="/api/internal/cleanup/inbox",
        method="POST",
        response_check=lambda r: r.status_code == 200,
        log_response=True,
    )

    # Independent tasks — no order dependency
    [cleanup_runs, cleanup_calculator_runs, cleanup_inbox]
```

- [ ] **Step 2: Add Airflow connection (manual step — out of repo)**

```bash
docker exec maple-airflow-scheduler airflow connections add module_cleanup \
  --conn-type http --conn-host host.docker.internal --conn-port 8084 --conn-schema http
```

- [ ] **Step 3: Commit**

```bash
git add airflow/dags/cleanup_pipeline.py
git commit -m "feat(airflow): add cleanup_pipeline DAG with 3 hourly tasks"
```

---

### Task 9: Delete ext cleanup code (surgical)

**Files:**
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt`
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` (remove specific lines)

- [ ] **Step 1: Delete scheduler files**

```bash
git rm module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt
git rm module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt
rmdir module-external-api/src/main/kotlin/maple/externalapi/cleanup/ 2>/dev/null || true
```

- [ ] **Step 2: Surgical edit to `InternalApiController.kt`**

Remove these EXACT lines/blocks (do not refactor surrounding code):

1. **Imports (lines 3-4)** — remove:
```kotlin
import maple.externalapi.cleanup.ArtifactCleanupScheduler
import maple.externalapi.cleanup.ConsumedChunkCleanupScheduler
```

2. **Constructor parameters (lines 20-21)** — remove:
```kotlin
    @Autowired(required = false) private val artifactCleanup: ArtifactCleanupScheduler?,
    @Autowired(required = false) private val consumedCleanup: ConsumedChunkCleanupScheduler?,
```

3. **Field declarations (lines 24-25)** — remove:
```kotlin
    private val artifactCleanupRunning = AtomicBoolean(false)
    private val consumedCleanupRunning = AtomicBoolean(false)
```

4. **Method `triggerArtifactCleanup` (lines 51-65)** — remove entire method.

5. **Method `triggerConsumedCleanup` (lines 67-81)** — remove entire method.

Keep all other code (`/run-status`, `/trigger/daily`, `RunStatusResponse`).

- [ ] **Step 3: Verify ext still compiles**

Run: `./gradlew :module-external-api:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify ext tests pass**

Run: `./gradlew :module-external-api:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A module-external-api/
git commit -m "refactor(ext): remove cleanup schedulers + trigger endpoints, port to module-cleanup"
```

---

### Task 10: Delete calc cleanup code (whole file)

**Files:**
- Delete: `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt`
- Delete: `module-calculator/src/main/kotlin/maple/calculator/cleanup/InternalApiController.kt` (whole file)
- Delete: `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorCleanupProperties.kt`

- [ ] **Step 1: Delete files**

```bash
git rm module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt
git rm module-calculator/src/main/kotlin/maple/calculator/cleanup/InternalApiController.kt
git rm module-calculator/src/main/kotlin/maple/calculator/config/CalculatorCleanupProperties.kt
rmdir module-calculator/src/main/kotlin/maple/calculator/cleanup/ 2>/dev/null || true
```

- [ ] **Step 2: Remove `CalculatorCleanupProperties` reference from `CalculatorApplication.kt`**

Edit `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt`:

Remove:
```kotlin
import maple.calculator.config.CalculatorCleanupProperties
```

And from `@EnableConfigurationProperties` list — remove `CalculatorCleanupProperties::class` so it becomes:
```kotlin
@EnableConfigurationProperties(PipelineProperties::class)
```

- [ ] **Step 3: Verify calc compiles**

Run: `./gradlew :module-calculator:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify calc tests pass**

Run: `./gradlew :module-calculator:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A module-calculator/
git commit -m "refactor(calc): remove cleanup schedulers + props, port to module-cleanup"
```

---

### Task 11: Full Gradle compile + test

- [ ] **Step 1: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run all tests**

Run: `./gradlew test 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build module-cleanup bootJar**

Run: `./gradlew :module-cleanup:bootJar 2>&1 | tail -10`
Expected: `module-cleanup-0.0.1-SNAPSHOT.jar` in `module-cleanup/build/libs/`.

---

### Task 12: Migration sequence (manual, big-bang)

Per the migration sequence in the plan header:

- [ ] **Step 1: Deploy module-cleanup with consumer disabled**

Set in `module-cleanup/src/main/resources/application.yml`:
```yaml
cleanup-inbox:
  auto-start: false
```

Build + start:
```bash
./gradlew :module-cleanup:bootJar
set -a && source .env && set +a
export SPRING_PROFILES_ACTIVE=local
nohup java -Xms512m -Xmx1g -jar module-cleanup/build/libs/module-cleanup-0.0.1-SNAPSHOT.jar > logs/cleanup.log 2>&1 &
until curl -sf http://localhost:8084/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "cleanup UP (consumer disabled)"
```

- [ ] **Step 2: Verify consumer disabled**

```bash
docker exec maple-kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group cleanup-inbox 2>&1 | head -5
```
Expected: "Consumer group 'cleanup-inbox' has no active members" (consumer not started).

- [ ] **Step 3: Trigger historical cleanup via OLD endpoints (still alive until Task 9/10 deployed)**

For ext `runs/` (if ext module still has the old code):
```bash
curl -s -X POST http://localhost:8081/api/internal/trigger/artifact-cleanup | python3 -m json.tool
curl -s -X POST http://localhost:8081/api/internal/trigger/consumed-cleanup | python3 -m json.tool
```

For calc `calculator/runs/`:
```bash
curl -s -X POST http://localhost:8082/api/internal/trigger/result-cleanup | python3 -m json.tool
```

Expected: each returns `{"status": "STARTED"}` or `ALREADY_RUNNING`.

- [ ] **Step 4: Wait for historical cleanup to complete (~minutes)**

```bash
echo "size before:"
du -sh ../data/runs ../data/calculator/runs
sleep 60
echo "size after:"
du -sh ../data/runs ../data/calculator/runs
```
Expected: significant size reduction (5 runs left in each).

- [ ] **Step 5: Flip auto-start to true, restart module-cleanup**

Edit `module-cleanup/src/main/resources/application.yml`:
```yaml
cleanup-inbox:
  auto-start: true
```

```bash
lsof -ti:8084 2>/dev/null | xargs -r kill -15
sleep 3
lsof -ti:8084 2>/dev/null | xargs -r kill -9
nohup java -Xms512m -Xmx1g -jar module-cleanup/build/libs/module-cleanup-0.0.1-SNAPSHOT.jar > logs/cleanup.log 2>&1 &
until curl -sf http://localhost:8084/actuator/health > /dev/null 2>&1; do sleep 2; done
```

- [ ] **Step 6: Verify consumer now active**

```bash
docker exec maple-kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group cleanup-inbox 2>&1 | head -5
```
Expected: active member listed.

- [ ] **Step 7: Deploy Airflow DAG**

Copy `airflow/dags/cleanup_pipeline.py` to where Airflow's DAGs are mounted (per existing `docker-compose.airflow.yml`):
```bash
docker cp airflow/dags/cleanup_pipeline.py maple-airflow-scheduler:/opt/airflow/dags/cleanup_pipeline.py
```

Or rely on volume mount if already configured.

- [ ] **Step 8: Deploy Tasks 9/10 (delete old ext + calc cleanup code)**

Follow Tasks 9 and 10 above. After this, old cleanup endpoints no longer exist. Only the Airflow-triggered new module-cleanup endpoints work.

- [ ] **Step 9: Verify final state**

```bash
echo "===health checks==="
for p in 8081 8082 8083 8084; do
  s=$(curl -sf http://localhost:$p/actuator/health 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin).get('status','?'))" 2>/dev/null)
  echo "port=$p: $s"
done

echo "===module-cleanup endpoints==="
curl -s -w "HTTP %{http_code}\n" -X POST http://localhost:8084/api/internal/cleanup/runs | head -c 300; echo
curl -s -w "HTTP %{http_code}\n" -X POST http://localhost:8084/api/internal/cleanup/calculator-runs | head -c 300; echo
curl -s -w "HTTP %{http_code}\n" -X POST http://localhost:8084/api/internal/cleanup/inbox | head -c 300; echo

echo "===old endpoints (should 404)==="
curl -s -w "HTTP %{http_code}\n" -X POST http://localhost:8081/api/internal/trigger/artifact-cleanup 2>&1 | head -c 100
```

Expected:
- All 4 health checks UP
- 3 new endpoints return 200 with JSON
- Old endpoints return 404 (after Task 9/10 deployed)

- [ ] **Step 10: Commit any post-deploy tweaks**

```bash
git status
git diff
```

If anything modified:
```bash
git add -A
git commit -m "chore(cleanup): post-deploy tweaks"
```

---

## Self-Review

**Spec coverage:**
- ✅ New module-cleanup Spring Boot app — Tasks 2-7
- ✅ RunCleanupService for `runs/` and `calculator/runs/` — Task 4
- ✅ ConsumedChunkInbox with `auto-start` gate + overflow guard — Task 5
- ✅ 3 HTTP endpoints — Task 6
- ✅ Airflow DAG (3 tasks, 1h) — Task 8
- ✅ Delete ext cleanup code (surgical edit with line numbers) — Task 9
- ✅ Delete calc cleanup code (whole file) — Task 10
- ✅ Big-bang cutover with 5-step migration sequence — Task 12
- ✅ ocid-mapping excluded — not in design, no task needed
- ✅ docker-compose deferred — no task

**Placeholder scan:** No "TBD" / "TODO" / "implement later" / "add appropriate handling" in steps. Task 9 step 2 has exact line numbers, no "read first" placeholders.

**Type consistency:**
- `RunCleanupResult` used in test and impl — same module-common type ✓
- `ChunkConsumedEvent` — same module-common type ✓
- `CleanupProperties` and `InboxProperties` — distinct types, both registered ✓
- `InboxCleanupResponse` — new data class, used by controller and test ✓
- `InboxProperties.basePath` consumed by `CleanupController.deleteFile` (Q3 fix) ✓

**Resolved decisions logged:**
- Q1: Migration sequence (manual historical drain first)
- Q2: `@KafkaListener(autoStartup="${...}")` for consumer gating
- Q3: `InboxProperties` inject + typed `InboxCleanupResponse` response
- Q4: `AtomicInteger pendingCount` + explicit evict on overflow
- Q5: Surgical edit with line numbers (Task 9 step 2)
- Q6: Raw `Files` API (YAGNI for MinIO future)
- Q7: `@Import(KafkaConsumerConfig::class)` only
- Q8: Manual verification via Task 12 (no auto integration test)

**Known risks accepted:**
- Task 9 step 2 surgical edit is manual; reviewer must verify the exact lines.
- First Task 12 step 1 deploy has `auto-start=false`; if missed, historical data leak in Kafka backlog.
- No integration test for migration sequence — production cutover is the first real test.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-07-cleanup-airflow-port.md`. Worktree: `cleanup-airflow-port` on branch `refactor/cleanup-airflow-port`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** - execute tasks in this session using executing-plans, batch with checkpoints

Which approach?
