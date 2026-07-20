package maple.nexon.client.system

import java.util.concurrent.CompletableFuture
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.transport.NexonTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SystemKeyNexonClientTest {
    @Test
    fun `returns the transport raw-byte future unchanged`() {
        val transport = mock<NexonTransport>()
        val client = SystemKeyNexonClient(transport)
        val request = NexonRequest(
            purpose = NexonEndpointPurpose.ITEM_EQUIPMENT,
            path = "/maplestory/v1/character/item-equipment",
            query = mapOf("ocid" to "ocid-1"),
            endpointTemplate = "/maplestory/v1/character/item-equipment",
        )
        val completion = CompletableFuture.completedFuture("raw".toByteArray())
        whenever(transport.exchange(request, "system-key")).thenReturn(completion)

        val returned = client.fetch(request, "system-key")

        assertThat(returned).isSameAs(completion)
        verify(transport).exchange(request, "system-key")
    }
}
