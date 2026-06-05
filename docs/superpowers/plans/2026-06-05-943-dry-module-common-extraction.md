# DRY Module-Common Extraction Plan (#943)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract 3 duplicated patterns across calculator, synchronizer, external-api into shared locations.

**Architecture:** CompressionUtils + HashUtils import in module-common (pure Kotlin). KafkaConsumerConfig in module-infra (Spring). Zero behavioral change.

**Tech Stack:** Kotlin, Spring Kafka, `object` utility pattern.

**Branch:** Create `refactor/943-dry-module-common-extraction` off `develop`. PR base: `develop`.

**Spec:** `docs/superpowers/specs/2026-06-05-943-dry-module-common-extraction-design.md`

---

## File structure

| File | Action | What |
|------|--------|------|
| `module-common/.../util/CompressionUtils.kt` | CREATE | ratioString utility (object) |
| `module-calculator/.../CalculatorChunkProcessingCoordinator.kt` | MODIFY | use CompressionUtils.ratioString() |
| `module-synchronizer/.../consumer/KafkaResultChunkConsumer.kt` | MODIFY | use CompressionUtils.ratioString() |
| `module-external-api/.../snapshot/ChunkedSnapshotSink.kt` | MODIFY | use CompressionUtils.ratioString() |
| `module-synchronizer/.../storage/BasicChunkFileReader.kt` | MODIFY | import HashUtils.sha256Hex, delete private sha256Hex |
| `module-synchronizer/.../preparer/EquipmentDocumentPreparer.kt` | MODIFY | import HashUtils.sha256Hex, delete private sha256Hex |
| `module-infra/build.gradle` | MODIFY | add `implementation spring-kafka` |
| `module-infra/.../config/KafkaConsumerConfig.kt` | CREATE | shared Kafka config |
| `module-calculator/.../config/KafkaConsumerConfig.kt` | DELETE | replaced by module-infra |
| `module-synchronizer/.../config/KafkaConsumerConfig.kt` | DELETE | replaced by module-infra |
| `module-external-api/.../config/KafkaConsumerConfig.kt` | DELETE | replaced by module-infra |
| `module-calculator/.../CalculatorApplication.kt` | MODIFY | add @Import(KafkaConsumerConfig::class) |
| `module-synchronizer/.../SynchronizerApplication.kt` | MODIFY | add @Import(KafkaConsumerConfig::class) |
| `module-external-api/.../ExternalApiApplication.kt` | MODIFY | add @Import(KafkaConsumerConfig::class) |

---

## Task 1: Create branch off develop

- [ ] **Step 1.1:** Fetch develop, create branch

```bash
cd /home/maple/probabilistic-valuation-engine
git fetch origin develop
git checkout develop
git pull origin develop
git checkout -b refactor/943-dry-module-common-extraction
```

---

## Task 2: Create CompressionUtils + update 3 callers

**Files:**
- Create: `module-common/src/main/kotlin/maple/expectation/util/CompressionUtils.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt`

- [ ] **Step 2.1:** Create CompressionUtils

```kotlin
package maple.expectation.util

object CompressionUtils {
    @JvmStatic
    fun ratioString(uncompressed: Long, compressed: Long): String =
        if (compressed > 0) "%.2f".format(uncompressed.toDouble() / compressed.toDouble()) else "N/A"
}
```

- [ ] **Step 2.2:** In `CalculatorChunkProcessingCoordinator.kt`, find:
```kotlin
val ratio = if (result.resultCompressedBytes > 0) "%.2f".format(result.resultUncompressedBytes.toDouble() / result.resultCompressedBytes.toDouble()) else "N/A"
```
Replace with:
```kotlin
val ratio = CompressionUtils.ratioString(result.resultUncompressedBytes, result.resultCompressedBytes)
```
Add import: `import maple.expectation.util.CompressionUtils`

- [ ] **Step 2.3:** In `KafkaResultChunkConsumer.kt`, find:
```kotlin
val ratio = if (event.compressedBytes > 0)
    "%.2f".format(event.uncompressedBytes.toDouble() / event.compressedBytes.toDouble())
else "N/A"
```
Replace with:
```kotlin
val ratio = CompressionUtils.ratioString(event.uncompressedBytes, event.compressedBytes)
```
Add import: `import maple.expectation.util.CompressionUtils`

- [ ] **Step 2.4:** In `ChunkedSnapshotSink.kt`, find:
```kotlin
val ratio = if (stats.compressedBytes > 0) "%.2f".format(stats.uncompressedBytes.toDouble() / stats.compressedBytes.toDouble()) else "N/A"
```
Replace with:
```kotlin
val ratio = CompressionUtils.ratioString(stats.uncompressedBytes, stats.compressedBytes)
```
Add import: `import maple.expectation.util.CompressionUtils`

- [ ] **Step 2.5:** Commit
```bash
git add module-common/src/main/kotlin/maple/expectation/util/CompressionUtils.kt module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
git commit -m "refactor(943): extract CompressionUtils.ratioString to module-common"
```

---

## Task 3: Update 2 sha256Hex callers

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparer.kt`

`HashUtils.sha256Hex()` already exists in module-common. Just import and delete private copies.

- [ ] **Step 3.1:** In `BasicChunkFileReader.kt`:
  - Add import: `import maple.expectation.util.HashUtils`
  - Delete the private `sha256Hex` function (around lines 187-188)
  - Replace call `sha256Hex(bodyBytes)` with `HashUtils.sha256Hex(bodyBytes)`
  - Remove unused import `import java.security.MessageDigest` if present

- [ ] **Step 3.2:** In `EquipmentDocumentPreparer.kt`:
  - Add import: `import maple.expectation.util.HashUtils`
  - Delete the private `sha256Hex` function (around lines 30-31)
  - Replace call `sha256Hex(bytes)` with `HashUtils.sha256Hex(bytes)`
  - Remove unused import `import java.security.MessageDigest` if present

- [ ] **Step 3.3:** Commit
```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparer.kt
git commit -m "refactor(943): use HashUtils.sha256Hex from module-common in synchronizer"
```

---

## Task 4: Extract KafkaConsumerConfig to module-infra

**Files:**
- Modify: `module-infra/build.gradle` — add spring-kafka
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/KafkaConsumerConfig.kt`
- Delete: 3 module-local KafkaConsumerConfig files
- Modify: 3 Application classes — add @Import

- [ ] **Step 4.1:** In `module-infra/build.gradle`, add spring-kafka dependency:
```groovy
implementation 'org.springframework.kafka:spring-kafka'
```
Add it near other implementation dependencies.

- [ ] **Step 4.2:** Copy one of the 3 KafkaConsumerConfig files to module-infra with updated package:
```kotlin
package maple.expectation.infrastructure.config

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConsumerConfig {

    private val log = LoggerFactory.getLogger(KafkaConsumerConfig::class.java)

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaTemplate: KafkaTemplate<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = consumerFactory

        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record: ConsumerRecord<*, *>, ex: Exception ->
            TopicPartition(record.topic() + ".DLT", record.partition())
        }

        factory.containerProperties.isAckOnError = false
        factory.setErrorHandler(DefaultErrorHandler(recoverer, FixedBackOff(1000, 3)))
        return factory
    }
}
```
NOTE: Read the actual file first to get exact code — the above is a template based on the issue description.

- [ ] **Step 4.3:** Delete 3 module-local KafkaConsumerConfig files:
```bash
rm module-calculator/src/main/kotlin/maple/calculator/config/KafkaConsumerConfig.kt
rm module-synchronizer/src/main/kotlin/maple/synchronizer/config/KafkaConsumerConfig.kt
rm module-external-api/src/main/kotlin/maple/externalapi/config/KafkaConsumerConfig.kt
```

- [ ] **Step 4.4:** Add `@Import(KafkaConsumerConfig::class)` to each Application class:
  - `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt`
  - `module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt` (already has @Import — add to existing)
  - `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`
  - Add import: `import maple.expectation.infrastructure.config.KafkaConsumerConfig`

- [ ] **Step 4.5:** Commit
```bash
git add -A
git commit -m "refactor(943): extract KafkaConsumerConfig to module-infra, @Import from 3 modules"
```

---

## Task 5: Compile + test gates

- [ ] **Step 5.1:** Compile all
```bash
./gradlew compileKotlin compileJava --continue
```

- [ ] **Step 5.2:** Run tests
```bash
./gradlew test
```

- [ ] **Step 5.3:** Verify no remaining private sha256Hex
```bash
grep -rn "private fun sha256Hex" module-synchronizer/src/main/kotlin/
```
Expected: no output.

---

## Task 6: PR

- [ ] **Step 6.1:** Push
```bash
git push -u origin refactor/943-dry-module-common-extraction
```

- [ ] **Step 6.2:** Create PR
```bash
gh pr create \
  --base develop \
  --head refactor/943-dry-module-common-extraction \
  --title "refactor(943): DRY extraction — CompressionUtils, sha256Hex, KafkaConsumerConfig" \
  --body "$(cat <<'EOF'
## Summary
Extract 3 duplicated patterns:
- **CompressionUtils.ratioString()** → `module-common/util/` (new, pure Kotlin)
- **HashUtils.sha256Hex()** → import existing module-common function, delete 2 private copies
- **KafkaConsumerConfig** → `module-infra/config/` (Spring), 3 modules `@Import` it

## Files
- Created: `CompressionUtils.kt`, `KafkaConsumerConfig.kt` (module-infra)
- Deleted: 3 module-local `KafkaConsumerConfig.kt`
- Modified: 3 ratio callers, 2 sha256Hex callers, 3 Application classes, module-infra build.gradle

## Verification
- [x] `./gradlew compileKotlin compileJava --continue` passes
- [x] `./gradlew test` passes

Closes #943
EOF
)"
```
