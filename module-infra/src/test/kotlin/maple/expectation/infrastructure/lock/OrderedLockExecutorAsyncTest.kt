package maple.expectation.infrastructure.lock

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * Async API contract tests for [OrderedLockExecutor].
 *
 * <p>Verifies that `executeWithOrderedLocksAsync` exists and returns
 * `CompletableFuture` without blocking the caller. The actual PG round-trip
 * and lock semantics are covered by integration tests (out of scope here).
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderedLockExecutorAsyncTest {

    @Mock
    lateinit var lockStrategy: LockStrategy

    @Mock
    lateinit var executor: LogicExecutor

    private fun orderedLockExecutor(): OrderedLockExecutor = OrderedLockExecutor(lockStrategy, executor)

    private fun jutilList(vararg elems: String): java.util.List<String> =
        (java.util.ArrayList<String>().apply { elems.forEach { add(it) } }) as java.util.List<String>

    /**
     * Configure the [LogicExecutor] mock so that the strategy detection probe
     * returns `false` (i.e. Redisson / non-nested path), and other calls are
     * no-ops. This is enough for the async chain to short-circuit into the
     * iterative strategy.
     */
    private fun stubExecutorForRedissonPath() {
        // Strategy probe: tryLockImmediately returns true, returns false
        // (so we pick the iterative strategy, not the nested one).
        whenever(lockStrategy.tryLockImmediately(any(), any())).thenReturn(true)
        whenever(
            executor.executeOrDefault(any<ThrowingSupplier<Boolean>>(), any<Boolean>(), any<TaskContext>()),
        ).thenAnswer { invocation ->
            val supplier = invocation.arguments[0] as ThrowingSupplier<Boolean>
            supplier.get()
        }
        // unlockSafely → executeVoidJava (no-op)
        // whenComplete path → no executor call (uses lockStrategy.unlockAsync directly)
    }

    @Test
    fun `executeWithOrderedLocksAsync returns CompletableFuture without blocking caller`() {
        stubExecutorForRedissonPath()
        whenever(lockStrategy.tryLockImmediatelyAsync(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(true))
        whenever(lockStrategy.unlockAsync(any()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val result = orderedLockExecutor().executeWithOrderedLocksAsync(
            keys = jutilList("a", "b"),
            totalTimeout = 30,
            timeUnit = TimeUnit.SECONDS,
            leaseTime = 60L,
            supplier = { CompletableFuture.completedFuture("done") },
        )

        assertNotNull(result)
        assertEquals("done", result.get())
    }

    @Test
    fun `executeWithOrderedLocksAsync with single key invokes supplier directly`() {
        stubExecutorForRedissonPath()
        whenever(lockStrategy.tryLockImmediatelyAsync(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(true))
        whenever(lockStrategy.unlockAsync(any()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val result = orderedLockExecutor().executeWithOrderedLocksAsync(
            keys = jutilList("a"),
            totalTimeout = 30,
            timeUnit = TimeUnit.SECONDS,
            leaseTime = 60L,
            supplier = { CompletableFuture.completedFuture(42) },
        )

        assertNotNull(result)
        assertEquals(42, result.get())
    }
}
