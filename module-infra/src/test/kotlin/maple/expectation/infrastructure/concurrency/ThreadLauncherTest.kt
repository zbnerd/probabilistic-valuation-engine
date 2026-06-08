package maple.expectation.infrastructure.concurrency

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadLauncherTest {
    @Test
    fun `launch runs block asynchronously`() {
        val exec = Executors.newSingleThreadExecutor()
        val launcher = DefaultThreadLauncher(exec)
        val ran = AtomicBoolean(false)

        launcher.launch("test-task") { ran.set(true) }
        exec.shutdown()
        assertTrue(exec.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(ran.get())
    }
}
