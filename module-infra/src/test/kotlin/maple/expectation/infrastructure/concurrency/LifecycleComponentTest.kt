package maple.expectation.infrastructure.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals

class LifecycleComponentTest {
    @Test
    fun `default shutdown timeout is 5000ms`() {
        val component = TestComponent("test")
        assertEquals(5_000L, component.shutdownTimeoutMs())
    }

    @Test
    fun `destroy calls drain then waits timeout`() {
        val component = TestComponent("test")
        component.destroy()
        assertTrue(component.drainCalled)
    }

    private class TestComponent(val name: String) : LifecycleComponent {
        var drainCalled = false
        override fun componentName() = name
        override suspend fun drain() { drainCalled = true }
    }
}
