package maple.externalapi.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class ExternalApiExecutorConfigurationTest {
    @Test
    fun `configuration exposes only distinct active virtual-thread executors`() {
        val context = contextWith(SimpleMeterRegistry())

        val internal = context.getBean("internalApiExecutor", ExecutorService::class.java)
        val urgent = context.getBean("urgentCharacterRequestExecutor", ExecutorService::class.java)
        val internalThread = CompletableFuture.supplyAsync(::currentThread, internal)
        val urgentThread = CompletableFuture.supplyAsync(::currentThread, urgent)

        await().until { internalThread.isDone && urgentThread.isDone }
        assertThat(internal).isNotSameAs(urgent)
        assertThat(context.containsBean("authCharacterFetchExecutor")).isFalse()
        assertThat(context.containsBean("externalApiSchedulerExecutor")).isFalse()
        assertThat(internalThread).isCompletedWithValueMatching { thread ->
            thread.virtual && thread.name.startsWith("external-internal-")
        }
        assertThat(urgentThread).isCompletedWithValueMatching { thread ->
            thread.virtual && thread.name.startsWith("external-urgent-")
        }

        context.close()
    }

    @Test
    fun `context close waits for active work and terminates both owners`() {
        val context = contextWith(SimpleMeterRegistry())
        val internal = context.getBean("internalApiExecutor", ExecutorService::class.java)
        val urgent = context.getBean("urgentCharacterRequestExecutor", ExecutorService::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        internal.submit {
            entered.countDown()
            release.await()
        }
        await().until { entered.count == 0L }

        val closeThread = Thread.ofPlatform().name("external-executor-context-close").start(context::close)
        await().until { closeThread.isAlive && internal.isShutdown }
        assertThat(internal.isTerminated).isFalse()

        release.countDown()
        await().until { !closeThread.isAlive }
        assertThat(internal.isTerminated).isTrue()
        assertThat(urgent.isTerminated).isTrue()
    }

    @Test
    fun `unfinished work is interrupted and force shutdown is counted with static tags`() {
        val registry = SimpleMeterRegistry()
        val configuration = ExternalApiExecutorConfiguration(registry, Duration.ofMillis(10))
        val internal = configuration.internalApiExecutor()
        configuration.urgentCharacterRequestExecutor()
        val entered = CountDownLatch(1)
        val neverRelease = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        internal.submit {
            entered.countDown()
            runCatching { neverRelease.await() }
                .onFailure { failure -> interrupted.set(failure is InterruptedException) }
        }
        await().until { entered.count == 0L }

        configuration.shutdownOwnedExecutors()

        await().until { internal.isTerminated && interrupted.get() }
        assertThat(
            registry.find("etl.executor.forced.shutdown")
                .tag("module", "external-api")
                .tag("executor", "internal")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.find("etl.executor.forced.shutdown")
                .tag("module", "external-api")
                .tag("executor", "urgent")
                .counter(),
        ).isNull()
    }

    private fun contextWith(registry: SimpleMeterRegistry): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
            beanFactory.registerSingleton("meterRegistry", registry)
            register(ExternalApiExecutorConfiguration::class.java)
            refresh()
        }

    private fun currentThread(): ThreadObservation {
        val thread = Thread.currentThread()
        return ThreadObservation(thread.name, thread.isVirtual)
    }

    private data class ThreadObservation(
        val name: String,
        val virtual: Boolean,
    )
}
