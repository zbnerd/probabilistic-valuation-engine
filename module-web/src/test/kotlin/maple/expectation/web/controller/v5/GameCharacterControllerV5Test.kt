package maple.expectation.web.controller.v5

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.concurrent.Executor
import maple.expectation.common.executor.TaskContext
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.domain.model.character.CharacterView
import maple.expectation.core.port.inbound.CalculationQueuePort
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.inbound.ExecutorPort
import maple.expectation.core.port.inbound.TaskReceipt
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.EquipmentFanOutPort
import maple.expectation.web.dto.v5.EquipmentExpectationResponseV5
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * Unit tests for [GameCharacterControllerV5].
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Cache HIT returns 200 with response body</li>
 *   <li>Cache MISS + queue success returns 202 with X-Task-Id header</li>
 *   <li>Cache MISS + queue rejection returns 503 with ErrorResponse body</li>
 *   <li>Fanout prewarm is triggered when fanOutEnabled=true on cache miss</li>
 *   <li>Fanout prewarm is NOT triggered when fanOutEnabled=false</li>
 *   <li>Recalculate deletes cache and enqueues with force=true</li>
 * </ul>
 *
 * @see GameCharacterControllerV5
 */
@Tag("unit")
@DisplayName("GameCharacterControllerV5 단위 테스트")
class GameCharacterControllerV5Test {

    private lateinit var queryPort: FakeCharacterViewQueryPort

    private lateinit var queuePort: FakeCalculationQueuePort

    private lateinit var executorPort: TestExecutorPort

    private lateinit var ocidPort: FakeCharacterOcidPort

    private lateinit var fanOutPort: FakeEquipmentFanOutPort

    private lateinit var computeExecutor: Executor

    private lateinit var preWarmExecutor: Executor

    private lateinit var meterRegistry: MeterRegistry

    private lateinit var controller: GameCharacterControllerV5

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        queryPort = FakeCharacterViewQueryPort()
        queuePort = FakeCalculationQueuePort()
        executorPort = TestExecutorPort()
        ocidPort = FakeCharacterOcidPort()
        fanOutPort = FakeEquipmentFanOutPort()
        computeExecutor = ImmediateExecutor()
        preWarmExecutor = ImmediateExecutor()

        controller = GameCharacterControllerV5(
            queryPort = queryPort,
            queuePort = queuePort,
            executorPort = executorPort,
            ocidPort = ocidPort,
            fanOutEnabled = false,
            fanOutPort = fanOutPort,
            computeExecutor = computeExecutor,
            preWarmExecutor = preWarmExecutor,
            meterRegistry = meterRegistry,
        )
    }

    @Test
    @DisplayName("getExpectationV5는 캐시 HIT 시 200과 응답 본문을 반환한다")
    fun getExpectationV5_cacheHit_returns200() {
        val userIgn = "testUser"
        val response = createTestResponse(userIgn)
        val characterView = createTestCharacterView(userIgn)
        queryPort.characterView = Optional.of(characterView)

        val future = controller.getExpectationV5(userIgn)
        val entity = future.get()

        assertThat(entity.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(entity.body).isNotNull
    }

    @Test
    @DisplayName("getExpectationV5는 캐시 MISS 및 큐 성공 시 202과 X-Task-Id 헤더를 반환한다")
    fun getExpectationV5_cacheMissQueueSuccess_returns202() {
        val userIgn = "testUser"
        val taskId = "pgmq-msg-123"
        val receipt = TaskReceipt(taskId, userIgn, queued = true)

        queryPort.characterView = Optional.empty()
        queuePort.receipt = receipt

        val future = controller.getExpectationV5(userIgn)
        val entity = future.get()

        assertThat(entity.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(entity.headers["X-Task-Id"]).isNotNull
        assertThat(entity.headers["X-Task-Id"]?.first()).isEqualTo(taskId)
    }

    @Test
    @DisplayName("getExpectationV5는 캐시 MISS 및 큐 거부 시 503과 ErrorResponse를 반환한다")
    fun getExpectationV5_cacheMissQueueRejected_returns503() {
        val userIgn = "testUser"
        val rejectedReceipt = TaskReceipt.rejected(userIgn)

        queryPort.characterView = Optional.empty()
        queuePort.receipt = rejectedReceipt

        val future = controller.getExpectationV5(userIgn)
        val entity = future.get()

        assertThat(entity.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(entity.body).isNotNull
    }

    @Test
    @DisplayName("getExpectationV5는 fanOutEnabled=true 시 캐시 MISS에서 fanout prewarm을 트리거한다")
    fun getExpectationV5_fanOutEnabled_triggersPrewarm() {
        val userIgn = "testUser"
        val ocid = "ocid-123"
        val taskId = "pgmq-msg-456"
        val receipt = TaskReceipt(taskId, userIgn, queued = true)

        // Enable fanout
        controller = GameCharacterControllerV5(
            queryPort = queryPort,
            queuePort = queuePort,
            executorPort = executorPort,
            ocidPort = ocidPort,
            fanOutEnabled = true,
            fanOutPort = fanOutPort,
            computeExecutor = computeExecutor,
            preWarmExecutor = preWarmExecutor,
            meterRegistry = meterRegistry,
        )

        queryPort.characterView = Optional.empty()
        queuePort.receipt = receipt
        ocidPort.ocid = ocid

        val future = controller.getExpectationV5(userIgn)
        future.get()

        // Verify fanout was called
        assertThat(ocidPort.resolveOcidCalled).isTrue
        assertThat(fanOutPort.preFetchByOcidCalled).isTrue
        assertThat(fanOutPort.lastOcid).isEqualTo(ocid)
    }

    @Test
    @DisplayName("getExpectationV5는 fanOutEnabled=false 시 캐시 MISS에서 fanout prewarm을 트리거하지 않는다")
    fun getExpectationV5_fanOutDisabled_noPrewarm() {
        val userIgn = "testUser"
        val taskId = "pgmq-msg-789"
        val receipt = TaskReceipt(taskId, userIgn, queued = true)

        queryPort.characterView = Optional.empty()
        queuePort.receipt = receipt

        val future = controller.getExpectationV5(userIgn)
        future.get()

        // Verify fanout was NOT called
        assertThat(ocidPort.resolveOcidCalled).isFalse
        assertThat(fanOutPort.preFetchByOcidCalled).isFalse
    }

    @Test
    @DisplayName("recalculateExpectationV5는 캐시를 삭제하고 force=true로 큐에 등록한다")
    fun recalculateExpectationV5_deletesCacheAndEnqueuesForce() {
        val userIgn = "testUser"
        val taskId = "pgmq-msg-force-123"
        val receipt = TaskReceipt(taskId, userIgn, queued = true)

        queuePort.receipt = receipt

        val future = controller.recalculateExpectationV5(userIgn)
        val entity = future.get()

        assertThat(queryPort.deleteCalled).isTrue
        assertThat(entity.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(entity.headers["X-Task-Id"]?.first()).isEqualTo(taskId)
        assertThat(queuePort.lastForceRecalculation).isTrue()
    }

    @Test
    @DisplayName("getExpectationV5는 IGN의 공백을 트림한다")
    fun getExpectationV5_trimsWhitespace() {
        val userIgn = "  testUser  "
        val trimmedIgn = "testUser"
        val characterView = createTestCharacterView(trimmedIgn)
        queryPort.characterView = Optional.of(characterView)

        val future = controller.getExpectationV5(userIgn)
        val entity = future.get()

        assertThat(entity.statusCode).isEqualTo(HttpStatus.OK)
    }

    private fun createTestResponse(userIgn: String): EquipmentExpectationResponseV5 = EquipmentExpectationResponseV5(
        userIgn = userIgn,
        calculatedAt = Instant.now(),
        fromCache = true,
        totalExpectedCost = BigDecimal.valueOf(1000000L),
        totalCostText = "100만",
        totalCostBreakdown = EquipmentExpectationResponseV5.CostBreakdownDto.empty(),
        maxPresetNo = 3,
        presets = emptyList(),
    )

    private fun createTestCharacterView(userIgn: String): CharacterView = object : CharacterView {
        override val userIgn = userIgn
        override val messageId = "msg-123"
        override val calculatedAt = Instant.now()
        override val fromCache = true
        override val totalExpectedCost = 1000000L
        override val maxPresetNo = 3
        override val presets = emptyList<CharacterView.PresetView>()
    }

    /**
     * Fake implementations of ports for testing
     */
    private class FakeCharacterViewQueryPort : CharacterViewQueryPort {
        var characterView: Optional<CharacterView> = Optional.empty()
        var deleteCalled = false

        override fun findByUserIgn(userIgn: String): Optional<CharacterView> = characterView

        override fun deleteByUserIgn(userIgn: String) {
            deleteCalled = true
        }

        override fun upsertFromCalculation(
            userIgn: String,
            messageId: String?,
            characterOcid: String?,
            characterClass: String?,
            characterLevel: Int?,
            totalExpectedCost: Long,
            maxPresetNo: Int,
            presetNo: Int,
            presetsJson: String,
        ) {
            // Not used in tests
        }
    }

    private class FakeCalculationQueuePort : CalculationQueuePort {
        var receipt: TaskReceipt = TaskReceipt.rejected("test")
        var lastForceRecalculation: Boolean? = null

        override fun offerHighPriority(userIgn: String, forceRecalculation: Boolean): Boolean {
            lastForceRecalculation = forceRecalculation
            return receipt.queued
        }

        override fun offerHighPriorityWithReceipt(userIgn: String, forceRecalculation: Boolean, presetNo: Int): TaskReceipt {
            lastForceRecalculation = forceRecalculation
            return receipt
        }
    }

    private class FakeCharacterOcidPort : CharacterOcidPort {
        var ocid: String? = null
        var resolveOcidCalled = false

        override fun resolveOcid(userIgn: String): String? {
            resolveOcidCalled = true
            return ocid
        }

        override fun resolveOcids(userIgns: Set<String>): Map<String, String> = emptyMap()

        override fun resolveAllOcids(): Map<String, String> = emptyMap()

        override fun resolveOcidsByFingerprint(fingerprint: String): Set<String> = emptySet()

        override fun updateFingerprint(ocid: String, fingerprint: String, accountId: String): Int = 0
    }

    private class FakeEquipmentFanOutPort : EquipmentFanOutPort {
        var preFetchByOcidCalled = false
        var lastOcid: String? = null

        override fun preFetchByOcid(ocid: String): Boolean {
            preFetchByOcidCalled = true
            lastOcid = ocid
            return true
        }
    }

    /**
     * Test implementation of ExecutorPort
     */
    private class TestExecutorPort : ExecutorPort {
        var deleteCalled = false

        override fun executeVoid(task: () -> Unit, context: TaskContext) {
            task()
        }

        override fun <T> executeOrDefault(
            task: () -> T,
            defaultValue: T,
            context: TaskContext,
        ): T = try {
            task()
        } catch (e: Exception) {
            defaultValue
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            if (context.toString().contains("Delete") || context.toString().contains("Invalidate")) {
                deleteCalled = true
            }
            task.run()
        }

        override fun <T> executeOrDefaultJava(
            task: ExecutorPort.ThrowingSupplier<T>,
            defaultValue: T,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Exception) {
            defaultValue
        }

        override fun <T> execute(task: () -> T, context: TaskContext): T = task()

        override fun <T> executeWithTranslation(
            task: () -> T,
            translator: (Throwable, TaskContext) -> Exception,
            context: TaskContext,
        ): T = task()
    }

    /**
     * Executor that runs tasks synchronously
     */
    private class ImmediateExecutor : Executor {
        override fun execute(command: Runnable) {
            command.run()
        }
    }
}
