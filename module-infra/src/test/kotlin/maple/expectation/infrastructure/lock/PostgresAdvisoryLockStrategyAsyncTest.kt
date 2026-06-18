package maple.expectation.infrastructure.lock

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture
import maple.expectation.infrastructure.executor.LogicExecutor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Async API contract tests for [PostgresAdvisoryLockStrategy].
 *
 * <p>Verifies that the *Async methods exist on the concrete strategy and
 * return CompletableFuture without blocking the caller. The actual PG
 * round-trip is covered by integration tests (out of scope here).
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostgresAdvisoryLockStrategyAsyncTest {

    @Mock
    lateinit var jdbcTemplate: JdbcTemplate

    @Mock
    lateinit var lockTransactionTemplate: TransactionTemplate

    @Mock
    lateinit var executor: LogicExecutor

    private fun lockMetrics(): LockMetrics = LockMetrics(SimpleMeterRegistry())

    private fun strategy(): PostgresAdvisoryLockStrategy = PostgresAdvisoryLockStrategy(
        jdbcTemplate = jdbcTemplate,
        executor = executor,
        lockTransactionTemplate = lockTransactionTemplate,
        lockMetrics = lockMetrics(),
        lockExecutor = java.util.concurrent.ForkJoinPool.commonPool(),
    )

    @Test
    fun `executeWithLockAsync returns CompletableFuture without blocking caller`() {
        // Arrange: hashtext() returns a deterministic lockId, pg_try_advisory_lock returns true
        whenever(jdbcTemplate.queryForObject("SELECT hashtext(?)", Long::class.java, any())).thenReturn(123L)
        whenever(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean::class.java, 123L))
            .thenReturn(true)

        // Act
        val result = strategy().executeWithLockAsync("test-key", 10, 20L) {
            CompletableFuture.completedFuture("ok")
        }

        // Assert: not null and completes with "ok"
        assertNotNull(result)
        assertTrue(result is CompletableFuture<*>)
        assertEquals("ok", result.get())
    }

    @Test
    fun `executeWithLockAsync convenience overload returns CompletableFuture`() {
        whenever(jdbcTemplate.queryForObject("SELECT hashtext(?)", Long::class.java, any())).thenReturn(456L)
        whenever(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean::class.java, 456L))
            .thenReturn(true)

        val result = strategy().executeWithLockAsync("test-key") {
            CompletableFuture.completedFuture(42)
        }

        assertNotNull(result)
        assertEquals(42, result.get())
    }

    @Test
    fun `tryLockImmediatelyAsync returns CompletableFuture with Boolean`() {
        whenever(jdbcTemplate.queryForObject("SELECT hashtext(?)", Long::class.java, any())).thenReturn(789L)
        whenever(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean::class.java, 789L))
            .thenReturn(true)

        val result = strategy().tryLockImmediatelyAsync("key", 20L)

        assertNotNull(result)
        assertEquals(true, result.get())
    }

    @Test
    fun `unlockAsync returns CompletableFuture Void`() {
        whenever(jdbcTemplate.queryForObject("SELECT hashtext(?)", Long::class.java, any())).thenReturn(321L)
        whenever(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean::class.java, 321L))
            .thenReturn(true)
        whenever(jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean::class.java, 321L))
            .thenReturn(true)

        val s = strategy()
        s.tryLockImmediatelyAsync("key", 20L).get()

        val result = s.unlockAsync("key")
        assertNotNull(result)
        result.get()
    }

    @Test
    fun `executeWithLeaderElectionAsync returns CompletableFuture`() {
        whenever(jdbcTemplate.queryForObject("SELECT hashtext(?)", Long::class.java, any())).thenReturn(654L)
        whenever(jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean::class.java, 654L))
            .thenReturn(true)

        val result = strategy().executeWithLeaderElectionAsync(
            key = "key",
            waitTimeSeconds = 10,
            leaderSupplier = { CompletableFuture.completedFuture("leader-result") },
            followerSupplier = { CompletableFuture.completedFuture("follower-result") },
        )

        assertNotNull(result)
        // Leader path should resolve to leader-result
        assertEquals("leader-result", result.get())
    }
}
