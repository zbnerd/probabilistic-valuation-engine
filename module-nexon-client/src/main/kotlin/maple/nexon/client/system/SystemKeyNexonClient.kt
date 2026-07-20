package maple.nexon.client.system

import java.util.concurrent.CompletableFuture
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.transport.NexonTransport

class SystemKeyNexonClient(
    private val transport: NexonTransport,
) {
    fun fetch(request: NexonRequest, systemApiKey: String): CompletableFuture<ByteArray> = transport.exchange(request, systemApiKey)
}
