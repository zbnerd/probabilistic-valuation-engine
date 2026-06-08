package maple.expectation.infrastructure.concurrency

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExecutorSelectorTest {
    @Test
    fun `submit runs block on registered executor`() {
        val exec = Executors.newSingleThreadExecutor()
        val registry = ExecutorRegistry(mapOf(ExecutorQualifier.IO to exec))
        val selector = DefaultExecutorSelector(registry)
        val counter = AtomicInteger(0)

        selector.submit(ExecutorQualifier.IO) { counter.incrementAndGet() }.get(1, TimeUnit.SECONDS)
        assertEquals(1, counter.get())

        exec.shutdown()
    }

    @Test
    fun `submit throws on unknown qualifier`() {
        val registry = ExecutorRegistry(emptyMap())
        val selector = DefaultExecutorSelector(registry)
        try {
            selector.submit(ExecutorQualifier.CALCULATION) { 1 }
            org.junit.jupiter.api.Assertions.fail("expected exception")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
