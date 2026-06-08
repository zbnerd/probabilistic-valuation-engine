package maple.expectation.infrastructure.batch

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDateTime
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.core.domain.model.character.UserIgn
import maple.expectation.infrastructure.config.AdaptiveMicroBatchProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class GameCharacterMicroBatchAdapterTest {

    @Mock
    private lateinit var repository: GameCharacterRepository

    private lateinit var properties: AdaptiveMicroBatchProperties
    private lateinit var logicExecutor: StubLogicExecutor
    private lateinit var adapter: GameCharacterMicroBatchAdapter

    @BeforeEach
    fun setUp() {
        properties = AdaptiveMicroBatchProperties.defaults()
        logicExecutor = StubLogicExecutor()
        val meterRegistry = SimpleMeterRegistry()

        adapter = GameCharacterMicroBatchAdapter(
            properties = properties,
            logicExecutor = logicExecutor,
            meterRegistry = meterRegistry,
            repository = repository,
        )
    }

    @Test
    @DisplayName("Should return character from single loader")
    fun `getByUserIgn returns character from repository`() {
        // Given
        val character = createTestCharacter("TestChar", "ocid123")
        whenever(repository.findByUserIgn("TestChar")).thenReturn(character)

        // When
        val result = adapter.getByUserIgn("TestChar")

        // Then
        assertThat(result).isNotNull
        assertThat(result?.userIgn?.value).isEqualTo("TestChar")
    }

    @Test
    @DisplayName("Should return null for non-existent character")
    fun `getByUserIgn returns null when not found`() {
        // Given
        whenever(repository.findByUserIgn("NonExistent")).thenReturn(null)

        // When
        val result = adapter.getByUserIgn("NonExistent")

        // Then
        assertThat(result).isNull()
    }

    private fun createTestCharacter(userIgn: String, ocid: String): GameCharacter = GameCharacter(
        id = null,
        userIgn = UserIgn.of(userIgn),
        characterId = CharacterId.of(ocid),
        equipment = null,
        worldName = "Scania",
        characterClass = "Warrior",
        characterImage = null,
        basicInfoUpdatedAt = LocalDateTime.now(),
        likeCount = 0L,
        version = null,
        updatedAt = LocalDateTime.now(),
    )

    /**
     * Stub LogicExecutor - simple implementation for testing
     */
    private class StubLogicExecutor(
        private val executionDelayMs: Long = 0,
    ) : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            return task.get()
        }

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T = try {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.get() ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T = try {
            execute(task, context)
        } finally {
            finallyBlock.run()
        }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            fallback(e)
        }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw customTranslator.translate(e, context)
        }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            recovery(e)
        }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw fallback.translate(e, context)
        }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw recovery.translate(e, context)
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.run()
        }
    }
}
