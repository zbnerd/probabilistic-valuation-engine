package maple.pipeline.artifact.storage

import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
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

    @ParameterizedTest
    @EnumSource(ResourceStage::class)
    fun `construction failure closes every acquired resource once in dependency order`(
        failureStage: ResourceStage,
    ) {
        val resourceFactory = RecordingResourceFactory(failureStage)

        assertThatThrownBy {
            MinioStorageResourcesFactory(resourceFactory).create(clientConfiguration())
        }.isSameAs(resourceFactory.constructionFailure)
        assertThat(resourceFactory.closeOrder).containsExactlyElementsOf(
            when (failureStage) {
                ResourceStage.SYNC_CLIENT -> emptyList()
                ResourceStage.ASYNC_CLIENT -> listOf("sync-client")
                ResourceStage.TRANSFER_MANAGER -> listOf("async-client", "sync-client")
                ResourceStage.STREAM_READER_EXECUTOR ->
                    listOf("transfer-manager", "async-client", "sync-client")
            },
        )
    }

    @Test
    fun `construction failure remains primary while close failures are suppressed in order`() {
        val transferCloseFailure = IllegalStateException("transfer close")
        val asyncCloseFailure = IllegalStateException("async close")
        val syncCloseFailure = IllegalStateException("sync close")
        val resourceFactory = RecordingResourceFactory(
            failureStage = ResourceStage.STREAM_READER_EXECUTOR,
            closeFailures = mapOf(
                "transfer-manager" to transferCloseFailure,
                "async-client" to asyncCloseFailure,
                "sync-client" to syncCloseFailure,
            ),
        )

        val thrown = catchThrowable {
            MinioStorageResourcesFactory(resourceFactory).create(clientConfiguration())
        }

        assertThat(thrown).isSameAs(resourceFactory.constructionFailure)
        assertThat(thrown.suppressed).containsExactly(
            transferCloseFailure,
            asyncCloseFailure,
            syncCloseFailure,
        )
        assertThat(resourceFactory.closeOrder).containsExactly(
            "transfer-manager",
            "async-client",
            "sync-client",
        )
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

    private fun clientConfiguration() = MinioClientConfiguration(
        endpoint = URI.create("http://minio:9000"),
        region = Region.US_EAST_1,
        credentialsProvider = AnonymousCredentialsProvider.create(),
        serviceConfiguration = S3Configuration.builder().pathStyleAccessEnabled(true).build(),
    )

    enum class ResourceStage {
        SYNC_CLIENT,
        ASYNC_CLIENT,
        TRANSFER_MANAGER,
        STREAM_READER_EXECUTOR,
    }

    private class RecordingResourceFactory(
        private val failureStage: ResourceStage,
        private val closeFailures: Map<String, Throwable> = emptyMap(),
    ) : MinioStorageResourceFactory {
        val constructionFailure = IllegalStateException("$failureStage construction")
        val closeOrder = mutableListOf<String>()
        private val syncClient = mock<S3Client> {
            on { close() } doAnswer { recordClose("sync-client") }
        }
        private val asyncClient = mock<S3AsyncClient> {
            on { close() } doAnswer { recordClose("async-client") }
        }
        private val transferManager = mock<S3TransferManager> {
            on { close() } doAnswer { recordClose("transfer-manager") }
        }
        private val streamReaderExecutor = mock<ExecutorService> {
            on { close() } doAnswer { recordClose("stream-reader") }
        }

        override fun createSyncClient(configuration: MinioClientConfiguration): S3Client {
            failAt(ResourceStage.SYNC_CLIENT)
            return syncClient
        }

        override fun createAsyncClient(configuration: MinioClientConfiguration): S3AsyncClient {
            failAt(ResourceStage.ASYNC_CLIENT)
            return asyncClient
        }

        override fun createTransferManager(asyncClient: S3AsyncClient): S3TransferManager {
            failAt(ResourceStage.TRANSFER_MANAGER)
            return transferManager
        }

        override fun createStreamReaderExecutor(): ExecutorService {
            failAt(ResourceStage.STREAM_READER_EXECUTOR)
            return streamReaderExecutor
        }

        private fun failAt(stage: ResourceStage) {
            if (failureStage == stage) throw constructionFailure
        }

        private fun recordClose(resource: String) {
            closeOrder.add(resource)
            closeFailures[resource]?.let { failure -> throw failure }
        }
    }
}
