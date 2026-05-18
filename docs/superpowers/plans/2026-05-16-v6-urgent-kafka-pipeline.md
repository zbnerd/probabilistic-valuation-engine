# V6 Urgent Kafka Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V6 read endpoint에서 character data가 없을 때 urgent Kafka pipeline을 트리거하여 Nexon API에서 데이터를 fetch하고 read model에 저장. 이후 재요청 시 cache hit.

**Architecture:** `module-rest-controller` (Kafka producer) → `module-external-api` (OCID + API fetch + chunk publish) → `module-calculator` → `module-synchronizer` → read model upsert. Urgent는 전용 Kafka topic 사용. Nexon 400 → negative cache (Redis) → 404.

**Tech Stack:** Spring Kafka, Kotlin, Redis (negative cache), Nexon API, gzip+jsonl chunks

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `module-rest-controller/.../urgent/UrgentCharacterRequest.kt` | Kafka message DTO |
| `module-rest-controller/.../urgent/UrgentTriggerPublisher.kt` | Kafka producer (urgent trigger) |
| `module-rest-controller/.../urgent/UrgentCharacterNotFoundConsumer.kt` | Kafka consumer (negative cache feedback) |
| `module-external-api/.../urgent/UrgentCharacterRequestConsumer.kt` | Kafka consumer + OCID + API fetch + chunk publish |

### Modified Files

| File | Change |
|------|--------|
| `module-rest-controller/build.gradle` | Add `spring-kafka` dependency |
| `module-rest-controller/.../application.yml` | Add Kafka producer/consumer config + urgent topic names |
| `module-rest-controller/.../read/ReadModelCacheService.kt` | Add negative cache get/set methods |
| `module-rest-controller/.../read/BatchReadScheduler.kt` | Add urgent trigger on total miss |
| `module-rest-controller/.../metrics/V6ReadMetrics.kt` | Add urgent trigger metrics |
| `module-external-api/.../application.yml` | Add urgent topic config |
| `module-calculator/.../consumer/KafkaSnapshotChunkReadyConsumer.kt` | Add urgent topic listener method |
| `module-calculator/.../application.yml` | Add urgent topic + consumer group config |
| `module-synchronizer/.../consumer/BasicSnapshotChunkConsumer.kt` | Add urgent basic chunk listener + OCID upsert after basic data processed |
| `module-synchronizer/.../application.yml` | Add urgent basic topic + consumer group config |

---

## Task 0: Prerequisites

**Files:**
- Create: `docs/01_ADR/ADR-XXX_v6-urgent-kafka-pipeline.md`

- [ ] **Step 1: Write ADR document**

Follow `.claude/rules/adr-conventions.md` template. Key decision: urgent pipeline uses dedicated Kafka topics (not shared with batch), module-rest-controller adds Kafka producer for trigger only.

- [ ] **Step 2: Create feature branch**

```bash
git checkout develop && git pull origin develop
git checkout -b feature/v6-urgent-kafka-pipeline
```

- [ ] **Step 3: Commit ADR**

```bash
git add docs/01_ADR/ADR-XXX_v6-urgent-kafka-pipeline.md
git commit -m "docs: add ADR for V6 urgent Kafka pipeline"
```

---

## Task 1: module-rest-controller — Kafka Infrastructure

**Files:**
- Modify: `module-rest-controller/build.gradle`
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentCharacterRequest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentTriggerPublisher.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/urgent/UrgentTriggerPublisherTest.kt`

- [ ] **Step 1: Add spring-kafka dependency to build.gradle**

In `module-rest-controller/build.gradle`, add after the Redis dependency block (line 31):

```groovy
// Kafka for urgent pipeline trigger
implementation(libs.spring.kafka)
```

- [ ] **Step 2: Add Kafka config to application.yml**

Read the full `module-rest-controller/src/main/resources/application.yml` first. Add Kafka section inside the existing `spring:` block (after `datasource`, before the block closes):

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
    consumer:
      group-id: rest-controller-urgent-feedback
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: manual_immediate
```

Add urgent topic config under the existing `expectation.v6:` block:

```yaml
expectation:
  v6:
    # ... existing fields ...
    urgent:
      enabled: false                              # feature flag
      request-topic: urgent-character-request
      not-found-topic: urgent-character-not-found
      negative-cache-ttl-seconds: 3600            # 1 hour
```

- [ ] **Step 3: Create UrgentCharacterRequest DTO**

Create `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentCharacterRequest.kt`:

```kotlin
package maple.restcontroller.urgent

import java.time.Instant
import java.util.UUID

data class UrgentCharacterRequest(
    val eventId: String = UUID.randomUUID().toString(),
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: Instant = Instant.now()
)
```

- [ ] **Step 4: Create UrgentTriggerPublisher**

Create `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentTriggerPublisher.kt`:

```kotlin
package maple.restcontroller.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate

class UrgentTriggerPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val topic: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(request: UrgentCharacterRequest) {
        val json = objectMapper.writeValueAsString(request)
        kafkaTemplate.send(topic, request.userIgn, json)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("Failed to publish urgent request: userIgn={}, topic={}", request.userIgn, topic, ex)
                } else {
                    log.info("Published urgent request: userIgn={}, topic={}, partition={}, offset={}",
                        request.userIgn, topic, result.recordMetadata.partition(), result.recordMetadata.offset())
                }
            }
    }
}
```

- [ ] **Step 5: Write test for UrgentTriggerPublisher**

Create `module-rest-controller/src/test/kotlin/maple/restcontroller/urgent/UrgentTriggerPublisherTest.kt`:

```kotlin
package maple.restcontroller.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class UrgentTriggerPublisherTest {

    private val kafkaTemplate: KafkaTemplate<String, String> = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val topic = "urgent-character-request"
    private val publisher = UrgentTriggerPublisher(kafkaTemplate, objectMapper, topic)

    @Test
    fun `publish sends message with userIgn as key`() {
        val request = UrgentCharacterRequest(userIgn = "testCharacter")
        val sendResult = CompletableFuture.completedFuture<SendResult<String, String>>(
            SendResult(RecordMetadata(TopicPartition(topic, 0), null, 0L, 0L, 0L, 0, 0), null)
        )

        whenever(kafkaTemplate.send(eq(topic), eq("testCharacter"), any())).thenReturn(sendResult)

        publisher.publish(request)

        verify(kafkaTemplate).send(eq(topic), eq("testCharacter"), argThat { contains("testCharacter") })
    }
}
```

- [ ] **Step 6: Register beans in config**

Read `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt`. Add bean definitions for `UrgentTriggerPublisher`. Use `@ConditionalOnProperty(name = ["expectation.v6.urgent.enabled"], havingValue = "true")` to match the feature flag.

The publisher bean needs:
- `KafkaTemplate<String, String>` (auto-configured by Spring Kafka)
- `ObjectMapper` (already available)
- Topic name from `@Value("\${expectation.v6.urgent.request-topic}")`

- [ ] **Step 7: Run compile to verify**

```bash
./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava --continue
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Run tests**

```bash
./gradlew :module-rest-controller:test
```

Expected: All tests pass

- [ ] **Step 9: Commit**

```bash
git add module-rest-controller/
git commit -m "feat(rest-controller): add Kafka infrastructure for urgent pipeline trigger"
```

---

## Task 2: module-rest-controller — Negative Cache

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelCacheService.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentCharacterNotFoundConsumer.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/urgent/UrgentCharacterNotFoundConsumerTest.kt`

- [ ] **Step 1: Add negative cache methods to ReadModelCacheService**

Add to `ReadModelCacheService` after the existing `multiPut` method:

```kotlin
companion object {
    private const val KEY_PREFIX = "v6:read"
    private const val NEGATIVE_KEY_PREFIX = "v6:not-found"
    private const val URGENT_PENDING_PREFIX = "v6:urgent-pending"
}

// --- Negative cache (non-existent characters) ---

fun negativeCacheKey(userIgn: String): String = "$NEGATIVE_KEY_PREFIX:$userIgn"

fun getNegativeCache(userIgn: String): Boolean {
    return redisTemplate.hasKey(negativeCacheKey(userIgn))
}

fun setNegativeCache(userIgn: String, ttlSeconds: Long) {
    redisTemplate.opsForValue().set(negativeCacheKey(userIgn), "NOT_FOUND", Duration.ofSeconds(ttlSeconds))
    log.info("Set negative cache: userIgn={}", userIgn)
}

// --- Urgent dedup (prevent duplicate triggers) ---

fun urgentPendingKey(userIgn: String): String = "$URGENT_PENDING_PREFIX:$userIgn"

fun tryMarkUrgentPending(userIgn: String, ttlSeconds: Long = 30): Boolean {
    val result = redisTemplate.opsForValue()
        .setIfAbsent(urgentPendingKey(userIgn), "1", Duration.ofSeconds(ttlSeconds))
    return result == true
}
```

- [ ] **Step 2: Create UrgentCharacterNotFoundConsumer**

Create `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentCharacterNotFoundConsumer.kt`:

```kotlin
package maple.restcontroller.urgent

import maple.restcontroller.read.ReadModelCacheService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["expectation.v6.urgent.enabled"], havingValue = "true")
class UrgentCharacterNotFoundConsumer(
    private val cacheService: ReadModelCacheService,
    private val objectMapper: ObjectMapper,
    @Value("\${expectation.v6.urgent.negative-cache-ttl-seconds}") private val negativeCacheTtlSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${expectation.v6.urgent.not-found-topic}"],
        groupId = "\${spring.kafka.consumer.group-id}"
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val userIgn = objectMapper.readTree(message).get("userIgn").asText()
        log.info("Received character-not-found event: userIgn={}", userIgn)
        cacheService.setNegativeCache(userIgn, negativeCacheTtlSeconds)
        acknowledgment.acknowledge()
    }
}
```

- [ ] **Step 3: Write test for UrgentCharacterNotFoundConsumer**

```kotlin
package maple.restcontroller.urgent

import maple.restcontroller.read.ReadModelCacheService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.support.Acknowledgment

class UrgentCharacterNotFoundConsumerTest {

    private val cacheService: ReadModelCacheService = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val acknowledgment: Acknowledgment = mock()
    private val consumer = UrgentCharacterNotFoundConsumer(cacheService, objectMapper, 3600L)

    @Test
    fun `consume sets negative cache and acknowledges`() {
        val message = """{"userIgn":"unknownChar","reason":"OPENAPI00004"}"""

        consumer.consume(message, acknowledgment)

        verify(cacheService).setNegativeCache("unknownChar", 3600L)
        verify(acknowledgment).acknowledge()
    }
}
```

- [ ] **Step 4: Run compile + test**

```bash
./gradlew :module-rest-controller:compileKotlin :module-rest-controller:test --continue
```

Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/
git commit -m "feat(rest-controller): add negative cache for non-existent characters"
```

---

## Task 3: module-rest-controller — Urgent Trigger in BatchReadScheduler

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/metrics/V6ReadMetrics.kt`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt`

- [ ] **Step 1: Add urgent trigger metric to V6ReadMetrics**

Read `module-rest-controller/src/main/kotlin/maple/restcontroller/metrics/V6ReadMetrics.kt`. Add:

```kotlin
val urgentTriggerTotal: Counter = Counter.builder("v6_urgent_trigger_total")
    .description("Total urgent pipeline triggers")
    .register(meterRegistry)
```

- [ ] **Step 2: Modify BatchReadScheduler to trigger urgent on miss**

Add `UrgentTriggerPublisher` as constructor parameter:

```kotlin
class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val registry: InflightRequestRegistry,
    private val queryService: ReadModelQueryService,
    private val cacheService: ReadModelCacheService,
    private val urgentPublisher: UrgentTriggerPublisher?,  // nullable — only when urgent enabled
    private val metrics: V6ReadMetrics,
    private val properties: V6ReadProperties
) : SmartLifecycle {
```

In `resolveBatch()`, modify the miss handling block (lines 99-110). After `metrics.recordMiss("read_model_empty")`, add:

```kotlin
} else {
    metrics.recordMiss("read_model_empty")

    // Check negative cache first
    if (cacheService.getNegativeCache(userIgn)) {
        val deferreds = registry.getAndRemove(userIgn)
        deferreds.forEach {
            it.setResult(ResponseEntity.status(404)
                .header("X-Error-Reason", "character-not-found")
                .build<Any>())
        }
        return@forEach
    }

    // Trigger urgent pipeline (with dedup)
    if (urgentPublisher != null && cacheService.tryMarkUrgentPending(userIgn)) {
        val presetNo = cacheMisses[userIgn] ?: 1
        urgentPublisher.publish(UrgentCharacterRequest(userIgn = userIgn, presetNo = presetNo))
        metrics.urgentTriggerTotal.increment()
        log.info("Triggered urgent pipeline: userIgn={}", userIgn)
    }

    // Deferred will time out → 202 Accepted
}
```

- [ ] **Step 3: Update V6ReadConfig to wire UrgentTriggerPublisher into BatchReadScheduler**

Read `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt`. When `urgent.enabled=true`, create `UrgentTriggerPublisher` bean and pass to `BatchReadScheduler`. When `false`, pass `null`.

- [ ] **Step 4: Run compile + test**

```bash
./gradlew :module-rest-controller:compileKotlin :module-rest-controller:test --continue
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/
git commit -m "feat(rest-controller): trigger urgent Kafka pipeline on V6 read miss"
```

---

## Task 4: module-external-api — Urgent Consumer

**Files:**
- Modify: `module-external-api/src/main/resources/application.yml`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumerTest.kt`

- [ ] **Step 1: Add urgent topic config to application.yml**

Read the full `module-external-api/src/main/resources/application.yml`. Add inside the existing `external-api:` block:

```yaml
external-api:
  # ... existing config ...
  urgent:
    request-topic: urgent-character-request
    not-found-topic: urgent-character-not-found
    chunk-ready-topic: external-api.urgent.snapshot.chunk-ready
    consumer-group-id: external-api-urgent-processor
```

- [ ] **Step 2: Create UrgentCharacterRequestConsumer**

Create `module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt`:

```kotlin
package maple.externalapi.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.snapshot.event.SnapshotChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["external-api.urgent.enabled"], havingValue = "true", matchIfMissing = false)
class UrgentCharacterRequestConsumer(
    private val clientPort: ExternalApiClientPort,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${external-api.urgent.not-found-topic}") private val notFoundTopic: String,
    @Value("\${external-api.urgent.chunk-ready-topic}") private val urgentChunkReadyTopic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${external-api.urgent.request-topic}"],
        groupId = "\${external-api.urgent.consumer-group-id}"
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val request = objectMapper.readValue(message, UrgentCharacterRequest::class.java)
        log.info("Processing urgent request: userIgn={}", request.userIgn)

        processUrgentCharacter(request)

        acknowledgment.acknowledge()
    }

    private fun processUrgentCharacter(request: UrgentCharacterRequest) {
        // Step 1: OCID lookup (try-catch for Nexon 400 → not-found is domain logic, not error handling)
        val ocidData = try {
            clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, request.userIgn)
                .join()
        } catch (ex: Exception) {
            val cause = ex.cause ?: ex
            if (isNexonCharacterNotFound(cause)) {
                publishNotFound(request.userIgn)
                return
            }
            throw ex
        }

        val ocid = objectMapper.readTree(ocidData).get("ocid").asText()

        // Step 2: Store OCID artifact (for future scheduler inclusion via OcidCacheProvider)
        artifactStore.store(ExternalApiEndpoint.OCID_LOOKUP, request.userIgn, ocidData)
        log.info("OCID resolved and stored: userIgn={}", request.userIgn)

        // Step 3: Fetch basic + equipment in parallel
        val basicFuture = clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.CHARACTER_BASIC, ocid)
        val equipmentFuture = clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.ITEM_EQUIPMENT, ocid)

        val basicData = basicFuture.join()
        val equipmentData = equipmentFuture.join()

        // Step 4: Create and publish urgent chunks using EXISTING snapshot pipeline infrastructure
        val runId = "urgent-${UUID.randomUUID()}"

        publishUrgentChunk(runId, ExternalApiEndpoint.CHARACTER_BASIC, request.userIgn, basicData)
        publishUrgentChunk(runId, ExternalApiEndpoint.ITEM_EQUIPMENT, request.userIgn, equipmentData)

        log.info("Urgent pipeline data fetch complete: userIgn={}, runId={}", request.userIgn, runId)
    }

    private fun publishUrgentChunk(
        runId: String,
        endpoint: ExternalApiEndpoint,
        userIgn: String,
        data: ByteArray
    ) {
        // REUSE existing snapshot pipeline's chunk creation + storage infrastructure.
        // The implementer must read the existing snapshot chunk writer to understand:
        // 1. How to create a single-record gzip+jsonl chunk
        // 2. How to upload to storage (local filesystem / Azure Blob via artifact store)
        // 3. The correct objectKey format for downstream calculator/synchronizer consumers
        //
        // Key classes to reference:
        //   - SnapshotChunkEventPublisher (for event creation)
        //   - Existing chunk writer in snapshot pipeline (for gzip+jsonl + storage)
        //
        // The event MUST match SnapshotChunkReadyEvent schema exactly
        // so that calculator/synchronizer consumers can process it without modification.

        val event = SnapshotChunkReadyEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            endpoint = endpoint.storageSubDir(),
            chunkId = "${endpoint.storageSubDir()}-$userIgn",
            objectKey = objectKey,  // from chunk writer
            recordCount = 1,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedBytes,
            createdAt = Instant.now()
        )

        val eventJson = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(urgentChunkReadyTopic, "${event.runId}:${event.endpoint}:${event.chunkId}", eventJson).get()
        log.info("Published urgent chunk: endpoint={}, userIgn={}", event.endpoint, userIgn)
    }

    private fun publishNotFound(userIgn: String) {
        val event = mapOf(
            "userIgn" to userIgn,
            "reason" to "OPENAPI00004",
            "occurredAt" to Instant.now().toString()
        )
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(notFoundTopic, userIgn, json)
        log.info("Published character-not-found event: userIgn={}", userIgn)
    }

    private fun isNexonCharacterNotFound(ex: Throwable): Boolean {
        val message = ex.message ?: return false
        return message.contains("OPENAPI00004") ||
            (ex is org.springframework.web.reactive.function.client.WebClientResponseException && ex.statusCode.value() == 400)
    }
}

// DTO (duplicated in rest-controller — same JSON schema, separate class)
data class UrgentCharacterRequest(
    val eventId: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: java.time.Instant
)
```

Notes:
- `@ConditionalOnProperty` ensures this consumer only activates when urgent is enabled
- try-catch for OCID 400 → not-found is domain logic (consistent with `OcidLookupPhase` pattern)
- `publishUrgentChunk` delegates to existing snapshot chunk infrastructure (not custom gzip/file I/O)
- The implementer must read the existing chunk writer in the snapshot pipeline and reuse it for single-record chunk creation

- [ ] **Step 3: Write test for UrgentCharacterRequestConsumer**

Create `module-external-api/src/test/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumerTest.kt`:

```kotlin
package maple.externalapi.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class UrgentCharacterRequestConsumerTest {

    private val clientPort: ExternalApiClientPort = mock()
    private val artifactStore: ExternalApiArtifactStorePort = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val kafkaTemplate: KafkaTemplate<String, String> = mock()
    private val acknowledgment: Acknowledgment = mock()

    private val consumer = UrgentCharacterRequestConsumer(
        clientPort, artifactStore, objectMapper, kafkaTemplate,
        "urgent-character-not-found", "external-api.urgent.snapshot.chunk-ready", "/tmp/test-data"
    )

    @Test
    fun `consume resolves OCID and fetches data`() {
        val request = UrgentCharacterRequest(UUID.randomUUID().toString(), "testChar", 1, Instant.now())
        val message = objectMapper.writeValueAsString(request)

        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "testChar"))
            .thenReturn(CompletableFuture.completedFuture("""{"ocid":"abc123"}""".toByteArray()))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.CHARACTER_BASIC, "abc123"))
            .thenReturn(CompletableFuture.completedFuture("""{"basic":"data"}""".toByteArray()))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.ITEM_EQUIPMENT, "abc123"))
            .thenReturn(CompletableFuture.completedFuture("""{"equipment":"data"}""".toByteArray()))
        whenever(kafkaTemplate.send(any(), any(), any())).thenReturn(
            CompletableFuture.completedFuture(mock())
        )

        consumer.consume(message, acknowledgment)

        verify(artifactStore).store(eq(ExternalApiEndpoint.OCID_LOOKUP), eq("testChar"), any())
        verify(kafkaTemplate, times(2)).send(eq("external-api.urgent.snapshot.chunk-ready"), any(), any())
        verify(acknowledgment).acknowledge()
    }

    @Test
    fun `consume publishes not-found on Nexon 400`() {
        val request = UrgentCharacterRequest(UUID.randomUUID().toString(), "unknownChar", 1, Instant.now())
        val message = objectMapper.writeValueAsString(request)

        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "unknownChar"))
            .thenReturn(CompletableFuture.failedFuture(
                RuntimeException("400 Bad Request: OPENAPI00004")
            ))
        whenever(kafkaTemplate.send(any(), any(), any())).thenReturn(
            CompletableFuture.completedFuture(mock())
        )

        consumer.consume(message, acknowledgment)

        verify(kafkaTemplate).send(eq("urgent-character-not-found"), eq("unknownChar"), any())
        verify(artifactStore, never()).store(any(), any(), any())
        verify(acknowledgment).acknowledge()
    }
}
```

- [ ] **Step 4: Run compile + test**

```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava :module-external-api:test --continue
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-external-api/
git commit -m "feat(external-api): add urgent character request consumer with OCID + data fetch"
```

---

## Task 5: module-calculator — Urgent Listener

**Files:**
- Modify: `module-calculator/src/main/resources/application.yml`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt`

- [ ] **Step 1: Add urgent topic config to application.yml**

Read `module-calculator/src/main/resources/application.yml`. Add after the existing `calculator.kafka` block:

```yaml
calculator:
  kafka:
    # ... existing ...
    urgent-snapshot-chunk-ready-topic: external-api.urgent.snapshot.chunk-ready
    urgent-consumer-group-id: calculator-urgent-chunk-processor
```

- [ ] **Step 2: Add urgent listener method to KafkaSnapshotChunkReadyConsumer**

Read `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt`. Add a second `@KafkaListener` method:

```kotlin
@KafkaListener(
    topics = ["\${calculator.kafka.urgent-snapshot-chunk-ready-topic}"],
    groupId = "\${calculator.kafka.urgent-consumer-group-id}"
)
fun consumeUrgent(message: String, acknowledgment: Acknowledgment) {
    val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
    log.info("Received URGENT chunk ready event: runId={}, endpoint={}, chunkId={}, recordCount={}",
        event.runId, event.endpoint, event.chunkId, event.recordCount)
    runBlocking { coordinator.handle(event) }
    acknowledgment.acknowledge()
}
```

**Important:** `coordinator.handle(event)` internally uses `KafkaResultEventPublisher` which publishes to the NORMAL `calculator.result.chunk-ready` topic. This is intentional — the synchronizer's existing consumers pick up urgent results from the normal topic. No separate urgent result topic needed. See "Design Considerations" at the end of this plan.

- [ ] **Step 3: Run compile + test**

```bash
./gradlew :module-calculator:compileKotlin :module-calculator:compileJava --continue
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-calculator/
git commit -m "feat(calculator): add urgent snapshot chunk consumer"
```

---

## Task 6: module-synchronizer — Urgent Basic Listener + OCID Upsert

**Files:**
- Modify: `module-synchronizer/src/main/resources/application.yml`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt`

- [ ] **Step 1: Add urgent topic config to application.yml**

Read `module-synchronizer/src/main/resources/application.yml`. Add after existing `synchronizer.kafka` block:

```yaml
synchronizer:
  kafka:
    # ... existing ...
    urgent-basic-chunk-ready-topic: external-api.urgent.snapshot.chunk-ready
    urgent-basic-consumer-group-id: synchronizer-urgent-basic-chunk-consumer
```

Note: No separate urgent result topic config needed. Calculator publishes urgent results to the NORMAL `calculator.result.chunk-ready` topic. The existing `KafkaResultChunkConsumer` handles them without modification.

- [ ] **Step 2: Add urgent basic listener + OCID upsert to BasicSnapshotChunkConsumer**

Read `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt`. The urgent basic listener processes CHARACTER_BASIC chunks AND upserts OCID into `game_character` — this is the right place because character-basic data contains both `userIgn` and `ocid` in the same records.

Add `JdbcTemplate` as an additional constructor parameter if not already present.

Add the urgent listener method:

```kotlin
@KafkaListener(
    topics = ["\${synchronizer.kafka.urgent-basic-chunk-ready-topic}"],
    groupId = "\${synchronizer.kafka.urgent-basic-consumer-group-id}"
)
fun consumeUrgentBasic(message: String, acknowledgment: Acknowledgment) {
    val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
    log.info("Received URGENT basic chunk: runId={}, chunkId={}, objectKey={}", event.runId, event.chunkId, event.objectKey)

    // Idempotency check
    if (chunkStatusRepository.isAlreadySuccess(event.runId, event.chunkId)) {
        acknowledgment.acknowledge()
        return
    }
    chunkStatusRepository.claimChunk(event.runId, event.chunkId, event.objectKey)

    if (!processingPermit.tryAcquire()) {
        return  // backpressure — no ack, redeliver later
    }

    CompletableFuture.runAsync({
        logicExecutor.executeWithFinally(
            {
                logicExecutor.executeOrCatch(
                    {
                        val records = fileReader.read(event.objectKey)
                        repository.bulkUpsert(event.runId, event.chunkId, records)
                        chunkStatusRepository.markSuccess(event.runId, event.chunkId)

                        // OCID upsert: character-basic records contain both userIgn and ocid
                        upsertOcidFromBasicRecords(records)

                        log.info("Urgent basic chunk processed: runId={}, chunkId={}, records={}", event.runId, event.chunkId, records.size)
                        acknowledgment.acknowledge()
                    },
                    { ex ->
                        chunkStatusRepository.markFailed(event.runId, event.chunkId, ex.message ?: "unknown")
                        log.error("Urgent basic chunk failed: runId={}, chunkId={}", event.runId, event.chunkId, ex)
                    },
                    TaskContext.of("BasicSync", "UrgentChunkProcess", event.chunkId)
                )
            },
            {
                processingPermit.release()
            },
            TaskContext.of("BasicSync", "UrgentChunkLifecycle", event.chunkId)
        )
    }, vtExecutor)
}

private fun upsertOcidFromBasicRecords(records: List<CharacterBasicRecord>) {
    records.forEach { record ->
        jdbc.update(
            """INSERT INTO game_character (user_ign, ocid, created_at, updated_at)
               VALUES (?, ?, NOW(), NOW())
               ON CONFLICT (user_ign) DO UPDATE SET ocid = EXCLUDED.ocid, updated_at = NOW()""",
            record.userIgn, record.ocid
        )
        log.info("Upserted OCID to game_character: userIgn={}", record.userIgn)
    }
}
```

**Why here, not in KafkaResultChunkConsumer:** The CHARACTER_BASIC snapshot records already contain both `userIgn` and `ocid` fields — no need to read result chunks or parse calculator output. The urgent basic consumer has direct access to the source data. This avoids the `runId.startsWith("urgent-")` hack entirely.

- [ ] **Step 4: Run compile + test**

```bash
./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava --continue
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-synchronizer/
git commit -m "feat(synchronizer): add urgent basic chunk consumer with OCID upsert"
```

---

## Task 7: Cross-Module Compile + Full Test

- [ ] **Step 1: Full compile**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL across all modules

- [ ] **Step 2: Full test suite**

```bash
./gradlew test
```

Expected: All tests pass

- [ ] **Step 3: Commit if any fixes were needed**

---

## Task 8: Server Runtime Verification

Per `.claude/rules/workflow-rules.md`, runtime verification is required before commit/push.

- [ ] **Step 1: Start Kafka locally (if not running)**

```bash
# Check if Kafka is running
docker ps | grep kafka || docker-compose -f docker-compose.yml up -d kafka
```

- [ ] **Step 2: Start rest-controller**

```bash
set -a && source .env && set +a
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew :module-rest-controller:bootRun
```

Wait for health check: `curl -s http://localhost:8084/actuator/health`

- [ ] **Step 3: Test V6 miss → 202 + urgent trigger**

```bash
curl -s -w "\nHTTP %{http_code}" "http://localhost:8084/api/v6/characters/UnknownChar123/expectation"
```

Expected: HTTP 202 (data not found, urgent trigger published)

- [ ] **Step 4: Check Kafka topic for urgent message**

```bash
# Verify message was published to urgent-character-request topic
kafka-console-consumer --bootstrap-server localhost:9092 --topic urgent-character-request --from-beginning --max-messages 1
```

Expected: JSON with `userIgn: UnknownChar123`

- [ ] **Step 5: Test negative cache ( Nexon 400 → 404 )**

After the urgent pipeline processes the non-existent character:

```bash
curl -s -w "\nHTTP %{http_code}" "http://localhost:8084/api/v6/characters/UnknownChar123/expectation"
```

Expected: HTTP 404 (negative cache hit)

- [ ] **Step 6: Check logs for errors**

```bash
grep "ERROR" module-rest-controller/logs/*.log 2>/dev/null | tail -10
```

Expected: No ERROR logs related to urgent pipeline

---

## Task 9: PR Creation

- [ ] **Step 1: Push branch**

```bash
git push -u origin feature/v6-urgent-kafka-pipeline
```

- [ ] **Step 2: Create PR targeting develop**

```bash
gh pr create --base develop --title "feat: V6 urgent Kafka pipeline for cache-miss character data" --body "$(cat <<'EOF'
## Summary

- V6 read endpoint에서 Redis + DB miss 시 urgent Kafka pipeline 트리거
- module-rest-controller → module-external-api → module-calculator → module-synchronizer 흐름
- Nexon 400 (존재하지 않는 캐릭터) → Redis negative cache → 404 응답
- OCID artifact 저장 → 향후 scheduler batch pipeline 포함

## Architecture

```
V6 miss → 202 → Kafka "urgent-character-request"
                         ↓
external-api: OCID lookup + basic + equipment → urgent chunk
                         ↓
calculator: urgent chunk processing
                         ↓
synchronizer: read model upsert + game_character OCID save
                         ↓
next V6 request → cache hit → 200
```

## Test plan

- [ ] `./gradlew compileKotlin compileJava --continue` 통과
- [ ] `./gradlew test` 전체 통과
- [ ] 서버 런타임: V6 miss → 202 응답 확인
- [ ] Kafka topic에 urgent 메시지 publish 확인
- [ ] Nexon 400 → 404 + negative cache 확인
- [ ] 기존 V6 read hit 경로 정상 동작 확인 (regression)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Topic Summary

| Topic | Publisher | Consumer |
|-------|-----------|----------|
| `urgent-character-request` | rest-controller | external-api |
| `urgent-character-not-found` | external-api | rest-controller |
| `external-api.urgent.snapshot.chunk-ready` | external-api | calculator (urgent group), synchronizer/basic (urgent group) |
| `calculator.result.chunk-ready` (existing, shared) | calculator | synchronizer/result (existing, shared) |

## Design Considerations

### Why calculator → synchronizer uses the NORMAL result topic

Calculator의 `coordinator.handle(event)`은 내부적으로 `KafkaResultEventPublisher`를 사용하여 `calculator.result.chunk-ready`에 publish. Urgent 결과를 별도 토픽으로 보내려면 coordinator 수정 필요 (기존 코드 변경 범위 확대).

**선택한 접근:** calculator urgent consumer는 같은 `coordinator.handle()`을 호출 → 결과는 normal topic으로 → synchronizer의 기존 consumer가 처리. Urgent 결과가 batch 결과와 섞이지만, calculator/synchronizer는 single-user chunk 처리가 빠르므로 실질적인 latency 차이 없음.

**Urgency가 의미 있는 구간:** rest-controller → external-api (OCID + API fetch). 이 구간이 실제 bottleneck. Calculator/synchronizer는 이미 충분히 빠름.

### Alternative: fully separated urgent topics

만약 calculator/synchronizer에서도 priority 분리가 필요하다면:
1. `KafkaResultEventPublisher`에 topic을 parameter로 받는 overloading 추가
2. Urgent coordinator bean 생성 (urgent result topic 주입)
3. Synchronizer에 separate urgent result consumer 추가

하지만 이건 기존 coordinator 코드 수정이 필요하고, single-user chunk 처리에는 over-engineering.

## Configuration Checklist

All thread pools, batch sizes, TTLs, topic names externalized to YAML. Feature flag `expectation.v6.urgent.enabled` controls activation.
