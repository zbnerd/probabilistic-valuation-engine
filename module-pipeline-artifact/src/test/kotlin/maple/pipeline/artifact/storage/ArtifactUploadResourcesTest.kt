package maple.pipeline.artifact.storage

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class ArtifactUploadResourcesTest {
    @AfterEach
    fun clearInterruptFlag() {
        Thread.interrupted()
    }

    @Test
    fun `Spring context closes upload executor exactly once`() {
        val executor = mock<ExecutorService>()
        whenever(executor.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(true)
        val resources = ArtifactUploadResources(executor, meterRegistry = null)
        val context = contextWith(resources)

        context.close()
        context.close()

        verify(executor, times(1)).shutdown()
        verify(executor, times(1)).awaitTermination(5, TimeUnit.SECONDS)
        verify(executor, never()).shutdownNow()
    }

    @Test
    fun `unfinished upload work is forced and counted with static tags`() {
        val executor = mock<ExecutorService>()
        whenever(executor.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(false)
        val registry = SimpleMeterRegistry()
        val resources = ArtifactUploadResources(executor, registry)

        resources.close()

        verify(executor).shutdownNow()
        assertThat(
            registry.find("pipeline.artifact.executor.forced.shutdown")
                .tag("executor", "upload")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `interrupted shutdown restores interruption and forces unfinished work`() {
        val executor = mock<ExecutorService>()
        whenever(executor.awaitTermination(any(), any())).thenThrow(InterruptedException("interrupted"))
        val resources = ArtifactUploadResources(executor, meterRegistry = null)

        resources.close()

        assertThat(Thread.currentThread().isInterrupted).isTrue
        verify(executor).shutdownNow()
    }

    private fun contextWith(resources: ArtifactUploadResources): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.registerBean(
            "artifactUploadResources",
            ArtifactUploadResources::class.java,
            Supplier { resources },
            { definition -> definition.destroyMethodName = "close" },
        )
        context.refresh()
        return context
    }
}
