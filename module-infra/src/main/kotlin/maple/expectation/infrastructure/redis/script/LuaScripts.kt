package maple.expectation.infrastructure.redis.script

object LuaScripts {
    private val constructor: (Nothing) -> Nothing = { throw UnsupportedOperationException("Utility class") }

    object Keys {
        const val HASH = "{buffer:likes}"
        const val TOTAL_COUNT = "{buffer:likes}:total_count"
        const val SYNC_PREFIX = "{buffer:likes}:sync:"
    }

    const val ATOMIC_TRANSFER = """
            redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
            redis.call('INCRBY', KEYS[2], ARGV[2])
            return 1
            """

    const val ATOMIC_DELETE_AND_DECREMENT = """
            local deleted = redis.call('HDEL', KEYS[1], ARGV[1])
            if deleted > 0 then
                redis.call('DECRBY', KEYS[2], ARGV[2])
            end
            return deleted
            """

    const val ATOMIC_COMPENSATION = """
            redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
            redis.call('HDEL', KEYS[2], ARGV[1])
            return 1
            """
}
