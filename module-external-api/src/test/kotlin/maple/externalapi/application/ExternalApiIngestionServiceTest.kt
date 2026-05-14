package maple.externalapi.application

import java.util.concurrent.CompletableFuture
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiPayloadRef
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.port.out.ExternalApiClientPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ExternalApiIngestionServiceTest {

    @Mock
    private lateinit var clientPort: ExternalApiClientPort

    @Mock
    private lateinit var artifactStorePort: ExternalApiArtifactStorePort

    private lateinit var service: ExternalApiIngestionService

    private val dummyRef = ExternalApiPayloadRef(
        artifactUri = "/data/external-api/ocid-lookup/test_ign.json.gz",
        sha256 = "abc123",
        sizeBytes = 100L,
    )

    @BeforeEach
    fun setUp() {
        service = ExternalApiIngestionService(clientPort, artifactStorePort)
    }

    @Test
    fun `fetchSingle - success flow`() {
        val responseData = """{"ocid":"test-ocid-123"}""".toByteArray()
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "강은호"))
            .thenReturn(CompletableFuture.completedFuture(responseData))
        whenever(artifactStorePort.store(ExternalApiEndpoint.OCID_LOOKUP, "강은호", responseData))
            .thenReturn(dummyRef)

        val result = service.fetchSingle(
            provider = ExternalApiProvider.NEXON,
            endpoint = ExternalApiEndpoint.OCID_LOOKUP,
            requestKey = "강은호",
            characterName = "강은호",
        )

        assertThat(result.success).isTrue()
        assertThat(result.requestKey).isEqualTo("강은호")
        assertThat(result.endpoint).isEqualTo(ExternalApiEndpoint.OCID_LOOKUP)
        assertThat(result.payloadRef).isNotNull
        assertThat(result.payloadRef!!.artifactUri).isEqualTo(dummyRef.artifactUri)

        verify(clientPort).fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "강은호")
        verify(artifactStorePort).store(ExternalApiEndpoint.OCID_LOOKUP, "강은호", responseData)
    }

    @Test
    fun `fetchSingle - client failure returns error result`() {
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "없는캐릭"))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("API error")))

        val result = service.fetchSingle(
            provider = ExternalApiProvider.NEXON,
            endpoint = ExternalApiEndpoint.OCID_LOOKUP,
            requestKey = "없는캐릭",
        )

        assertThat(result.success).isFalse()
        assertThat(result.payloadRef).isNull()
        assertThat(result.errorMessage).contains("API error")
    }

    @Test
    fun `fetchBatch - processes all keys`() {
        val responseData = """{"ocid":"test-ocid"}""".toByteArray()
        whenever(clientPort.fetch(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(responseData))
        whenever(artifactStorePort.store(any(), any(), any()))
            .thenReturn(dummyRef)

        val results = service.fetchBatch(
            provider = ExternalApiProvider.NEXON,
            endpoint = ExternalApiEndpoint.OCID_LOOKUP,
            requestKeys = listOf("a", "b", "c"),
        )

        assertThat(results).hasSize(3)
        assertThat(results.all { it.success }).isTrue()
    }
}
