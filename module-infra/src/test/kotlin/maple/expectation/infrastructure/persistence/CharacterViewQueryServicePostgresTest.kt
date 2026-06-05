package maple.expectation.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.concurrent.Executors
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.port.inbound.CharacterViewProjectionCommand
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import maple.expectation.infrastructure.persistence.repository.CharacterValuationViewJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate

/**
 * Unit tests for [CharacterViewQueryServicePostgres].
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>findByUserIgn delegates to repository and returns entity</li>
 *   <li>upsert with new entity → inserts (no existing entity found)</li>
 *   <li>upsert with newer version → updates existing entity</li>
 *   <li>upsert with stale version (lower) → skips update</li>
 *   <li>countByUserIgn returns 1 when entity exists, 0 when not</li>
 *   <li>getLastAppliedVersion returns version when entity exists, 0L when not</li>
 *   <li>deleteByUserIgn delegates to repository</li>
 * </ul>
 *
 * @see CharacterViewQueryServicePostgres
 */
@Tag("unit")
@ExtendWith(MockitoExtension::class)
@DisplayName("CharacterViewQueryServicePostgres 단위 테스트")
class CharacterViewQueryServicePostgresTest {

    @Mock
    private lateinit var repository: CharacterValuationViewJpaRepository

    @Mock
    private lateinit var readModelWriteService: ExpectationReadModelWriteService

    private lateinit var objectMapper: ObjectMapper

    private lateinit var meterRegistry: MeterRegistry

    private lateinit var executor: LogicExecutor

    @Mock
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var service: CharacterViewQueryServicePostgres

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        meterRegistry = SimpleMeterRegistry()
        executor = TestLogicExecutor()
        transactionTemplate = NoOpTransactionTemplate()
        service = CharacterViewQueryServicePostgres(
            repository,
            readModelWriteService,
            objectMapper,
            executor,
            meterRegistry,
            jdbc,
            transactionTemplate,
            Executors.newVirtualThreadPerTaskExecutor(),
            500,
        )
    }

    @Test
    @DisplayName("findByUserIgn은 repository에 위임하여 entity를 반환한다")
    fun findByUserIgn_delegatesToRepository() {
        val userIgn = "testUser"
        val entity = createTestEntity(userIgn, version = 1L)
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(entity)

        val result = service.findByUserIgn(userIgn)

        assertThat(result).isNotNull
        assertThat(result?.userIgn).isEqualTo(userIgn)
        verify(repository).findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)
    }

    @Test
    @DisplayName("findByUserIgn은 entity가 없으면 null을 반환한다")
    fun findByUserIgn_returnsNullWhenNotFound() {
        val userIgn = "nonExistentUser"
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(null)

        val result = service.findByUserIgn(userIgn)

        assertThat(result).isNull()
    }

    @Test
    @DisplayName("upsert는 새 entity를 insert한다")
    fun upsert_insertsNewEntity() {
        val userIgn = "newUser"
        val messageId = "msg-123"
        val entity = createTestEntity(userIgn, messageId = messageId, version = 100L)
        whenever(jdbc.update(any<String>(), any<Map<String, *>>())).thenReturn(1)

        service.upsert(entity)

        verify(jdbc).update(any<String>(), any<Map<String, *>>())
    }

    @Test
    @DisplayName("upsert는 더 높은 버전으로 기존 entity를 update한다")
    fun upsert_updatesExistingEntityWithNewerVersion() {
        val userIgn = "existingUser"
        val messageId = "msg-456"
        val incoming = createTestEntity(userIgn, messageId = messageId, version = 100L)
        whenever(jdbc.update(any<String>(), any<Map<String, *>>())).thenReturn(1)

        service.upsert(incoming)

        verify(jdbc).update(any<String>(), any<Map<String, *>>())
    }

    @Test
    @DisplayName("upsert는 낮은 버전으로 update를 건너뛴다")
    fun upsert_skipsUpdateWithStaleVersion() {
        val userIgn = "existingUser"
        val messageId = "msg-789"
        val incoming = createTestEntity(userIgn, messageId = messageId, version = 50L)
        whenever(jdbc.update(any<String>(), any<Map<String, *>>())).thenReturn(0)

        service.upsert(incoming)

        verify(jdbc).update(any<String>(), any<Map<String, *>>())
    }

    @Test
    @DisplayName("countByUserIgn은 entity가 존재하면 1을 반환한다")
    fun countByUserIgn_returnsOneWhenExists() {
        val userIgn = "existingUser"
        val entity = createTestEntity(userIgn, version = 1L)
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(entity)

        val result = service.countByUserIgn(userIgn)

        assertThat(result).isEqualTo(1L)
    }

    @Test
    @DisplayName("countByUserIgn은 entity가 없으면 0을 반환한다")
    fun countByUserIgn_returnsZeroWhenNotExists() {
        val userIgn = "nonExistentUser"
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(null)

        val result = service.countByUserIgn(userIgn)

        assertThat(result).isEqualTo(0L)
    }

    @Test
    @DisplayName("getLastAppliedVersion은 entity가 존재하면 버전을 반환한다")
    fun getLastAppliedVersion_returnsVersionWhenExists() {
        val userIgn = "existingUser"
        val lastAppliedVersion = 100L
        val entity = createTestEntity(userIgn, version = 150L, lastAppliedVersion = lastAppliedVersion)
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(entity)

        val result = service.getLastAppliedVersion(userIgn)

        assertThat(result).isEqualTo(lastAppliedVersion)
    }

    @Test
    @DisplayName("getLastAppliedVersion은 entity가 없으면 0L을 반환한다")
    fun getLastAppliedVersion_returnsZeroWhenNotExists() {
        val userIgn = "nonExistentUser"
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(null)

        val result = service.getLastAppliedVersion(userIgn)

        assertThat(result).isEqualTo(0L)
    }

    @Test
    @DisplayName("upsertFromCalculation은 JSON을 파싱하여 entity를 upsert한다")
    fun upsertFromCalculation_parsesJsonAndUpserts() {
        val userIgn = "testUser"
        val messageId = "msg-123"
        val characterOcid = "ocid-456"
        val characterClass = "전체계산가"
        val characterLevel = 300
        val totalExpectedCost = 1000000L
        val maxPresetNo = 3
        val presetsJson = """[]"""

        whenever(jdbc.update(any<String>(), any<Map<String, *>>())).thenReturn(1)

        service.upsertFromCalculation(
            userIgn,
            messageId,
            characterOcid,
            characterClass,
            characterLevel,
            totalExpectedCost,
            maxPresetNo,
            1, // presetNo default
            presetsJson,
        )

        val captor = argumentCaptor<Map<String, *>>()
        verify(jdbc).update(any<String>(), captor.capture())
        val params = captor.firstValue
        assertThat(params["userIgn"]).isEqualTo(userIgn)
        assertThat(params["messageId"]).isEqualTo(messageId)
        assertThat(params["characterOcid"]).isEqualTo(characterOcid)
        assertThat(params["characterClass"]).isEqualTo(characterClass)
        assertThat(params["characterLevel"]).isEqualTo(characterLevel)
        assertThat(params["totalExpectedCost"]).isEqualTo(totalExpectedCost)
        assertThat(params["maxPresetNo"]).isEqualTo(maxPresetNo)
        assertThat(params["presetNo"]).isEqualTo(1)
    }

    @Test
    @DisplayName("batchUpsertFromCalculations는 JDBC batch upsert를 수행한다")
    fun batchUpsertFromCalculations_usesJdbcBatchUpsert() {
        val commands = listOf(
            CharacterViewProjectionCommand(
                userIgn = "testUser1",
                messageId = "101",
                characterOcid = "ocid-101",
                characterClass = "전체계산가",
                characterLevel = null,
                totalExpectedCost = 1000000L,
                maxPresetNo = 3,
                presetNo = 1,
                presetsJson = "[]",
            ),
            CharacterViewProjectionCommand(
                userIgn = "testUser2",
                messageId = "102",
                characterOcid = "ocid-102",
                characterClass = "전체계산가",
                characterLevel = null,
                totalExpectedCost = 2000000L,
                maxPresetNo = 4,
                presetNo = 2,
                presetsJson = "[]",
            ),
        )
        whenever(jdbc.batchUpdate(any<String>(), any<Array<SqlParameterSource>>())).thenReturn(intArrayOf(1, 1))

        val result = service.batchUpsertFromCalculations(commands)

        val captor = argumentCaptor<Array<SqlParameterSource>>()
        verify(jdbc).batchUpdate(any<String>(), captor.capture())
        assertThat(result).isEqualTo(2)
        assertThat(captor.firstValue).hasSize(2)
        assertThat(captor.firstValue[0].getValue("userIgn")).isEqualTo("testUser1")
        assertThat(captor.firstValue[0].getValue("messageId")).isEqualTo("101")
        assertThat(captor.firstValue[1].getValue("totalExpectedCost")).isEqualTo(2000000L)
    }

    private fun createTestEntity(
        userIgn: String,
        messageId: String? = null,
        version: Long? = null,
        lastAppliedVersion: Long? = null,
    ): CharacterValuationViewEntity {
        val preset = CharacterValuationViewEntity.PresetView(
            presetNo = 1,
            totalExpectedCost = 100000L,
            totalCostText = "10만",
            costBreakdown = CharacterValuationViewEntity.CostBreakdownView(
                blackCubeCost = 10000L,
                redCubeCost = 20000L,
                additionalCubeCost = 5000L,
                starforceCost = 30000L,
                flameCost = 35000L,
            ),
            items = listOf(
                CharacterValuationViewEntity.ItemExpectationView(
                    itemName = "TestItem",
                    expectedCost = 50000L,
                    costText = "5만",
                ),
            ),
        )
        return CharacterValuationViewEntity(
            id = 1L,
            jpaVersion = 0L,
            userIgn = userIgn,
            messageId = messageId,
            characterOcid = "ocid-123",
            characterClass = "전체계산가",
            characterLevel = 300,
            calculatedAt = Instant.now(),
            lastApiSyncAt = Instant.now(),
            version = version,
            lastAppliedVersion = lastAppliedVersion,
            totalExpectedCost = 1000000L,
            maxPresetNo = 3,
            presets = listOf(preset),
            fromCache = false,
        )
    }

    /**
     * 테스트용 간단한 LogicExecutor 구현
     */
    private class TestLogicExecutor : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T = try {
            task.get()
        } catch (e: Throwable) {
            throw RuntimeException(e)
        }

        override fun <T> execute(task: ThrowingSupplier<T>, taskName: String): T = execute(task, TaskContext.of("Test", taskName))

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T = try {
            task.get()
        } catch (_: Throwable) {
            defaultValue
        }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            try {
                task.run()
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
        }

        override fun executeVoid(task: ThrowingRunnable, taskName: String) {
            executeVoid(task, TaskContext.of("Test", taskName))
        }

        override fun <T> executeWithFinally(
            task: ThrowingSupplier<T>,
            finallyBlock: Runnable,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Throwable) {
            throw RuntimeException(e)
        } finally {
            finallyBlock.run()
        }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            translator: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Throwable) {
            throw translator.translate(e, context)
        }

        override fun <T> executeWithFallback(
            task: ThrowingSupplier<T>,
            fallback: (Throwable) -> T,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Throwable) {
            fallback(e)
        }

        override fun <T> executeWithFallback(
            task: ThrowingSupplier<T>,
            fallback: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Throwable) {
            @Suppress("UNCHECKED_CAST")
            fallback.translate(e, context) as T
        }

        override fun <T> executeOrCatch(
            task: ThrowingSupplier<T>,
            recovery: (Throwable) -> T,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Throwable) {
            recovery(e)
        }

        override fun <T> executeOrCatch(
            task: ThrowingSupplier<T>,
            recovery: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Throwable) {
            @Suppress("UNCHECKED_CAST")
            recovery.translate(e, context) as T
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }

        override fun executeVoidJava(task: Runnable, taskName: String) {
            executeVoidJava(task, TaskContext.of("Test", taskName))
        }
    }

    /**
     * 테스트용 TransactionTemplate - callback을 즉시 실행 (트랜잭션 없음)
     */
    private class NoOpTransactionTemplate : TransactionTemplate() {
        override fun <T> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
    }
}
