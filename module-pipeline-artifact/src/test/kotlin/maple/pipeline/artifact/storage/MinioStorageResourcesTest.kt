package maple.pipeline.artifact.storage

import java.util.concurrent.ExecutorService
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.transfer.s3.S3TransferManager

class MinioStorageResourcesTest {
    @Test
    fun `Spring context closes MinIO resources once in dependency order`() {
        val closeOrder = mutableListOf<String>()
        val transferManager = mock<S3TransferManager> {
            on { close() } doAnswer {
                closeOrder.add("transfer-manager")
                Unit
            }
        }
        val asyncClient = mock<S3AsyncClient> {
            on { close() } doAnswer {
                closeOrder.add("async-client")
                Unit
            }
        }
        val syncClient = mock<S3Client> {
            on { close() } doAnswer {
                closeOrder.add("sync-client")
                Unit
            }
        }
        val streamReaderExecutor = mock<ExecutorService> {
            on { close() } doAnswer {
                closeOrder.add("stream-reader")
                Unit
            }
        }
        val resources = MinioStorageResources(
            transferManager,
            asyncClient,
            syncClient,
            streamReaderExecutor,
        )
        val context = contextWith(resources)

        context.close()
        context.close()

        assertThat(closeOrder).containsExactly(
            "transfer-manager",
            "async-client",
            "sync-client",
            "stream-reader",
        )
        verify(transferManager, times(1)).close()
        verify(asyncClient, times(1)).close()
        verify(syncClient, times(1)).close()
        verify(streamReaderExecutor, times(1)).close()
    }

    private fun contextWith(resources: MinioStorageResources): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.registerBean(
            "minioStorageResources",
            MinioStorageResources::class.java,
            Supplier { resources },
            { definition -> definition.destroyMethodName = "close" },
        )
        context.refresh()
        return context
    }
}
