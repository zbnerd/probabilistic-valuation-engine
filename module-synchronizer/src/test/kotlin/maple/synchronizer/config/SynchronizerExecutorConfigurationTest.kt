package maple.synchronizer.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.util.ReflectionTestUtils

class SynchronizerExecutorConfigurationTest {
    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `configuration preserves named virtual and OCID platform executor semantics`() {
        val context = contextWith(SimpleMeterRegistry())
        val result = context.getBean("kafkaResultChunkExecutor", ExecutorService::class.java)
        val basic = context.getBean("basicSnapshotChunkExecutor", ExecutorService::class.java)
        val ocid = context.getBean("synchronizerOcidLookupExecutor", ThreadPoolTaskExecutor::class.java)
        val resultThread = CompletableFuture.supplyAsync(::currentThread, result)
        val basicThread = CompletableFuture.supplyAsync(::currentThread, basic)

        await().until { resultThread.isDone && basicThread.isDone }
        assertThat(result).isNotSameAs(basic)
        assertThat(resultThread).isCompletedWithValueMatching { thread ->
            thread.virtual && thread.name.startsWith("sync-result-chunk-")
        }
        assertThat(basicThread).isCompletedWithValueMatching { thread ->
            thread.virtual && thread.name.startsWith("sync-basic-chunk-")
        }
        assertThat(context.containsBean("defaultAsyncExecutor")).isFalse()
        assertThat(ocid.corePoolSize).isEqualTo(8)
        assertThat(ocid.maxPoolSize).isEqualTo(16)
        assertThat(ocid.queueCapacity).isEqualTo(200)
        assertThat(ocid.threadNamePrefix).isEqualTo("async-")
        assertThat(ocid.keepAliveSeconds).isEqualTo(30)
        assertThat(ocid.threadPoolExecutor.allowsCoreThreadTimeOut()).isTrue()
        assertThat(ocid.threadPoolExecutor.rejectedExecutionHandler)
            .isInstanceOf(ThreadPoolExecutor.AbortPolicy::class.java)
        assertThat(ReflectionTestUtils.getField(ocid, "waitForTasksToCompleteOnShutdown"))
            .isEqualTo(true)
        assertThat(ReflectionTestUtils.getField(ocid, "awaitTerminationMillis"))
            .isEqualTo(30_000L)

        context.close()

        assertThat(result.isTerminated).isTrue()
        assertThat(basic.isTerminated).isTrue()
        assertThat(ocid.threadPoolExecutor.isTerminated).isTrue()
    }

    @Test
    fun `OCID pool propagates caller MDC and decorator restores prior worker MDC`() {
        val context = contextWith(SimpleMeterRegistry())
        val ocid = context.getBean("synchronizerOcidLookupExecutor", ThreadPoolTaskExecutor::class.java)
        val observed = AtomicReference<String?>()
        MDC.put("traceId", "caller-value")

        val completed = CompletableFuture.runAsync(
            { observed.set(MDC.get("traceId")) },
            ocid,
        )
        await().until { completed.isDone }

        assertThat(completed).isCompleted()
        assertThat(observed).hasValue("caller-value")

        val decorator = SynchronizerMdcTaskDecorator()
        MDC.put("traceId", "captured-value")
        val decorated = decorator.decorate {
            assertThat(MDC.get("traceId")).isEqualTo("captured-value")
            MDC.put("traceId", "task-value")
        }
        MDC.put("traceId", "worker-before")

        decorated.run()

        assertThat(MDC.get("traceId")).isEqualTo("worker-before")
        context.close()
    }

    @Test
    fun `unfinished virtual work is interrupted and force shutdown is counted`() {
        val registry = SimpleMeterRegistry()
        val configuration = SynchronizerExecutorConfiguration(registry, Duration.ofMillis(10))
        val result = configuration.kafkaResultChunkExecutor()
        configuration.basicSnapshotChunkExecutor()
        val entered = CountDownLatch(1)
        val neverRelease = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        result.submit {
            entered.countDown()
            runCatching { neverRelease.await() }
                .onFailure { failure -> interrupted.set(failure is InterruptedException) }
        }
        await().until { entered.count == 0L }

        configuration.shutdownOwnedExecutors()

        await().until { result.isTerminated && interrupted.get() }
        assertThat(
            registry.find("etl.executor.forced.shutdown")
                .tag("module", "synchronizer")
                .tag("executor", "result")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.find("etl.executor.forced.shutdown")
                .tag("module", "synchronizer")
                .tag("executor", "basic")
                .counter(),
        ).isNull()
    }

    private fun contextWith(registry: SimpleMeterRegistry): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
            beanFactory.registerSingleton("meterRegistry", registry)
            register(SynchronizerExecutorConfiguration::class.java)
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
