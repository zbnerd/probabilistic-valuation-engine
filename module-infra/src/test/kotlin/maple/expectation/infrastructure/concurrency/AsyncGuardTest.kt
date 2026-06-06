package maple.expectation.infrastructure.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AsyncGuardTest {
    @Test
    fun `guard returns chain result when within timeout`() {
        val guard = DefaultAsyncGuard()
        val chain = CompletableFuture.supplyAsync { 42 }
        val guarded = guard.guard("test", 5_000, chain)
        assertEquals(42, guarded.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun `guard fails chain on timeout`() {
        val guard = DefaultAsyncGuard()
        val slow = CompletableFuture.supplyAsync {
            Thread.sleep(500)
            "late"
        }
        val guarded = guard.guard("slow-test", 50, slow)
        val ex = assertThrows(ExecutionException::class.java) {
            guarded.get(2, TimeUnit.SECONDS)
        }
        assert(ex.cause is TimeoutException)
    }
}
