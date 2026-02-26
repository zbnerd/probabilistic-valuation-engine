package maple.expectation.infrastructure.shutdown.dto

import com.fasterxml.jackson.annotation.JsonIgnore

data class FlushResult(
    val redisSuccessCount: Int,
    val fileBackupCount: Int
) {
    companion object {
        @JvmStatic
        fun empty() = FlushResult(0, 0)
        @JvmStatic
        fun success(count: Int) = FlushResult(count, 0)
    }

    @JsonIgnore
    fun hasFailures() = fileBackupCount > 0

    @JsonIgnore
    fun isFullSuccess() = fileBackupCount == 0 && redisSuccessCount > 0

    @JsonIgnore
    fun totalCount() = redisSuccessCount + fileBackupCount

    @JsonIgnore
    fun successRate(): Double {
        val total = totalCount()
        return if (total == 0) 1.0 else redisSuccessCount.toDouble() / total
    }
}
