package maple.expectation.infrastructure.redis.script

interface LikeAtomicOperations {
    val MAX_INCREMENT_PER_OPERATION: Long
        get() = 10_000L

    fun atomicTransfer(userIgn: String, count: Long): Boolean

    fun atomicDeleteAndDecrement(tempKey: String, userIgn: String, count: Long): Long

    fun atomicCompensation(tempKey: String, userIgn: String, count: Long): Boolean
}
