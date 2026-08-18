package maple.externalapi.infra.nexon

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.nexon.client.failure.RateLimited
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.system.SystemKeyNexonClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NexonExternalApiClientCharacterizationTest {
    private val systemClient = mock<SystemKeyNexonClient>()
    private val metrics = mock<SnapshotFetchMetrics>()
    private val adapter = NexonExternalApiClientAdapter(
        apiKey = SYSTEM_KEY,
        systemClient = systemClient,
        fetchMetrics = metrics,
        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
    )

    @Test
    fun `maps every system endpoint and query without changing raw bytes`() {
        val completion = CompletableFuture.completedFuture(RESPONSE)
        whenever(systemClient.fetch(any(), eq(SYSTEM_KEY))).thenReturn(completion)

        val ocid = adapter.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "진격캐넌")
        val basic = adapter.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.CHARACTER_BASIC, "ocid-1")
        val equipment = adapter.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.ITEM_EQUIPMENT, "ocid-2")
        val ranking = adapter.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-07-20:17")

        assertThat(listOf(ocid, basic, equipment, ranking)).allMatch { it === completion }
        assertThat(completion.resultNow()).containsExactly(*RESPONSE)
        val requests = argumentCaptor<NexonRequest>()
        verify(systemClient, org.mockito.kotlin.times(4)).fetch(requests.capture(), eq(SYSTEM_KEY))
        assertThat(requests.allValues).containsExactly(
            NexonRequest(
                NexonEndpointPurpose.OCID_LOOKUP,
                "/maplestory/v1/id",
                mapOf("character_name" to "진격캐넌"),
                "/maplestory/v1/id",
            ),
            NexonRequest(
                NexonEndpointPurpose.CHARACTER_BASIC,
                "/maplestory/v1/character/basic",
                mapOf("ocid" to "ocid-1"),
                "/maplestory/v1/character/basic",
            ),
            NexonRequest(
                NexonEndpointPurpose.ITEM_EQUIPMENT,
                "/maplestory/v1/character/item-equipment",
                mapOf("ocid" to "ocid-2"),
                "/maplestory/v1/character/item-equipment",
            ),
            NexonRequest(
                NexonEndpointPurpose.RANKING_OVERALL,
                "/maplestory/v1/ranking/overall",
                linkedMapOf("date" to "2026-07-20", "page" to "17"),
                "/maplestory/v1/ranking/overall",
            ),
        )
        ExternalApiEndpoint.entries.forEach { endpoint ->
            verify(metrics).recordNexonBodyReceived(eq(endpoint.name), any(), eq(RESPONSE.size))
        }
    }

    @Test
    fun `typed failure future is propagated unchanged and observed without raw data`() {
        val request = NexonRequest(
            NexonEndpointPurpose.OCID_LOOKUP,
            "/maplestory/v1/id",
            mapOf("character_name" to "SensitiveName"),
            "/maplestory/v1/id",
        )
        val failure = RateLimited(request, 429, "OPENAPI00007", null)
        val completion = CompletableFuture.failedFuture<ByteArray>(failure)
        whenever(systemClient.fetch(any(), eq(SYSTEM_KEY))).thenReturn(completion)

        val returned = adapter.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "SensitiveName")

        assertThat(returned).isSameAs(completion)
        assertThat(returned.exceptionNow()).isSameAs(failure)
        verify(metrics).recordNexonFailure(eq("OCID_LOOKUP"), any())
    }

    private companion object {
        private const val SYSTEM_KEY = "synthetic-system-key"
        private val RESPONSE = "{\"ocid\":\"raw-value\"}".toByteArray()
    }
}
