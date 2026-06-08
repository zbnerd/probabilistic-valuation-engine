# Spec: DRY Module-Common Extraction (#943)

- Status: Draft
- Date: 2026-06-05
- Owner: TBD
- Issue: #943

## 1. Background / Problem

Three duplicated patterns across calculator, synchronizer, and external-api modules:

### 1. Compression ratio string (3 modules)

Identical formula in 3 places:
```kotlin
if (compressed > 0) "%.2f".format(uncompressed.toDouble() / compressed.toDouble()) else "N/A"
```
- `CalculatorChunkProcessingCoordinator:110` (calculator)
- `KafkaResultChunkConsumer:120` (synchronizer)
- `ChunkedSnapshotSink:227` (external-api)

### 2. sha256Hex (synchronizer, 2 files)

Identical private function:
```kotlin
private fun sha256Hex(input: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
```
- `BasicChunkFileReader:187-188` (synchronizer)
- `EquipmentDocumentPreparer:30-31` (synchronizer)

`module-common` already has `HashUtils.kt` — add `sha256Hex()` there.

### 3. KafkaConsumerConfig (3 modules)

Identical 35-line `@Configuration` class:
- `module-calculator/.../config/KafkaConsumerConfig.kt`
- `module-synchronizer/.../config/KafkaConsumerConfig.kt`
- `module-external-api/.../config/KafkaConsumerConfig.kt`

Same DLQ naming (`"${record.topic()}.DLT"`), same `FixedBackOff(1000, 3)`, same log format.

Cannot go to `module-common` (zero Spring deps enforced by `verifyNoSpringDependency`). Goes to `module-infra` instead (all 3 modules depend on it).

### Goal

Extract 3 duplicated patterns into shared locations. Zero behavioral change.

## 2. Decision

### CompressionUtils.ratioString()

New file in module-common:

```kotlin
// module-common/.../util/CompressionUtils.kt
package maple.expectation.util

object CompressionUtils {
    fun ratioString(uncompressed: Long, compressed: Long): String =
        if (compressed > 0) "%.2f".format(uncompressed.toDouble() / compressed.toDouble()) else "N/A"
}
```

### sha256Hex — already exists in HashUtils

`module-common/.../util/HashUtils.kt` already has `sha256Hex(data: ByteArray): String`. Just import and use it. Delete private duplicates in 2 synchronizer files.

### KafkaConsumerConfig to module-infra

Move to `module-infra/.../config/KafkaConsumerConfig.kt` (package `maple.expectation.infrastructure.config`). Add `implementation spring-kafka` to `module-infra/build.gradle`. Delete 3 module-local copies. Each module adds `@Import(KafkaConsumerConfig::class)` to its Application class (auto-scan won't find it — scan scopes don't include `maple.expectation.infrastructure.config`).

## 3. Trade-offs

### Sensitivity

- `module-infra` now transitively provides `spring-kafka` to `module-web`/`module-app` (legacy modules, scheduled for cleanup)
- `HashUtils` is a utility object — adding a function is low-risk

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| KafkaConfig in module-infra | 3 files → 1 | spring-kafka transitive to web/app |
| Skip KafkaConfig | No dependency change | 3 identical files remain |

Decision: extract to module-infra. web/app are legacy.

### Risk

- None — pure extraction, no behavioral change

### Non-Risk

- `verifyNoSpringDependency` — CompressionUtils and sha256Hex are pure Kotlin/JDK, no Spring

## 4. File changes

| File | Action | What |
|------|--------|------|
| `module-common/.../util/CompressionUtils.kt` | CREATE | ratioString utility |
| `module-infra/build.gradle` | MODIFY | add `implementation spring-kafka` dependency |
| `module-infra/.../config/KafkaConsumerConfig.kt` | CREATE | shared Kafka config |
| `module-calculator/.../config/KafkaConsumerConfig.kt` | DELETE | replaced by module-infra |
| `module-calculator/.../CalculatorApplication.kt` | MODIFY | add `@Import(KafkaConsumerConfig::class)` |
| `module-synchronizer/.../config/KafkaConsumerConfig.kt` | DELETE | replaced by module-infra |
| `module-synchronizer/.../SynchronizerApplication.kt` | MODIFY | add `@Import(KafkaConsumerConfig::class)` |
| `module-external-api/.../config/KafkaConsumerConfig.kt` | DELETE | replaced by module-infra |
| `module-external-api/.../ExternalApiApplication.kt` | MODIFY | add `@Import(KafkaConsumerConfig::class)` |
| `module-calculator/.../CalculatorChunkProcessingCoordinator.kt` | MODIFY | use CompressionUtils |
| `module-synchronizer/.../consumer/KafkaResultChunkConsumer.kt` | MODIFY | use CompressionUtils |
| `module-external-api/.../snapshot/ChunkedSnapshotSink.kt` | MODIFY | use CompressionUtils |
| `module-synchronizer/.../storage/BasicChunkFileReader.kt` | MODIFY | use HashUtils.sha256Hex |
| `module-synchronizer/.../preparer/EquipmentDocumentPreparer.kt` | MODIFY | use HashUtils.sha256Hex |

## 5. Testing

- Existing tests pass. No new tests (pure extraction).
- Compile gate: `./gradlew compileKotlin compileJava --continue`
- Test gate: `./gradlew test`

## 6. Acceptance criteria

From #943 (updated scope):
- [ ] `CompressionUtils.ratioString()` in module-common, 3 callers updated
- [ ] `HashUtils.sha256Hex()` imported from module-common (already exists), 2 callers updated
- [ ] `KafkaConsumerConfig` in module-infra, 3 module-local copies deleted
- [ ] Compile + test pass

## 7. Summary

> Extract 3 duplicated patterns: compression ratio string and SHA-256 hex to module-common (pure Kotlin), Kafka consumer config to module-infra (Spring). 12 files touched, zero behavioral change.
