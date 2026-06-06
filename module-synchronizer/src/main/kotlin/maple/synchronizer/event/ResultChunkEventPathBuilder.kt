package maple.synchronizer.event

import org.springframework.stereotype.Component

@Component
class ResultChunkEventPathBuilder {
    fun sourceObjectKey(runId: String, sourceEndpoint: String, chunkId: String): String =
        "runs/$runId/$sourceEndpoint/chunks/$chunkId.jsonl.gz"
}
