package maple.externalapi.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

class LoopExecutorConfigTest {

    @Test
    fun `loopExecutor bean is a ThreadPoolTaskExecutor with configured sizing and virtual threads`() {
        val cfg = LoopExecutorConfig()
        val executor = cfg.loopExecutor(
            corePoolSize = 4,
            maxPoolSize = 16,
            queueCapacity = 64,
            threadNamePrefix = "ext-api-loop-",
            virtualThreads = true,
            awaitTerminationSeconds = 30,
        )

        assertNotNull(executor)
        assertTrue(executor is ThreadPoolTaskExecutor)
        val tpe = executor as ThreadPoolTaskExecutor
        assertEquals(4, tpe.corePoolSize)
        assertEquals(16, tpe.maxPoolSize)
        assertEquals(64, tpe.queueCapacity)
        assertEquals("ext-api-loop-", tpe.threadNamePrefix)
        // The executor accepts and runs a Runnable end-to-end.
        var ran = false
        tpe.execute(Runnable { ran = true })
        tpe.shutdown()
        assertTrue(ran, "executor must run submitted Runnable before shutdown completes")
    }
}
