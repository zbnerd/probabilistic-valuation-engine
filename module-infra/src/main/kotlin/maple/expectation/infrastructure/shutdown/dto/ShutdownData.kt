package maple.expectation.infrastructure.shutdown.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ShutdownData(
    val timestamp: LocalDateTime,
    val instanceId: String,
    val likeBuffer: Map<String, Long>?,
    val equipmentPending: List<String>?
) {
    companion object {
        @JvmStatic
        fun empty(instanceId: String) = ShutdownData(
            LocalDateTime.now(),
            instanceId,
            emptyMap(),
            emptyList()
        )
    }

    @JsonIgnore
    fun isEmpty() = (likeBuffer == null || likeBuffer.isEmpty()) &&
                     (equipmentPending == null || equipmentPending.isEmpty())

    @JsonIgnore
    fun getTotalItems(): Int {
        val likeCount = likeBuffer?.size ?: 0
        val equipmentCount = equipmentPending?.size ?: 0
        return likeCount + equipmentCount
    }
}
