package maple.externalapi.scheduler

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.boot.context.event.ApplicationReadyEvent

class ExternalApiSchedulerLifecycleTest {
    @Test
    fun `start waits for the first readiness event before triggering scheduler work`() {
        val scheduler = mock<ExternalApiScheduler>()
        val lifecycle = ExternalApiSchedulerLifecycle(scheduler)

        lifecycle.start()

        assertThat(lifecycle.isRunning).isTrue()
        assertThat(lifecycle.isAutoStartup).isTrue()
        assertThat(lifecycle.phase).isEqualTo(Int.MAX_VALUE - 100)
        verifyNoInteractions(scheduler)

        val readyEvent = mock<ApplicationReadyEvent>()
        lifecycle.onApplicationEvent(readyEvent)
        lifecycle.onApplicationEvent(readyEvent)

        verify(scheduler, times(1)).startAfterReady()
    }

    @Test
    fun `duplicate stop calls share one named virtual shutdown and complete every callback once`() {
        val scheduler = mock<ExternalApiScheduler>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopThreadName = AtomicReference<String>()
        val stopThreadVirtual = AtomicBoolean(false)
        doAnswer {
            stopThreadName.set(Thread.currentThread().name)
            stopThreadVirtual.set(Thread.currentThread().isVirtual)
            entered.countDown()
            release.await()
            Unit
        }.`when`(scheduler).stopAndAwait(Duration.ofSeconds(5))
        val lifecycle = ExternalApiSchedulerLifecycle(scheduler)
        val callbacks = AtomicInteger()
        lifecycle.start()

        lifecycle.stop(Runnable { callbacks.incrementAndGet() })
        lifecycle.stop(Runnable { callbacks.incrementAndGet() })

        await().until { entered.count == 0L }
        assertThat(lifecycle.isRunning).isFalse()
        assertThat(callbacks).hasValue(0)

        release.countDown()
        await().until { callbacks.get() == 2 }
        verify(scheduler, times(1)).stopAndAwait(Duration.ofSeconds(5))
        assertThat(stopThreadName).hasValue("external-api-scheduler-stop")
        assertThat(stopThreadVirtual).isTrue()
    }

    @Test
    fun `scheduler stop failure still completes callback exactly once`() {
        val scheduler = mock<ExternalApiScheduler>()
        doThrow(IllegalStateException("shutdown failed"))
            .whenever(scheduler)
            .stopAndAwait(Duration.ofSeconds(5))
        val lifecycle = ExternalApiSchedulerLifecycle(scheduler)
        val callbacks = AtomicInteger()
        lifecycle.start()

        lifecycle.stop(Runnable { callbacks.incrementAndGet() })

        await().until { callbacks.get() == 1 }
        assertThat(lifecycle.isRunning).isFalse()
        verify(scheduler, times(1)).stopAndAwait(Duration.ofSeconds(5))
    }
}
