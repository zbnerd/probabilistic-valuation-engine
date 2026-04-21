package maple.expectation.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import maple.expectation.infrastructure.persistence.repository.CharacterValuationViewJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat

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

    private lateinit var service: CharacterViewQueryServicePostgres

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        meterRegistry = SimpleMeterRegistry()
        executor = TestLogicExecutor()
        service = CharacterViewQueryServicePostgres(
            repository,
            readModelWriteService,
            objectMapper,
            executor,
            meterRegistry,
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
        whenever(repository.findByMessageId(messageId)).thenReturn(null)
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(null)
        whenever(repository.save(any())).thenReturn(entity)

        service.upsert(entity)

        val captor = argumentCaptor<CharacterValuationViewEntity>()
        verify(repository).save(captor.capture())
        val saved = captor.firstValue
        assertThat(saved.userIgn).isEqualTo(userIgn)
        assertThat(saved.version).isEqualTo(1L)
        assertThat(saved.lastAppliedVersion).isEqualTo(100L)
    }

    @Test
    @DisplayName("upsert는 더 높은 버전으로 기존 entity를 update한다")
    fun upsert_updatesExistingEntityWithNewerVersion() {
        val userIgn = "existingUser"
        val messageId = "msg-456"
        val existing = createTestEntity(userIgn, messageId = null, version = 50L, lastAppliedVersion = 50L)
        val incoming = createTestEntity(userIgn, messageId = messageId, version = 100L)
        whenever(repository.findByMessageId(messageId)).thenReturn(null)
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(existing)
        whenever(repository.save(any())).thenReturn(incoming)

        service.upsert(incoming)

        val captor = argumentCaptor<CharacterValuationViewEntity>()
        verify(repository).save(captor.capture())
        val saved = captor.firstValue
        assertThat(saved.userIgn).isEqualTo(userIgn)
        assertThat(saved.messageId).isEqualTo(messageId)
        assertThat(saved.lastAppliedVersion).isEqualTo(100L)
    }

    @Test
    @DisplayName("upsert는 낮은 버전으로 update를 건너뛴다")
    fun upsert_skipsUpdateWithStaleVersion() {
        val userIgn = "existingUser"
        val messageId = "msg-789"
        val existing = createTestEntity(userIgn, messageId = null, version = 100L, lastAppliedVersion = 100L)
        val incoming = createTestEntity(userIgn, messageId = messageId, version = 50L)
        whenever(repository.findByMessageId(messageId)).thenReturn(null)
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(existing)

        service.upsert(incoming)

        verify(repository, never()).save(any())
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
    @DisplayName("deleteByUserIgn은 repository에 위임한다")
    fun deleteByUserIgn_delegatesToRepository() {
        val userIgn = "testUser"

        service.deleteByUserIgn(userIgn)

        verify(repository).deleteByUserIgn(userIgn)
    }

    @Test
    @DisplayName("upsertFromCalculation은 JSON을 파싱하여 entity를 저장한다")
    fun upsertFromCalculation_parsesJsonAndSaves() {
        val userIgn = "testUser"
        val messageId = "msg-123"
        val characterOcid = "ocid-456"
        val characterClass = "전체계산가"
        val characterLevel = 300
        val totalExpectedCost = 1000000L
        val maxPresetNo = 3
        val presetsJson = """[]"""

        whenever(repository.findByMessageId(messageId)).thenReturn(null)
        whenever(repository.findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn)).thenReturn(null)
        whenever(repository.save(any())).thenReturn(createTestEntity(userIgn, version = 1L))

        service.upsertFromCalculation(
            userIgn,
            messageId,
            characterOcid,
            characterClass,
            characterLevel,
            totalExpectedCost,
            maxPresetNo,
            presetsJson,
        )

        val captor = argumentCaptor<CharacterValuationViewEntity>()
        verify(repository).save(captor.capture())
        val saved = captor.firstValue
        assertThat(saved.userIgn).isEqualTo(userIgn)
        assertThat(saved.messageId).isEqualTo(messageId)
        assertThat(saved.characterOcid).isEqualTo(characterOcid)
        assertThat(saved.characterClass).isEqualTo(characterClass)
        assertThat(saved.characterLevel).isEqualTo(characterLevel)
        assertThat(saved.totalExpectedCost).isEqualTo(totalExpectedCost)
        assertThat(saved.maxPresetNo).isEqualTo(maxPresetNo)
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

        override fun <T> execute(task: ThrowingSupplier<T>, taskName: String): T {
            return execute(task, TaskContext.of("Test", taskName))
        }

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
        ): T {
            return try {
                task.get()
            } catch (e: Throwable) {
                @Suppress("UNCHECKED_CAST")
                fallback.translate(e, context) as T
            }
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
        ): T {
            return try {
                task.get()
            } catch (e: Throwable) {
                @Suppress("UNCHECKED_CAST")
                recovery.translate(e, context) as T
            }
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }

        override fun executeVoidJava(task: Runnable, taskName: String) {
            executeVoidJava(task, TaskContext.of("Test", taskName))
        }
    }
}
