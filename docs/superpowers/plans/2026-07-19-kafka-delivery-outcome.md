# Kafka Delivery Outcome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every active ETL Kafka record one explicit durable outcome and one technical retry owner, with ACK only after durable work and required outbound sends, bounded pause/resume backpressure, DLT-before-commit recovery, and safe DLT topology provisioning.

**Architecture:** Create `module-pipeline-messaging`. Workloads expose `PipelineSubscription` handlers returning `CompletionStage<DeliveryOutcome>` and never access Spring Kafka `Acknowledgment`. A synchronous void listener hands records to one serial `PartitionLane` per assigned topic-partition; the lane pauses that partition, invokes the async handler off the consumer thread, and ACKs monotonically before advancing/resuming. The messaging module owns containers, lanes, retry scheduling, DLT serialization/sanitization, and metrics. Business lease/attempt state remains in synchronizer.

**Tech Stack:** Kotlin/JDK 21, Gradle Groovy DSL, Spring Kafka 3.3.8, Apache Kafka client, Jackson, Micrometer, JUnit 5, AssertJ, Mockito-Kotlin, Awaitility.

**Spec:** `docs/superpowers/specs/2026-07-19-kafka-delivery-outcome-design.md`

**Depends on:** `2026-07-19-pipeline-artifact-lifecycle.md` Task 7 for `CleanupInboxStore`.

## Global Constraints

- Preserve topic names, group IDs, keys, partitions, event JSON, initial attempt plus three technical retries, and fixed one-second technical backoff.
- Workload production source must contain no `Acknowledgment`, `acknowledge()`, or `nack()` after its subscription migrates. Only `module-pipeline-messaging` may touch the acknowledgment object.
- Configure migrated containers with `AckMode.MANUAL_IMMEDIATE` and `asyncAcks=false`. The listener itself is void; only `PartitionLane` invokes `Acknowledgment` off the consumer thread after a safe terminal action. Do not use `nack()`.
- Capacity/lease-not-due backpressure pauses only the affected partition, does not ACK, and does not consume the technical retry budget.
- DLT publication must finish successfully before source commit. Set send-result failure propagation and an explicit timeout; a DLT failure leaves the record uncommitted.
- Secret-bearing auth/BYOK records must never copy their raw payload or API key into DLT data, headers, exceptions, logs, metrics, or traces.
- The messaging adapter must never log or tag a `Retryable` throwable message, cause chain, stack trace, payload, or key. `DeliveryMetrics` maps failures to the fixed `TIMEOUT|IO|DB|KAFKA|OTHER` buckets and uses only static subscription IDs, bounded topics, outcome, and normalized reason as tags; workload code owns any additional sanitized domain logging.
- Do not add Testcontainers or integration-test source sets. Validate broker behavior with deterministic component tests and the existing local Docker Kafka runtime.
- Do not use `Thread.sleep`, coroutine `delay`, blocking `join`/`get`, or `runBlocking`. Use an owned `ScheduledExecutorService`, completion stages, and Awaitility.
- Do not change synchronizer transaction/lease semantics. The ordering change is durable DB work → required outbound send completion → conditional success state → ACK.
- Preserve before/after consumer throughput, lag, duplicate count, DLT count, pause duration, and retry count in `docs/05_Reports/2026-07-19-kafka-delivery-outcome-evidence.md`.

---

## Task 1: Capture delivery baselines, write ADR-746, and scaffold messaging

**Files:**

- Create: `docs/01_ADR/ADR-746-kafka-delivery-outcomes.md`
- Create: `docs/05_Reports/2026-07-19-kafka-delivery-outcome-evidence.md`
- Modify: `settings.gradle`
- Create: `module-pipeline-messaging/build.gradle`

**Interfaces:**

- Consumes: current listener inventory, retry configuration, broker topic metadata, and pipeline metrics.
- Produces: a recorded behavior baseline and an independently compilable messaging module.

- [ ] **Step 1: Record every current listener and broker boundary**

Run:

```bash
rg -n '@KafkaListener|Acknowledgment|acknowledge\(|nack\(' module-calculator/src/main module-synchronizer/src/main module-external-api/src/main module-cleanup/src/main > /tmp/kafka-delivery-listeners-before.txt
rg -n 'consumer-max-retries|consumer-retry-backoff|FixedBackOff|\.DLT|consumer-group|group-id|topic:' module-calculator/src/main/resources module-synchronizer/src/main/resources module-external-api/src/main/resources module-cleanup/src/main/resources module-infra/src/main > /tmp/kafka-delivery-policy-before.txt
```

Use this fixed non-secret measurement protocol and record it before changing a listener:

- local Docker Kafka, the current four service JARs, unchanged partition counts/concurrency, and the exact JVM options/commit/CPU fingerprint;
- two generated UTF-8 files: 100 warmup plus 1,000 measured records, each line keyed `delivery-probe-{warmup|measured}-%05d` with value `{"probe":"kafka-delivery","sequence":N}`; this is valid JSON but invalid for `SnapshotChunkReadyEvent`, contains no credential, and is sent to `external-api.snapshot.chunk-ready` with `kafka-console-producer --property parse.key=true --property key.separator='|'`;
- SHA-256, byte count, and record count for both files; retain the files under `/tmp` until the after measurement finishes so the exact bytes are replayed;
- a 60-second warmup followed by a five-minute measurement sampled every 30 seconds from calculator/synchronizer Prometheus endpoints and `kafka-consumer-groups --describe` for their normal groups;
- deltas for consumed/success/retry/pause/DLT/duplicate counters, source/DLT end offsets, group lag, JVM CPU/heap, and executor/pool pressure. Use counter deltas over the window, never instantaneous counter values.

Send one separate malformed auth JSON record containing no API key only for DLT-envelope characterization; exclude it from throughput. Do not print or store message bodies from the auth request topic. Record any current nested calculator retry amplification separately from the broker record count.

Expected: the report lists calculator normal/urgent, synchronizer basic/urgent/result/OCID, external auth/urgent, and cleanup inbox paths; nested calculator retry is explicitly recorded as up to sixteen handler attempts.

- [ ] **Step 2: Create ADR-746**

Write `docs/01_ADR/ADR-746-kafka-delivery-outcomes.md` with the repository's five ADR sections and this decision text:

```markdown
Create module-pipeline-messaging. Workloads return a closed DeliveryOutcome and expose PipelineSubscription beans. The messaging module alone owns per-partition serial lanes, manual-immediate ACK, one initial plus three fixed-backoff retries, partition pause/resume, DLT publication, secret sanitization, and delivery metrics. Synchronizer lease attempts remain business state. No distributed transaction or wire-schema change is introduced.
```

- [ ] **Step 3: Add the Gradle module**

Add `include 'module-pipeline-messaging'` to `settings.gradle` and create:

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
    implementation(libs.spring.kafka)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
}

tasks.named('jar') {
    enabled = true
    archiveClassifier = 'plain'
}
```

- [ ] **Step 4: Verify and commit the scaffold**

Run: `./gradlew :module-pipeline-messaging:compileKotlin :module-pipeline-messaging:compileJava --continue`

Expected: `BUILD SUCCESSFUL`; `module-pipeline-messaging` has no dependency on `module-infra` or any executable module.

```bash
git add settings.gradle module-pipeline-messaging/build.gradle docs/01_ADR/ADR-746-kafka-delivery-outcomes.md docs/05_Reports/2026-07-19-kafka-delivery-outcome-evidence.md
git commit -m "build: add pipeline messaging module"
```

---

## Task 2: Define the closed delivery contract and subscription boundary

**Files:**

- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/contract/DeliveryContext.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/contract/DeliveryOutcome.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/contract/DeliveryHandler.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/contract/PipelineSubscription.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/contract/CompletionFailures.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/contract/SafeDeliveryException.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/DltRecordSanitizer.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/contract/DeliveryContractTest.kt`

**Interfaces:**

- Consumes: raw record value and immutable Kafka metadata.
- Produces: one of five outcomes and a completion stage; it exposes no Spring Kafka transport type to workloads.

- [ ] **Step 1: Write failing exhaustiveness/value tests**

Test metadata mapping, positive backpressure duration validation, immutable topic lists, and that handlers return a `CompletionStage<DeliveryOutcome>`.

Run: `./gradlew :module-pipeline-messaging:test --tests '*DeliveryContractTest'`

Expected: compilation fails because contract types do not exist.

- [ ] **Step 2: Implement the contract**

```kotlin
package maple.pipeline.messaging.contract

import java.time.Duration
import java.time.Instant

data class DeliveryContext(
    val listenerId: String,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Instant,
    val key: String?,
    val deliveryAttempt: Int,
)

sealed interface DeliveryOutcome {
    data object Success : DeliveryOutcome
    data class TerminalDrop(val reason: String) : DeliveryOutcome {
        init { require(reason.matches(Regex("[A-Z0-9_]{1,64}"))) }
    }
    data class InvalidMessage(val reason: String) : DeliveryOutcome {
        init { require(reason.matches(Regex("[A-Z0-9_]{1,64}"))) }
    }
    data class Retryable(val cause: Throwable) : DeliveryOutcome
    data class Backpressure(val duration: Duration) : DeliveryOutcome {
        init {
            require(!duration.isNegative && !duration.isZero) {
                "backpressure duration must be positive"
            }
        }
    }
}
```

```kotlin
fun interface DeliveryHandler {
    fun handle(payload: String, context: DeliveryContext): CompletionStage<DeliveryOutcome>
}

class PipelineSubscription(
    val id: String,
    topics: Collection<String>,
    val groupId: String,
    val concurrency: Int = 1,
    val handler: DeliveryHandler,
    val dltSanitizer: DltRecordSanitizer,
) {
    val topics: List<String> = java.util.List.copyOf(topics)

    init {
        require(id.isNotBlank() && groupId.isNotBlank() && topics.isNotEmpty())
        require(concurrency > 0)
    }
}
```

`DltRecordSanitizer.sanitize(key, value, context)` returns `DltPayload(key: String?, value: String, extraHeaders: Map<String, ByteArray>)`. Its constructor defensively copies the map and every byte array. `PassThrough` preserves key/value only for explicitly non-secret subscriptions; every `PipelineSubscription` must choose a sanitizer explicitly, and a secret-bearing subscription must replace or null the key as well as sanitize the value. Extra-header names are validated against a fixed `x-pipeline-safe-*` prefix and values have a 1 KiB cap.

Unwrap only completion wrappers at the contract boundary:

```kotlin
object CompletionFailures {
    fun unwrap(failure: Throwable): Throwable = when (failure) {
        is CompletionException -> failure.cause?.let(::unwrap) ?: failure
        is ExecutionException -> failure.cause?.let(::unwrap) ?: failure
        else -> failure
    }
}
```

Add nested `CompletionException`/`ExecutionException` cases to `DeliveryContractTest`; the final domain/transport cause object must be returned unchanged.

`SafeDeliveryException` has a bounded constant message derived only from the validated outcome reason and attempt, accepts no cause, and overrides no message with a source exception. Exhausted retry uses `RETRY_EXHAUSTED`; it never copies the original throwable's message, stack, or suppressed exceptions into the DLT path.

- [ ] **Step 3: Verify and commit the contract**

Run: `./gradlew :module-pipeline-messaging:test --tests '*DeliveryContractTest'`

Expected: `BUILD SUCCESSFUL`.

```bash
git add module-pipeline-messaging
git commit -m "feat: define Kafka delivery outcomes"
```

---

## Task 3: Implement partition lanes, one retry owner, and DLT-before-commit

**Files:**

- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/policy/DeliveryRetryPolicy.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/KafkaDeliveryAdapter.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/PartitionControl.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/PartitionLane.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/PartitionLaneRegistry.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/PartitionOwnership.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/PipelineKafkaEndpointRegistry.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/PipelineDeliveryExecutors.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/KafkaDltPublisher.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/DltRecordFactory.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/SafeDeadLetterPublishingRecoverer.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/config/PipelineKafkaConsumerConfiguration.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/metrics/DeliveryMetrics.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/adapter/KafkaDeliveryAdapterTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/adapter/PartitionLaneTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/adapter/PartitionLaneRegistryTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/adapter/PipelineDeliveryExecutorsTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/config/PipelineKafkaConsumerConfigurationTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/config/AsyncAcksScopeCharacterizationTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/dlt/DltRecordFactoryTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/dlt/SafeDeadLetterPublishingRecovererTest.kt`

**Interfaces:**

- Consumes: `PipelineSubscription`, `ConsumerRecord<String,String>`, container-local acknowledgment, rebalance generation, and a per-partition control handle.
- Produces: one in-flight delivery per assigned partition, monotonic commit only for success/terminal/DLT-success, and scheduled re-attempt or targeted pause for every other result.

- [ ] **Step 1: Write the full outcome/action matrix as failing tests**

The component test matrix must assert:

```text
Success                         acknowledge once
TerminalDrop                   metric then acknowledge once
InvalidMessage + DLT success   DLT completion then acknowledge once
InvalidMessage + DLT failure   no acknowledge, pause, DLT-only retry
Retryable attempts 1..3        pause partition, schedule at 1 second, no acknowledge
Retryable attempt 4            DLT completion then acknowledge/resume
Backpressure                   pause partition, schedule resume/reinvoke, attempt unchanged
same-partition offset N+1      handler is not invoked until N is ACK-eligible and its ACK is queued
different-partition completion commits/resumes independently
revoked partition completion  no ACK/resume; record replays on the new owner
```

Use a fake scheduler/clock and Awaitility; do not wait for real seconds.

Run: `./gradlew :module-pipeline-messaging:test --tests '*KafkaDeliveryAdapterTest'`

Expected: compilation fails because adapter types do not exist.

- [ ] **Step 2: Implement the fixed technical policy**

```kotlin
data class DeliveryRetryPolicy(
    val maxRetries: Int = 3,
    val backoff: Duration = Duration.ofSeconds(1),
) {
    init {
        require(maxRetries == 3) { "initial migration preserves exactly three retries" }
        require(backoff == Duration.ofSeconds(1)) { "initial migration preserves one-second backoff" }
    }
}
```

`PartitionLaneRegistry` creates a generation-scoped lane for each `(listenerId, topic-partition)`. The void listener offers its record/acknowledgment to that lane and requests `pausePartition` before dispatch. Records from the same already-polled batch may still reach the listener, so the lane queues them in offset order; the expected high-water bound is the configured `max.poll.records`. Never drop on a violated bound: retain the record, degrade health/increment a static invariant counter, and keep the partition paused. It never invokes offset N+1 before N reaches a safe terminal action and queues its ACK. A lane resumes only after its queue is empty. Other assigned partition lanes continue independently.

`KafkaDeliveryAdapter.deliver` receives the lane's record plus immutable `PartitionOwnership`, invokes the handler with `thenComposeAsync` on `pipelineDeliveryExecutor` even when the handler returns an already-completed stage, and returns a stage that yields `Commit` only after a safe terminal outcome. This guarantees `MANUAL_IMMEDIATE` acknowledgment is always made off the consumer thread and wakes the consumer for commit processing. A `Retryable` schedules the same handler until four total attempts. A `Backpressure` reinvokes without incrementing the attempt. A DLT send failure leaves the adapter stage pending, degrades health, increments the failure counter, and schedules a DLT-only retry after one second. That retry reuses the already-sanitized record, never invokes the workload handler, and continues only while ownership remains current. `PartitionLane` is the sole acknowledgment caller: on `Commit` plus current ownership it calls `acknowledge()`, then advances; stale/revoked completion never ACKs or resumes. “Commit” in this internal action means safe ACK eligibility; the actual broker commit remains Spring's consumer-thread operation.

`DeliveryMetrics` receives only subscription metadata, closed outcome/reason, attempt, duration, and a `FailureCategory` produced by a fixed classifier (`TIMEOUT`, `IO`, `DB`, `KAFKA`, `OTHER`). It never receives a payload/key or renders a throwable. Adapter logs use the same bounded fields and never pass the original throwable as a logging argument; tests capture logs and registry meters to prove a sentinel throwable message is absent.

On rebalance revoke, increment the lane generation, stop new dispatch/retry scheduling for that ownership, clear queued transport envelopes without ACK, and leave in-flight durable work to finish best-effort. Its stale completion is discarded, so Kafka replays from the committed offset under the new owner; workload idempotency remains required. Assignment creates a fresh generation. Tests cover revoke during handler, required send, and DLT-only retry.

`PipelineDeliveryExecutors` owns exactly three resources: a single named scheduled executor for retry timers, a virtual-thread-per-task `pipelineDeliveryExecutor` that invokes handlers off the consumer thread, and a separate virtual-thread-per-task executor for blocking `DeadLetterPublishingRecoverer.accept` calls. Its close path stops new work, awaits ten seconds, restores interruption, forces unfinished tasks, and records a static-tag forced-shutdown counter. The adapter never uses the common pool.

- [ ] **Step 3: Configure a migrated-only container factory**

The bean must be named `pipelineKafkaListenerContainerFactory` and include:

```kotlin
factory.consumerFactory = consumerFactory
factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
factory.containerProperties.isAsyncAcks = false
```

Keep the existing `kafkaListenerContainerFactory` during per-listener migration. `PipelineKafkaEndpointRegistry` creates/owns containers from the migrated factory, preserves each subscription's group ID/concurrency and current `max.poll.records`, installs an `AcknowledgingConsumerAwareMessageListener` that returns `Unit`, and connects assignment/revocation callbacks to `PartitionLaneRegistry`. The listener never returns a `Future`/`CompletionStage`; workload asynchrony is behind the lane. `PartitionControl` wraps container `pausePartition`/`resumePartition` requests and is the only pause API. The registry receives all executors from `PipelineDeliveryExecutors`; it creates none.

Pin a source-level characterization test to Spring Kafka 3.3.8 semantics: `asyncAcks=true` sets `pausedForAsyncAcks` and pauses all partitions assigned to that child consumer while a poll has incomplete acknowledgments. This is the reason the design does not use async replies; deleting the characterization requires a new transport decision.

- [ ] **Step 4: Implement DLT send completion and secret replacement**

`DltRecordFactory` first applies the subscription sanitizer and creates a new `ConsumerRecord<String,String>` with the same topic/partition/offset/timestamp but only the sanitized key/value and validated safe headers. It never copies arbitrary input headers. `KafkaDltPublisher` is the adapter's only DLT facade. Its `publish` returns `CompletionStage<Void>` from `CompletableFuture.runAsync({ recoverer.accept(safeRecord, SafeDeliveryException(reason, attempt)) }, pipelineDltExecutor)`. The recoverer sends to `${sourceTopic}.DLT` on the same partition and includes bounded original metadata plus normalized reason/attempt. Because the recoverer is configured to wait for the send result and throw on failure, the returned stage completes only after broker success or completes exceptionally on send/timeout; the adapter acknowledges only the successful stage.

`SafeDeadLetterPublishingRecoverer` subclasses Spring Kafka's recoverer and overrides this verified 3.3.8 signature:

```kotlin
override fun createProducerRecord(
    record: ConsumerRecord<*, *>,
    topicPartition: TopicPartition,
    headers: Headers,
    keyBytes: ByteArray?,
    valueBytes: ByteArray?,
): ProducerRecord<Any, Any>
```

Call the superclass with the already-sanitized record to obtain normalized headers/destination, then enforce the exact header allowlist: Spring original topic/partition/offset/timestamp, exception class, normalized pipeline reason/attempt, and `x-pipeline-safe-*`. Configure:

```kotlin
recoverer.setFailIfSendResultIsError(true)
recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10))
recoverer.setVerifyPartition(true)
recoverer.setPartitionInfoTimeout(Duration.ofSeconds(10))
```

Do not copy API-key headers, arbitrary source headers, raw keys, or raw auth payload. Parse failure uses a null key plus only event ID when safely extractable, SHA-256, and byte length.

- [ ] **Step 5: Verify adapter/config/recoverer behavior**

Run:

```bash
./gradlew :module-pipeline-messaging:test --tests '*KafkaDeliveryAdapterTest' --tests '*PartitionLaneTest' --tests '*PartitionLaneRegistryTest' --tests '*PipelineDeliveryExecutorsTest' --tests '*PipelineKafkaConsumerConfigurationTest' --tests '*AsyncAcksScopeCharacterizationTest' --tests '*DltRecordFactoryTest' --tests '*SafeDeadLetterPublishingRecovererTest'
```

Expected: all tests pass; factory reports manual-immediate/non-async ACK, same-partition delivery is serial, other partitions progress, revoke prevents stale ACK, no branch calls `nack`, and a failed DLT future leaves acknowledgment count at zero.

- [ ] **Step 6: Commit transport ownership**

```bash
git add module-pipeline-messaging
git commit -m "feat: own Kafka ack retry and DLT flow"
```

---

## Task 4: Migrate calculator and remove its nested retry loop

**Files:**

- Modify: `module-calculator/build.gradle`
- Create: `module-calculator/src/main/kotlin/maple/calculator/consumer/CalculatorSnapshotSubscription.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/consumer/SnapshotDispatchService.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumerTest.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/consumer/CalculatorSnapshotSubscriptionTest.kt`

**Interfaces:**

- Consumes: normal/urgent `SnapshotChunkReadyEvent` payloads and calculator coordinator completion.
- Produces: `Success`, `InvalidMessage`, or `Retryable` with no local sleep/retry/ACK.

- [ ] **Step 1: Write failing one-attempt and durable-completion tests**

Assert one handler invocation per adapter attempt, no internal retry when the coordinator fails, result-artifact plus result-ready send completion before `Success`, and parse errors as `InvalidMessage`.

Run: `./gradlew :module-calculator:test --tests '*CalculatorSnapshotSubscriptionTest'`

Expected: compilation fails because the subscription does not exist.

- [ ] **Step 2: Replace the listener with two subscription beans**

`CalculatorSnapshotSubscription` exposes normal and urgent `PipelineSubscription` beans. Both parse through `SnapshotEventParser` and call a simplified dispatch handler:

```kotlin
fun dispatch(event: SnapshotChunkReadyEvent, label: String): CompletionStage<DeliveryOutcome> =
    coordinator.handleAsync(event)
        .thenApply<DeliveryOutcome> { DeliveryOutcome.Success }
        .exceptionally { DeliveryOutcome.Retryable(CompletionFailures.unwrap(it)) }
```

If the coordinator remains `suspend` during the first commit, bridge it once at the module boundary using the existing owned calculator scope; do not add `runBlocking`.

- [ ] **Step 3: Delete nested retry and transport access**

Remove `maxRetries`, `retryBackoffMs`, the `while` loop, `delay`, `Acknowledgment`, and both `@KafkaListener` methods. `SnapshotDispatchService` becomes one attempt and returns the completion stage/outcome. Keep existing log labels and bounded metric tags.

- [ ] **Step 4: Verify calculator behavior**

Run:

```bash
./gradlew :module-calculator:test --tests '*KafkaSnapshotChunkReadyConsumerTest' --tests '*CalculatorSnapshotSubscriptionTest' --tests '*CalculatorChunkProcessingCoordinatorTest'
```

Expected: all tests pass and the failure test observes one coordinator call per adapter attempt, not four internal calls.

- [ ] **Step 5: Commit calculator migration**

```bash
git add module-calculator
git commit -m "refactor: migrate calculator Kafka delivery"
```

---

## Task 5: Migrate synchronizer with publish-before-success-state ordering

**Files:**

- Modify: `module-synchronizer/build.gradle`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/event/KafkaChunkConsumedEventPublisher.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/SynchronizerSubscriptions.kt`
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt`
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt`
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/SynchronizerSubscriptionsTest.kt`

**Interfaces:**

- Consumes: basic/result/OCID payload plus current DB execution claim/lease state.
- Produces: a completion stage whose `Success` means durable DB work, consumed-event send, and conditional `markSucceeded` all completed.

- [ ] **Step 1: Add failing state/send ordering tests**

Use controllable futures and an ordered call log. Assert exactly:

```text
process -> publish invoked -> publish completed -> markSucceeded -> Success
```

Publish failure must skip `markSucceeded` and return `Retryable`. A lost `markSucceeded` race must not return `Success`. Permit release must occur for success, cancellation, processing failure, publish failure, and state-write failure. A future `nextRetryAt` and unavailable permit must return `Backpressure`, not `Retryable` or terminal ACK.

Run: `./gradlew :module-synchronizer:test --tests '*ChunkConsumerTemplateTest'`

Expected: at least the publish-before-state test fails against the current ordering.

- [ ] **Step 2: Make outbound publication consumable**

Change:

```kotlin
fun publish(event: ChunkConsumedEvent): CompletableFuture<Void>
```

Serialize first; return `kafkaTemplate.send(ProducerRecord(topic, key, serializedValue)).thenApply { null }`. Do not attach an `exceptionally`/`whenComplete` branch that converts failure to success.

- [ ] **Step 3: Rewrite `ChunkConsumerTemplate` around outcomes**

`ChunkConsumerRequest` no longer contains `Acknowledgment`, `TaskContext`, or lifecycle callbacks that perform required outbound work. Its core fields become:

```kotlin
val process: () -> Unit
val publishRequired: () -> CompletionStage<Void>
val onObservedSuccess: () -> Unit
val onObservedFailure: (Throwable) -> Unit
```

`submit` returns `CompletionStage<DeliveryOutcome>`. Claim/state/permit decisions map to closed outcomes. The success chain is:

```kotlin
val preparation = CompletableFuture.supplyAsync({ prepareClaim(request) }, request.executor)
return preparation
    .thenComposeAsync({ prepared ->
        when (prepared) {
            is ClaimPreparation.Immediate -> CompletableFuture.completedFuture(prepared.outcome)
            is ClaimPreparation.Claimed -> processClaimed(request, prepared.claim)
        }
    }, request.executor)
    .whenComplete { _, _ -> request.processingPermit.release() }
```

Acquire the processing permit before submitting `prepareClaim`; if acquisition or executor submission fails, release any acquired permit and return bounded `Backpressure`. `prepareClaim` performs insert/find/claim DB calls on the named workload executor, never on the Kafka consumer thread. `ClaimPreparation.Immediate` carries terminal/current/not-due outcomes; `ClaimPreparation.Claimed` carries the compare-and-set claim.

`processClaimed` runs `request.process` on that same executor thread through `runCatching`. A synchronous failure calls `persistClassifiedFailureAsync(request, claim, cause)`. Success invokes the required publish stage; its exceptional completion calls the same failure helper. Publish success uses `CompletableFuture.supplyAsync({ markSucceededOutcome(request, claim) }, request.executor)` so the final blocking repository call never runs on a Kafka producer callback thread. `markSucceededOutcome` returns `Success` only on a successful compare-and-set and otherwise returns `Retryable(IllegalStateException("success state write lost race"))`. `persistClassifiedFailureAsync` runs on `request.executor`, writes the existing retryable/terminal state exactly once, and returns its closed outcome. Preserve original unwrapped causes when classifying failures and do not use a generic executor exception translator.

- [ ] **Step 4: Expose workload subscriptions**

Replace basic/urgent/result/OCID `@KafkaListener` entry points with `PipelineSubscription` beans. Basic endpoint mismatch returns `TerminalDrop("ENDPOINT_MISMATCH")`; stale run returns the existing ADR-727 reason; unsupported schema returns `InvalidMessage`; permit/lease delay returns `Backpressure` with a bounded duration.

- [ ] **Step 5: Verify transactions, duplicates, and permit safety**

Run:

```bash
./gradlew :module-synchronizer:test --tests '*ChunkConsumerTemplateTest' --tests '*SynchronizerSubscriptionsTest' --tests '*BasicChunkIngestionServiceTest' --tests '*ChunkExecutionRepositoryTest'
```

Expected: all tests pass; duplicate/replay calls remain idempotent and no test observes success state before send completion.

- [ ] **Step 6: Commit synchronizer migration**

```bash
git add module-synchronizer
git commit -m "fix: complete synchronizer publish before ack"
```

---

## Task 6: Migrate external urgent/auth and cleanup inbox delivery

**Files:**

- Modify: `module-external-api/build.gradle`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/messaging/ExternalApiSubscriptions.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthRequestDltSanitizer.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumerTest.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/auth/AuthRequestDltSanitizerTest.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/messaging/ExternalApiSubscriptionsTest.kt`
- Modify: `module-cleanup/build.gradle`
- Create: `module-cleanup/src/main/kotlin/maple/cleanup/inbox/CleanupInboxSubscription.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/inbox/ConsumedChunkInbox.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/inbox/InboxProperties.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/controller/InboxCleanupResponse.kt`
- Modify: `module-cleanup/src/test/kotlin/maple/cleanup/inbox/ConsumedChunkInboxTest.kt`
- Modify: `module-cleanup/src/test/kotlin/maple/cleanup/controller/CleanupControllerTest.kt`

**Interfaces:**

- Consumes: urgent/auth request payloads and cleanup `ChunkConsumedEvent` payloads.
- Produces: typed outcomes with required future completion and secret-safe DLT material.

- [ ] **Step 1: Write failing external and secret tests**

Assert urgent semaphore exhaustion returns `Backpressure` with no ACK/drop; upload/send failure returns `Retryable`; auth response send must complete before `Success`; and the literal API key appears nowhere in sanitized value, headers, exception strings, or captured logs. For malformed auth JSON, only SHA-256/length and safe record metadata are retained.

Run:

```bash
./gradlew :module-external-api:test --tests '*ExternalApiSubscriptionsTest' --tests '*AuthRequestDltSanitizerTest'
```

Expected: tests fail because subscriptions and sanitizer do not exist.

- [ ] **Step 2: Convert urgent processing into a handler**

Keep the current fetch → artifact receipt → two downstream sends chain. Return `Success` only at the end. Release the semaphore with `whenComplete` on every branch. If acquisition fails, return a completed `Backpressure` outcome and let the adapter pause/reinvoke the record.

- [ ] **Step 3: Make auth publication awaitable without changing legacy classification yet**

`publishResponse` returns `CompletableFuture<Void>` for serialization plus Kafka send. The handler returns `Success` only after it completes. At this stage the legacy `NexonAuthClient` may still provide `Optional`; the Nexon plan replaces its collapsed failure taxonomy, but no response is ACKed before send completion here.

- [ ] **Step 4: Implement the auth DLT sanitizer**

For valid requests, emit JSON containing only `eventId`, masked/safe request identifiers approved by the event schema, source payload SHA-256, and byte length. Never emit `apiKey`, the raw value, or a value-derived prefix/suffix. For parse failure, emit only hash and length. Use constant metric tags.

- [ ] **Step 5: Convert cleanup to a subscription**

`ConsumedChunkInbox` becomes a transport-neutral handler that decodes the event, builds `CleanupInboxEntry` from `DeliveryContext` plus an injected `Clock`, and returns the stage from `CleanupInboxStore.putIfAbsent`. Map `Created` and `Replay` to `Success`, `IntegrityConflict` to `InvalidMessage("INBOX_EVENT_ID_CONFLICT")`, and an exceptionally completed storage stage to `Retryable(CompletionFailures.unwrap(failure))`. `CleanupInboxSubscription` exposes the subscription only when `cleanup-inbox.auto-start=true`. Delete `ConcurrentLinkedQueue`, local `pendingCount`, oldest-drop behavior, `drain()`, and all acknowledgment access; the handler never blocks for inbox persistence.

Switch `CleanupController.cleanupInbox` to the durable store in this same commit. Add `drainPageSize=100` and `maxDrainEntriesPerRequest=10000` to `InboxProperties`; preserve `maxPending` only as a durable-backlog alert threshold and keep `basePath` as legacy binding compatibility. Page forward by the last scanned key until empty or the per-request cap. For each entry, delete result/source keys with separate `runCatching` boundaries. Delete the inbox object only when every target delete succeeds or was already absent; otherwise retain it and continue to the next key. Return:

```kotlin
data class InboxCleanupResponse(
    val scanned: Int,
    val completed: Int,
    val retainedForRetry: Int,
    val deletedTargets: Int,
)
```

`deletedTargets` counts successful idempotent delete operations, including already-absent targets. The durable object, never a local retry list, is the retry source.

- [ ] **Step 6: Verify all three workloads**

Run:

```bash
./gradlew :module-external-api:test --tests '*ExternalApiSubscriptionsTest' --tests '*AuthRequestDltSanitizerTest' --tests '*UrgentCharacterRequestConsumerTest'
./gradlew :module-cleanup:test --tests '*ConsumedChunkInboxTest' --tests '*CleanupControllerTest'
```

Expected: all tests pass; urgent capacity is never silently dropped, auth send failure is retryable, cleanup storage failure never produces success, restart retains pending entries, and a partial target-delete failure leaves the inbox object for the next drain.

- [ ] **Step 7: Commit external/cleanup migration**

```bash
git add module-external-api module-cleanup
git commit -m "refactor: migrate external and cleanup delivery"
```

---

## Task 7: Verify DLT topology, remove legacy listener wiring, and close the slice

**Files:**

- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/DltTopologyStatus.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/DltTopologyHealthIndicator.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/DltTopologyResources.kt`
- Create: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/dlt/DltTopologyAction.kt`
- Modify: `module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/config/PipelineKafkaConsumerConfiguration.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/dlt/DltTopologyStatusTest.kt`
- Create: `module-pipeline-messaging/src/test/kotlin/maple/pipeline/messaging/dlt/DltTopologyResourcesTest.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/KafkaConsumerConfig.kt`
- Modify: `module-infra/build.gradle`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt`
- Modify: `docs/05_Reports/2026-07-19-kafka-delivery-outcome-evidence.md`

**Interfaces:**

- Consumes: broker topic metadata and all migrated subscriptions.
- Produces: idempotent create/expand-only DLT provisioning, health/metric evidence that each DLT has at least the source partition count, and no active listener using legacy ACK code.

- [ ] **Step 1: Add deterministic topology evaluation tests**

Given source/DLT partition maps, `DltTopologyStatus` returns healthy only when every DLT exists and `dltPartitions >= sourcePartitions`. Its plan emits `CreateDlt(topic, sourcePartitions)` for a missing DLT, `ExpandDlt(topic, sourcePartitions)` for an undersized DLT, and no action for a valid DLT. A missing source is unhealthy and never produces a create action. Topic names are bounded and no message content is retained.

Run: `./gradlew :module-pipeline-messaging:test --tests '*DltTopologyStatusTest' --tests '*DltTopologyResourcesTest'`

Expected: compilation fails because status types do not exist.

- [ ] **Step 2: Implement asynchronous create/expand-only topology convergence and health**

Bind `pipeline.messaging.dlt-topology.refresh-interval=PT30S` and `pipeline.messaging.dlt-topology.ensure-enabled=true`; require a positive duration. `DltTopologyResources` creates one `AdminClient` from Boot's admin properties, owns it, and closes it once with a five-second bound. First describe all source topics with `describeTopics(sourceTopics).allTopicNames().toCompletionStage()`; any missing source fails the cycle and produces no mutation. Then use `describeTopics(dltTopics).topicNameValues()` and compose each Kafka future independently so only `UnknownTopicOrPartitionException` becomes a missing-DLT fact while authorization/transport failures remain exceptional. A single `allTopicNames()` call over source plus possibly missing DLTs is forbidden because it loses the partial metadata needed to plan creation. Cache the last bounded status. When ensure is enabled, compose only these idempotent mutations before one full verifying re-read:

- create a missing `${source}.DLT` with the source partition count and broker-default replication factor via `NewTopic(name, Optional.of(partitions), Optional.empty())`;
- increase an undersized DLT with `createPartitions`; never shrink, delete, reconfigure, or create a missing source topic.

Treat only `TopicExistsException`/same-target `InvalidPartitionsException` as convergence races and always verify by describing again; authorization, timeout, missing-source, or any other error remains unhealthy. Do not block a health thread with `get`. Refresh once on application-ready, then schedule the next refresh only after completion using `CompletableFuture.delayedExecutor(refreshInterval.toMillis(), MILLISECONDS, Executor { it.run() })`; an atomic running flag prevents scheduling after close. The health indicator reports `UP` with `subscriptions=0` when no pipeline subscriptions exist (the app/web facade case), otherwise `OUT_OF_SERVICE` before the first verified result and cached `UP`/`DOWN` afterward. A source/DLT mismatch or provisioning failure emits a startup error and a low-cardinality gauge. Tests prove two concurrent reconcilers converge without deleting or shrinking anything.

- [ ] **Step 3: Reduce the infra config to a legacy facade**

Add `implementation project(':module-pipeline-messaging')` to `module-infra`. Existing app/web imports may retain `KafkaConsumerConfig`, but it delegates/imports the new configuration. Active ETL applications import `PipelineKafkaConsumerConfiguration` directly. Remove the legacy default factory from an executable only after every listener in that executable is represented by a subscription.

- [ ] **Step 4: Run source guards**

Run:

```bash
rg -n '@KafkaListener|Acknowledgment|acknowledge\(|nack\(' module-calculator/src/main module-synchronizer/src/main module-external-api/src/main module-cleanup/src/main
```

Expected: no matches. `Acknowledgment` matches are allowed only under `module-pipeline-messaging/src/main`.

- [ ] **Step 5: Run all affected tests and compilation**

Run:

```bash
./gradlew :module-pipeline-messaging:test :module-infra:test :module-external-api:test :module-calculator:test :module-synchronizer:test :module-cleanup:test
./gradlew compileKotlin compileJava --continue
```

Expected: both commands exit `0`; no new integration-test source set exists.

- [ ] **Step 6: Validate topology convergence with existing Docker Kafka**

Start the existing local broker without resetting or deleting data:

```bash
set -euo pipefail
docker compose up -d kafka

source_topics=(
  external-api.snapshot.chunk-ready
  external-api.urgent.snapshot.chunk-ready
  calculator.result.chunk-ready
  external-api.ocid.lookup-ready
  urgent-character-request
  auth-character-fetch-request
  synchronizer.chunk.consumed
)

```

Boot the current images/JARs with `external-api.schedule.enabled=false` and no reset flags, wait through the initial `OUT_OF_SERVICE` reconciliation state until every health endpoint is `UP`, then run:

```bash
for source_topic in "${source_topics[@]}"; do
  docker exec maple-kafka kafka-topics \
    --bootstrap-server localhost:9092 --describe --topic "$source_topic"
  docker exec maple-kafka kafka-topics \
    --bootstrap-server localhost:9092 --describe --topic "$source_topic.DLT"
done | tee /tmp/kafka-delivery-topology-after.txt
```

Use the captured-PID boot/cleanup discipline from the runtime-ownership plan so a failed health check cannot leave a JVM behind. Exercise one deterministic malformed non-secret record and one malformed auth JSON record containing no credential. Use unique `delivery-probe-<timestamp>` keys and record only those keys plus DLT diagnostic envelopes; never dump the auth source topic.

The real-broker evidence proves:

- non-secret DLT retains the original value and metadata;
- auth DLT contains only the diagnostic envelope;
- source group advances only after DLT send success;
- every listed DLT exists with at least the source partition count after convergence.

Do not delete/rename topics or intentionally take the shared broker/DLT unavailable. DLT-send failure/no-commit, partition-lane independence, same-partition monotonic ACK, rebalance fencing, and DLT-only retry are proven by the deterministic Task 3 component tests with controllable send futures; link those test reports here instead of mutating shared broker topology.

Expected: every DLT partition count is greater than or equal to its source; no API key appears in broker dumps or logs.

- [ ] **Step 7: Capture after metrics and runtime health**

Start the four services without reset flags. Repeat the baseline's exact record corpus/hash, service JVM options, partition counts, concurrency, 60-second warmup, five-minute measurement window, and 30-second sampling cadence. Record throughput, consumer lag, retries, pauses, duplicates, DLT sends/failures, and JVM/pool pressure. Inspect logs for premature ACK and repeated nested retry signatures. If the baseline workload cannot be reproduced byte-for-byte, mark the comparison invalid and rerun; do not compare unrelated traffic windows.

Expected: no silent drop, no nested calculator retry, no unexplained throughput regression, and all four service health endpoints report `UP`.

- [ ] **Step 8: Commit topology/wiring/evidence**

```bash
git add module-pipeline-messaging module-infra module-external-api module-calculator module-synchronizer module-cleanup docs/05_Reports/2026-07-19-kafka-delivery-outcome-evidence.md
git commit -m "refactor: complete ETL Kafka delivery boundary"
```

## Plan Completion Gate

- [ ] `git diff --check` is clean.
- [ ] Workload production source has zero `@KafkaListener`, `Acknowledgment`, `acknowledge()`, and `nack()` matches.
- [ ] Every subscription has an explicit success boundary, retry behavior, DLT sanitizer choice, and stable listener ID.
- [ ] DLT failure/no-commit, partition-lane ordering/independence, rebalance fencing, pause/resume, duplicate, and required-send ordering tests pass.
- [ ] Auth timeout/429/5xx classification remains explicitly pending the Nexon consolidation plan; ACK/send ordering is complete here.
- [ ] Before/after evidence and broker topology are recorded without secret payloads.
