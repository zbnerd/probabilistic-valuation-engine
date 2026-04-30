# Write Path Pure Calculate Refactoring

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract pure calculation from `EquipmentExpectationServiceV4`'s fetch+calculate+persist pipeline into a standalone `PureExpectationCalculator`, then wire `ApiResponseWorker` to load `CalculationInput` from DB and call the pure calculator — removing all `.join()` blocking and `@Transactional` overlap from the Write Path.

**Architecture:** The current `calculateExpectationAsync` is an orchestration function (fetch + calculate + persist) masquerading as a calculation function. We split it at line 214-216 of `EquipmentExpectationServiceV4.java` — everything above is fetch (stays in Read Path / External API Path), everything below is pure calculate (moves to `PureExpectationCalculator`). The `ApiResponseWorker` loads pre-built `CalculationInput` from DB, calls the pure calculator synchronously (no `CompletableFuture`, no `.join()`), then persists the result in a separate `@Transactional` boundary.

**Tech Stack:** Kotlin 2.x, Spring Boot 3.x, JUnit 5, MockK

---

## File Structure

### Created
| File | Responsibility |
|------|---------------|
| `module-core/.../dto/v4/EquipmentItemConverter.kt` | `EquipmentItem` → `CubeCalculationInput` pure data mapping |
| `module-app/.../service/expectation/PureExpectationCalculator.kt` | `CalculationInput` → `EquipmentExpectationResponseV4` (pure, no I/O) |
| `module-core/src/test/.../dto/v4/EquipmentItemConverterTest.kt` | Converter unit test |
| `module-app/src/test/.../service/expectation/PureExpectationCalculatorTest.kt` | Calculator unit test |

### Modified
| File | Change |
|------|--------|
| `module-app/.../worker/ApiResponseWorker.kt` | Replace `expectationPort.calculateExpectationAsync().join()` with `pureCalculator.calculate(input)` |
| `module-core/.../port/out/CalculationJobPort.kt` | Add `retryCalculation()` method |
| `module-infra/.../repository/CalculationJobRepository.kt` | Add `retryCalculation()` JPQL |
| `module-infra/.../adapter/outgoing/CalculationJobPortAdapter.kt` | Implement `retryCalculation()` |
| `module-infra/.../job/CalculationJobService.kt` | Add `handleCalculationFailure()` with exponential backoff |

---

### Task 1: EquipmentItemConverter — EquipmentItem → CubeCalculationInput

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentItemConverter.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/dto/v4/EquipmentItemConverterTest.kt`

This is the missing bridge between the typed `CalculationInput` contract and the existing calculator pipeline (`PresetCalculationHelper`). It maps each `EquipmentItem` field to the corresponding `CubeCalculationInput` field — a pure data transformation with no I/O or external dependencies.

- [ ] **Step 1: Write the converter test**

```kotlin
package maple.expectation.core.dto.v4

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EquipmentItemConverterTest {

    @Test
    fun `maps all EquipmentItem fields to CubeCalculationInput`() {
        val item = EquipmentItem(
            part = EquipmentSlot.WEAPON,
            equipmentPart = EquipmentPart.WEAPON,
            itemName = "아케인셰이드 스태프",
            level = 200,
            potential = PotentialLines(
                grade = PotentialGrade.LEGENDARY,
                line1 = "INT +12%",
                line2 = "마력 +9%",
                line3 = "올스탯 +3%"
            ),
            additionalPotential = PotentialLines(
                grade = PotentialGrade.UNIQUE,
                line1 = "INT +9%",
                line2 = "마력 +6%",
                line3 = null
            ),
            starforce = 17,
            starforceScrollFlag = StarforceScrollFlag.USED,
            addOption = AddOption(
                str = 0, dex = 0, int = 3, luk = 0,
                maxHp = 0, allStat = 0,
                attackPower = 0, magicPower = 5,
                bossDamage = 0, damage = 0
            ),
            baseAttackPower = 10,
            baseMagicPower = 200
        )

        val result = EquipmentItemConverter.toCubeInput(item)

        assertEquals(200, result.level)
        assertEquals("무기", result.part)
        assertEquals("아케인셰이드 스태프", result.itemName)
        assertEquals("무기", result.itemEquipmentPart)
        assertEquals("레전드리", result.grade)
        assertEquals(listOf("INT +12%", "마력 +9%", "올스탯 +3%"), result.options)
        assertEquals("유니크", result.additionalGrade)
        assertEquals(listOf("INT +9%", "마력 +6%"), result.additionalOptions)
        assertEquals(17, result.starforce)
        assertEquals("사용", result.starforceScrollFlag)
        assertEquals(3, result.addOptionInt)
        assertEquals(5, result.addOptionMag)
        assertEquals(10, result.baseAttackPower)
        assertEquals(200, result.baseMagicPower)
    }

    @Test
    fun `handles null potentials gracefully`() {
        val item = EquipmentItem(
            part = EquipmentSlot.MEDAL,
            equipmentPart = EquipmentPart.ETC,
            itemName = "훈장",
            level = 100,
            potential = null,
            additionalPotential = null,
            starforce = 0,
            starforceScrollFlag = StarforceScrollFlag.NOT_USED,
            addOption = AddOption(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            baseAttackPower = 0,
            baseMagicPower = 0
        )

        val result = EquipmentItemConverter.toCubeInput(item)

        assertNull(result.grade)
        assertTrue(result.options.isEmpty())
        assertNull(result.additionalGrade)
        assertTrue(result.additionalOptions.isEmpty())
        assertEquals(0, result.starforce)
        assertEquals("미사용", result.starforceScrollFlag)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-core:test --tests "maple.expectation.core.dto.v4.EquipmentItemConverterTest" 2>&1 | tail -5`
Expected: FAIL — `EquipmentItemConverter` does not exist yet

- [ ] **Step 3: Write the converter**

```kotlin
package maple.expectation.core.dto.v4

import maple.expectation.core.dto.cube.CubeCalculationInput

object EquipmentItemConverter {

    fun toCubeInput(item: EquipmentItem): CubeCalculationInput = CubeCalculationInput(
        level = item.level,
        part = item.part.koreanName,
        grade = item.potential?.grade?.koreanName,
        options = item.potential?.asList()?.toMutableList() ?: mutableListOf(),
        itemName = item.itemName,
        itemEquipmentPart = item.equipmentPart.koreanName,
        additionalGrade = item.additionalPotential?.grade?.koreanName,
        additionalOptions = item.additionalPotential?.asList()?.filterNotNull()?.toMutableList() ?: mutableListOf(),
        starforce = item.starforce,
        starforceScrollFlag = item.starforceScrollFlag.koreanValue,
        addOptionStr = item.addOption.str,
        addOptionDex = item.addOption.dex,
        addOptionInt = item.addOption.int,
        addOptionLuk = item.addOption.luk,
        addOptionMaxHp = item.addOption.maxHp,
        addOptionAllStat = item.addOption.allStat,
        addOptionAtt = item.addOption.attackPower,
        addOptionMag = item.addOption.magicPower,
        addOptionBossDmg = item.addOption.bossDamage,
        addOptionDmg = item.addOption.damage,
        baseAttackPower = item.baseAttackPower,
        baseMagicPower = item.baseMagicPower,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-core:test --tests "maple.expectation.core.dto.v4.EquipmentItemConverterTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentItemConverter.kt module-core/src/test/kotlin/maple/expectation/core/dto/v4/EquipmentItemConverterTest.kt
git commit -m "feat(write-path): add EquipmentItemConverter for pure calculation input"
```

---

### Task 2: PureExpectationCalculator — CalculationInput → EquipmentExpectationResponseV4

**Files:**
- Create: `module-app/src/main/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculator.kt`
- Create: `module-app/src/test/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculatorTest.kt`

This is the core refactoring — a `@Component` that takes `CalculationInput` (already loaded from DB, no I/O) and returns `EquipmentExpectationResponseV4`. It converts items via `EquipmentItemConverter` then delegates to the existing `PresetCalculationHelper`.

The `future.get(30, TimeUnit.SECONDS)` on the internal CompletableFuture is temporary (per user's direction: "30s timeout은 임시로만 유지하거나 단계적으로 제거"). This blocks only the item-calculation thread pool — no DB connections, no external calls.

- [ ] **Step 1: Write the calculator test**

```kotlin
package maple.expectation.application.service.expectation

import maple.expectation.core.dto.v4.*
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.PresetExpectation
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.ItemExpectationV4
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.CubeExpectationDto
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.StarforceExpectationDto
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.FlameExpectationDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CompletableFuture

class PureExpectationCalculatorTest {

    private val presetHelper: PresetCalculationHelper = mockk()
    private val calculator = PureExpectationCalculator(presetHelper)

    private fun stubPreset() = PresetExpectation(
        presetNo = 1,
        totalExpectedCost = 1_000_000.0,
        totalCostText = "1,000,000",
        costBreakdown = CostBreakdownDto(
            blackCubeCost = 500_000.0,
            redCubeCost = 300_000.0,
            additionalCubeCost = 100_000.0,
            starforceCost = 100_000.0
        ),
        items = listOf(
            ItemExpectationV4(
                itemName = "테스트 무기",
                itemIcon = "",
                itemPart = "무기",
                itemLevel = 200,
                expectedCost = 1_000_000.0,
                expectedCostText = "1,000,000",
                costBreakdown = CostBreakdownDto.empty(),
                enhancePath = "에픽→유니크→레전드리",
                potentialGrade = "레전드리",
                additionalPotentialGrade = null,
                currentStar = 0,
                targetStar = 17,
                isNoljang = true,
                specialRingLevel = 0,
                blackCubeExpectation = CubeExpectationDto.empty(),
                additionalCubeExpectation = CubeExpectationDto.empty(),
                starforceExpectation = StarforceExpectationDto.empty(),
                flameExpectation = FlameExpectationDto.empty()
            )
        )
    )

    @Test
    fun `calculate returns response with correct user IGN and preset data`() {
        val input = CalculationInput(
            jobId = "test-job",
            userIgn = "테스트유저",
            characterClass = "아크메이지",
            presetNo = 1,
            items = listOf(
                EquipmentItem(
                    part = EquipmentSlot.WEAPON,
                    equipmentPart = EquipmentPart.WEAPON,
                    itemName = "테스트 무기",
                    level = 200,
                    potential = PotentialLines(PotentialGrade.LEGENDARY, "INT +12%", "마력 +9%", "올스탯 +3%"),
                    additionalPotential = null,
                    starforce = 17,
                    starforceScrollFlag = StarforceScrollFlag.USED,
                    addOption = AddOption(0, 0, 3, 0, 0, 0, 0, 5, 0, 0),
                    baseAttackPower = 10,
                    baseMagicPower = 200
                )
            )
        )

        val preset = stubPreset()
        every { presetHelper.calculatePresetAsync(any(), eq(1), eq("아크메이지")) } returns CompletableFuture.completedFuture(preset)

        val result = calculator.calculate(input)

        assertEquals("테스트유저", result.userIgn)
        assertFalse(result.fromCache)
        assertEquals(1_000_000.0, result.totalExpectedCost)
        assertEquals(1, result.maxPresetNo)
        assertEquals(1, result.presets.size)
        assertEquals(1_000_000.0, result.presets[0].totalExpectedCost)
    }

    @Test
    fun `calculate propagates exceptions from preset helper`() {
        val input = CalculationInput(
            jobId = "test-job",
            userIgn = "테스트유저",
            characterClass = "아크메이지",
            presetNo = 1,
            items = emptyList()
        )

        every { presetHelper.calculatePresetAsync(any(), any(), any()) } returns
            CompletableFuture.failedFuture(RuntimeException("Calculation failed"))

        assertThrows(Exception::class.java) {
            calculator.calculate(input)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-app:test --tests "maple.expectation.application.service.expectation.PureExpectationCalculatorTest" 2>&1 | tail -5`
Expected: FAIL — `PureExpectationCalculator` does not exist yet

- [ ] **Step 3: Write the calculator**

```kotlin
package maple.expectation.application.service.expectation

import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4
import maple.expectation.core.dto.v4.EquipmentItemConverter
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@Component
class PureExpectationCalculator(
    private val presetHelper: PresetCalculationHelper
) {
    fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 {
        val cubeInputs = input.items.map { EquipmentItemConverter.toCubeInput(it) }

        val future = presetHelper.calculatePresetAsync(
            cubeInputs, input.presetNo, input.characterClass
        )
        val preset = future.get(30, TimeUnit.SECONDS)

        return EquipmentExpectationResponseV4(
            userIgn = input.userIgn,
            calculatedAt = LocalDateTime.now(),
            fromCache = false,
            totalExpectedCost = preset.totalExpectedCost,
            totalCostText = preset.totalCostText,
            totalCostBreakdown = preset.costBreakdown,
            maxPresetNo = input.presetNo,
            presets = listOf(preset)
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-app:test --tests "maple.expectation.application.service.expectation.PureExpectationCalculatorTest" 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-app/src/main/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculator.kt module-app/src/test/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculatorTest.kt
git commit -m "feat(write-path): add PureExpectationCalculator — pure CalculationInput to response"
```

---

### Task 3: Calculation Retry — handleCalculationFailure + retryCalculation

**Depends on:** Task 1, Task 2 (must be implemented before Task 4)

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`

Follow the existing retry pattern from `handleApiFailure()` / `handleOcidFailure()`: check retry count, if under max then transition to a retryable state and re-publish the MQ event, otherwise mark failed.

For calculation retry, the transition is `CALCULATING → SNAPSHOT_READY` (so the existing `snapshot_ready` flow picks it up again). This avoids creating new job statuses.

- [ ] **Step 1: Add retryCalculation to the port interface**

In `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt`, add:

```kotlin
fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean
```

Full file after edit:

```kotlin
package maple.expectation.core.port.out

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import java.time.Instant
import java.util.UUID

interface CalculationJobPort {
    fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob
    fun findJobById(jobId: UUID): CalculationJob?
    fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean
    fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean
    fun incrementRetry(jobId: UUID, errorCode: String): Boolean
    fun incrementRetryForOcid(jobId: UUID, errorCode: String): Boolean
    fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean
    fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean
    fun unlock(jobId: UUID): Boolean
    fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob>
    fun findActiveJobByUserIgn(userIgn: String, presetNo: Int): CalculationJob?
    fun resolveOcidAndTransition(jobId: UUID, ocid: String): Boolean
    fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID>
}
```

- [ ] **Step 2: Add retryCalculation JPQL to repository**

In `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt`, add before the closing `}`:

```kotlin
@Modifying
@Query("""
    UPDATE CalculationJobEntity j
    SET j.retryCount = j.retryCount + 1,
        j.status = 'SNAPSHOT_READY',
        j.nextRetryAt = :nextRetryAt,
        j.lastErrorCode = :errorCode,
        j.lockedBy = NULL, j.lockedUntil = NULL,
        j.updatedAt = CURRENT_TIMESTAMP
    WHERE j.jobId = :jobId
      AND j.status = 'CALCULATING'
      AND j.retryCount < j.maxRetries
""")
fun retryCalculation(
    @Param("jobId") jobId: UUID,
    @Param("errorCode") errorCode: String,
    @Param("nextRetryAt") nextRetryAt: Instant
): Int
```

- [ ] **Step 3: Implement retryCalculation in adapter**

In `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt`, add:

```kotlin
override fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean {
    return jobRepository.retryCalculation(jobId, errorCode, nextRetryAt) > 0
}
```

- [ ] **Step 4: Add handleCalculationFailure to CalculationJobService**

In `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`, add after `handleOcidFailure()` (line ~171):

```kotlin
@Transactional
fun handleCalculationFailure(jobId: UUID, errorCode: String, errorMessage: String) {
    val job = jobPort.findJobById(jobId) ?: return

    if (job.retryCount >= job.maxRetries) {
        jobPort.markFailed(jobId, errorCode, errorMessage)
        log.warn("[jobId={}] Calculation failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        return
    }

    val backoffSeconds = calculateBackoff(job.retryCount)
    val nextRetry = java.time.Instant.now().plusSeconds(backoffSeconds)
    val retried = jobPort.retryCalculation(jobId, errorCode, nextRetry)
    if (retried) {
        val event = NexonApiResponseEventFactory.create(
            jobId.toString(),
            job.snapshotId?.toString() ?: return,
            "",
            job.ocid ?: return,
            job.userIgn,
            job.presetNo
        )
        eventAppender.append(nexonApiResponseTopic, event)
        log.info("[jobId={}] Calculation retry scheduled (attempt {}, backoff={}s)", jobId, job.retryCount + 1, backoffSeconds)
    } else {
        jobPort.markFailed(jobId, errorCode, errorMessage)
    }
}

private fun calculateBackoff(retryCount: Int): Long {
    val baseSeconds = 30L
    return minOf(baseSeconds * (1L shl retryCount), 600L)
}
```

The exponential backoff formula: `30s → 60s → 120s → 240s → 480s` capped at 600s (10 min).

- [ ] **Step 5: Compile and commit**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "(ERROR|FAIL|BUILD)" | head -10`
Expected: BUILD SUCCESSFUL

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt
git commit -m "feat(write-path): add calculation retry with exponential backoff"
```

---

### Task 4: ApiResponseWorker — Remove .join(), Use Pure Calculator

**Depends on:** Task 1, Task 2, Task 3

**Files:**
- Modify: `module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt`

This is the critical change. Replace `expectationPort.calculateExpectationAsync(userIgn, false, jobId.toString(), presetNo).join()` with:
1. Load `CalculationInput` from DB (already done at line 83)
2. Call `pureCalculator.calculate(input)` — synchronous, pure, no I/O
3. Serialize and persist result (existing code)

The `@Transactional` boundary is now ONLY in `jobService.completeCalculationWithResult()` — no transaction wraps the calculation itself.

- [ ] **Step 1: Modify ApiResponseWorker**

Replace the `expectationPort` dependency with `pureCalculator` and restructure `processApiResponse`:

```kotlin
package maple.expectation.application.worker

import maple.expectation.application.service.expectation.PureExpectationCalculator
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ApiResponseWorker(
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val pureCalculator: PureExpectationCalculator,
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService,
    private val calculationInputPort: CalculationInputPort,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val terminalStatuses = setOf(
        CalculationJobStatus.COMPLETED,
        CalculationJobStatus.FAILED
    )

    init {
        nexonApiResponseTopic.subscribe { envelope, _ -> handleApiResponse(envelope) }
    }

    private fun handleApiResponse(envelope: IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val jobId = UUID.fromString(payload["jobId"].toString())
        val userIgn = payload["userIgn"].toString()
        val context = TaskContext.of("ApiResponseWorker", "Process", userIgn)
        return executor.executeOrCatch(
            { processApiResponse(payload, jobId, userIgn) },
            { e ->
                log.error("[jobId={}] Calculation failed: {}", jobId, e.message)
                val msg = (e.message ?: "Unknown error").take(200)
                executor.executeVoid({ jobService.handleCalculationFailure(jobId, "CALCULATION_ERROR", msg) }, context)
                ConsumeResult.Ack
            },
            context
        )
    }

    private fun processApiResponse(payload: Map<*, *>, jobId: UUID, userIgn: String): ConsumeResult {
        val job = jobPort.findJobById(jobId)
        if (job == null) {
            log.warn("[jobId={}] Job not found, archiving", jobId)
            return ConsumeResult.Ack
        }

        if (job.status in terminalStatuses) {
            log.info("[jobId={}] Already in terminal state: {}, skipping", jobId, job.status)
            return ConsumeResult.Ack
        }

        if (job.status == CalculationJobStatus.CALCULATING) {
            log.warn("[jobId={}] Stuck in CALCULATING on redelivery, marking as failed", jobId)
            jobPort.markFailed(jobId, "CALCULATION_STUCK", "Calculation stuck after redelivery")
            return ConsumeResult.Ack
        }

        val started = jobService.startCalculation(jobId, "ApiResponseWorker")
        if (!started) {
            log.warn("[jobId={}] Could not start calculation, archiving", jobId)
            return ConsumeResult.Ack
        }

        val presetNo = (payload["presetNo"] as Number).toInt()
        val characterId = payload["characterId"]?.toString() ?: ""

        val input = calculationInputPort.findByJobId(jobId)
        if (input == null) {
            log.error("[jobId={}] CalculationInput not found, cannot proceed", jobId)
            jobPort.markFailed(jobId, "INPUT_NOT_FOUND", "CalculationInput not found for job")
            return ConsumeResult.Ack
        }

        val result = pureCalculator.calculate(input)

        val resultJson = objectMapper.writeValueAsString(result)

        jobService.completeCalculationWithResult(
            jobId = jobId,
            resultJson = resultJson,
            characterClass = input.characterClass,
            presetNo = presetNo,
            characterId = characterId
        )

        log.info("[jobId={}] Calculation completed from CalculationInput (pure)", jobId)
        return ConsumeResult.Ack
    }
}
```

Key changes vs current code:
- `expectationPort: ExpectationV4Port` → `pureCalculator: PureExpectationCalculator`
- `expectationPort.calculateExpectationAsync(...).join()` → `pureCalculator.calculate(input)` (no CompletableFuture, no .join())
- Error handler: `jobPort.markFailed(...)` → `jobService.handleCalculationFailure(...)` (delegated to service with retry logic)

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "(ERROR|FAIL|BUILD)" | head -10`
Expected: BUILD SUCCESSFUL (no errors)

Note: This step will fail until Task 4 is done because `handleCalculationFailure` doesn't exist yet. If needed, implement Task 4 first or add a stub.

- [ ] **Step 3: Commit**

```bash
git add module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt
git commit -m "refactor(write-path): replace .join() blocking with pure calculator in ApiResponseWorker"
```

---

### Task 4: Calculation Retry — handleCalculationFailure + retryCalculation

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`

Follow the existing retry pattern from `handleApiFailure()` / `handleOcidFailure()`: check retry count, if under max then transition to a retryable state and re-publish the MQ event, otherwise mark failed.

For calculation retry, the transition is `CALCULATING → SNAPSHOT_READY` (so the existing `snapshot_ready` flow picks it up again). This avoids creating new job statuses.

- [ ] **Step 1: Add retryCalculation to the port interface**

In `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt`, add:

```kotlin
fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean
```

Full file after edit:

```kotlin
package maple.expectation.core.port.out

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import java.time.Instant
import java.util.UUID

interface CalculationJobPort {
    fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob
    fun findJobById(jobId: UUID): CalculationJob?
    fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean
    fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean
    fun incrementRetry(jobId: UUID, errorCode: String): Boolean
    fun incrementRetryForOcid(jobId: UUID, errorCode: String): Boolean
    fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean
    fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean
    fun unlock(jobId: UUID): Boolean
    fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob>
    fun findActiveJobByUserIgn(userIgn: String, presetNo: Int): CalculationJob?
    fun resolveOcidAndTransition(jobId: UUID, ocid: String): Boolean
    fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID>
}
```

- [ ] **Step 2: Add retryCalculation JPQL to repository**

In `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt`, add before the closing `}`:

```kotlin
@Modifying
@Query("""
    UPDATE CalculationJobEntity j
    SET j.retryCount = j.retryCount + 1,
        j.status = 'SNAPSHOT_READY',
        j.nextRetryAt = :nextRetryAt,
        j.lastErrorCode = :errorCode,
        j.lockedBy = NULL, j.lockedUntil = NULL,
        j.updatedAt = CURRENT_TIMESTAMP
    WHERE j.jobId = :jobId
      AND j.status = 'CALCULATING'
      AND j.retryCount < j.maxRetries
""")
fun retryCalculation(
    @Param("jobId") jobId: UUID,
    @Param("errorCode") errorCode: String,
    @Param("nextRetryAt") nextRetryAt: Instant
): Int
```

- [ ] **Step 3: Implement retryCalculation in adapter**

In `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt`, add:

```kotlin
override fun retryCalculation(jobId: UUID, errorCode: String, nextRetryAt: Instant): Boolean {
    return jobRepository.retryCalculation(jobId, errorCode, nextRetryAt) > 0
}
```

- [ ] **Step 4: Add handleCalculationFailure to CalculationJobService**

In `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`, add after `handleOcidFailure()` (line ~171):

```kotlin
@Transactional
fun handleCalculationFailure(jobId: UUID, errorCode: String, errorMessage: String) {
    val job = jobPort.findJobById(jobId) ?: return

    if (job.retryCount >= job.maxRetries) {
        jobPort.markFailed(jobId, errorCode, errorMessage)
        log.warn("[jobId={}] Calculation failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        return
    }

    val backoffSeconds = calculateBackoff(job.retryCount)
    val nextRetry = java.time.Instant.now().plusSeconds(backoffSeconds)
    val retried = jobPort.retryCalculation(jobId, errorCode, nextRetry)
    if (retried) {
        val event = NexonApiResponseEventFactory.create(
            jobId.toString(),
            job.snapshotId?.toString() ?: return,
            "",
            job.ocid ?: return,
            job.userIgn,
            job.presetNo
        )
        eventAppender.append(nexonApiResponseTopic, event)
        log.info("[jobId={}] Calculation retry scheduled (attempt {}, backoff={}s)", jobId, job.retryCount + 1, backoffSeconds)
    } else {
        jobPort.markFailed(jobId, errorCode, errorMessage)
    }
}

private fun calculateBackoff(retryCount: Int): Long {
    val baseSeconds = 30L
    return minOf(baseSeconds * (1L shl retryCount), 600L)
}
```

The exponential backoff formula: `30s → 60s → 120s → 240s → 480s` capped at 600s (10 min).

- [ ] **Step 5: Compile and commit**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "(ERROR|FAIL|BUILD)" | head -10`
Expected: BUILD SUCCESSFUL

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt
git commit -m "feat(write-path): add calculation retry with exponential backoff"
```

---

### Task 5: Compile Verification + Full Test Run

**Files:** None (verification only)

- [ ] **Step 1: Full compile check**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "(ERROR|FAIL|BUILD)" | head -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests**

Run: `./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — all tests pass

- [ ] **Step 3: Verify no .join() remains in ApiResponseWorker**

Run: `grep -n "\.join()" module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt`
Expected: no output (no .join() calls)

---

## Verification (Post-Implementation E2E)

After all tasks are done, verify the Write Path end-to-end:

1. Start the server: `set -a && source .env && set +a && ./gradlew :module-app:bootRun`
2. Trigger a calculation via the v5 recalculate endpoint
3. Watch the logs for: `Calculation completed from CalculationInput (pure)`
4. Verify no `.join()` or `CompletableFuture` in the `ApiResponseWorker` path
5. Verify result is persisted and outbox event is inserted
6. Test retry: artificially cause a calculation failure and verify the job transitions to `SNAPSHOT_READY` with incremented retry count

---

## Out of Scope (Future Work)

- Remove `@Transactional` from `EquipmentExpectationServiceV4.calculateExpectation()` (Read Path cleanup)
- Remove the `orTimeout(30s)` from `calculateExpectationAsync` once Read Path is also refactored
- Remove `CalculationWorker` (module-infra) if it's superseded by `ApiResponseWorker` + `PureExpectationCalculator`
- Make `PresetCalculationHelper.calculatePresetAsync()` fully synchronous (remove internal CompletableFuture dispatch)
