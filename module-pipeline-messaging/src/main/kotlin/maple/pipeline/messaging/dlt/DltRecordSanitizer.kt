package maple.pipeline.messaging.dlt

import java.util.Collections
import maple.pipeline.messaging.contract.DeliveryContext

fun interface DltRecordSanitizer {
    fun sanitize(key: String?, value: String, context: DeliveryContext): DltPayload

    data object PassThrough : DltRecordSanitizer {
        override fun sanitize(key: String?, value: String, context: DeliveryContext): DltPayload =
            DltPayload(key, value, emptyMap())
    }
}

class DltPayload(
    val key: String?,
    val value: String,
    extraHeaders: Map<String, ByteArray>,
) {
    val extraHeaders: Map<String, ByteArray> = Collections.unmodifiableMap(
        LinkedHashMap<String, ByteArray>(extraHeaders.size).apply {
            extraHeaders.forEach { (name, bytes) ->
                require(name.startsWith(SAFE_HEADER_PREFIX)) {
                    "DLT extra header must use $SAFE_HEADER_PREFIX"
                }
                require(bytes.size <= MAX_SAFE_HEADER_BYTES) {
                    "DLT extra header exceeds $MAX_SAFE_HEADER_BYTES bytes"
                }
                put(name, bytes.copyOf())
            }
        },
    )

    companion object {
        const val SAFE_HEADER_PREFIX = "x-pipeline-safe-"
        const val MAX_SAFE_HEADER_BYTES = 1024
    }
}
