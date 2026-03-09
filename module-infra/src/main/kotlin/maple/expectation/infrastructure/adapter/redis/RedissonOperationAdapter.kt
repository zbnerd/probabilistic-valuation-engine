package maple.expectation.infrastructure.adapter.redis

import java.time.Duration
import java.util.concurrent.TimeUnit
import maple.expectation.core.port.out.redis.RedisOperationPort
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.springframework.stereotype.Component

@Component
class RedissonOperationAdapter(
    private val redissonClient: RedissonClient,
) : RedisOperationPort {

    // ===== Basic Operations =====

    override fun <T : Any> get(key: String): T? {
        val bucket = redissonClient.getBucket<Any>(key)
        @Suppress("UNCHECKED_CAST")
        return bucket.get() as? T
    }

    override fun <T : Any> set(key: String, value: T, ttl: Duration?) {
        val bucket = redissonClient.getBucket<Any>(key)
        if (ttl != null) {
            bucket.set(value, ttl.toMillis(), TimeUnit.MILLISECONDS)
        } else {
            bucket.set(value)
        }
    }

    override fun delete(key: String): Boolean = redissonClient.getKeys().delete(key) > 0
    override fun exists(key: String): Boolean = redissonClient.getKeys().countExists(key) > 0

    // ===== Hash Operations =====

    override fun <T : Any> hGet(key: String, field: String): T? {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getMap<String, Any>(key)[field] as? T
    }

    override fun <T : Any> hGetAll(key: String): Map<String, T> {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getMap<String, Any>(key).entries.associate { it.key to (it.value as T) }
    }

    override fun hSet(key: String, field: String, value: Any) {
        redissonClient.getMap<String, Any>(key)[field] = value
    }

    override fun hSetAll(key: String, map: Map<String, Any>) {
        redissonClient.getMap<String, Any>(key).putAll(map)
    }

    override fun hDelete(key: String, vararg fields: String): Long = redissonClient.getMap<String, Any>(key).fastRemove(*fields)

    // ===== Set Operations =====

    override fun <T : Any> sMembers(key: String): Set<T> {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getSet<Any>(key).map { it as T }.toSet()
    }

    override fun sAdd(key: String, vararg values: Any): Long {
        var count = 0L
        val set = redissonClient.getSet<Any>(key)
        for (v in values) {
            if (set.add(v)) count++
        }
        return count
    }

    override fun sRem(key: String, vararg values: Any): Long {
        var count = 0L
        val set = redissonClient.getSet<Any>(key)
        for (v in values) {
            if (set.remove(v)) count++
        }
        return count
    }

    override fun sIsMember(key: String, value: Any): Boolean = redissonClient.getSet<Any>(key).contains(value)

    // ===== List Operations =====

    override fun <T : Any> lRange(key: String, start: Long, end: Long): List<T> {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getList<Any>(key).range(start.toInt(), end.toInt()).map { it as T }
    }

    override fun lPush(key: String, vararg values: Any): Long {
        val list = redissonClient.getList<Any>(key)
        for (v in values.reversed()) list.add(0, v)
        return list.size.toLong()
    }

    override fun rPush(key: String, vararg values: Any): Long {
        val list = redissonClient.getList<Any>(key)
        list.addAll(values.toList())
        return list.size.toLong()
    }

    override fun lPop(key: String): Any? {
        val list = redissonClient.getList<Any>(key)
        return if (list.isNotEmpty()) list.removeAt(0) else null
    }

    override fun rPop(key: String): Any? {
        val list = redissonClient.getList<Any>(key)
        return if (list.isNotEmpty()) list.removeAt(list.size - 1) else null
    }

    // ===== Atomic Operations =====

    override fun <T : Any> getAndSet(key: String, newValue: T): T? {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getBucket<Any>(key).getAndSet(newValue) as? T
    }

    override fun <T : Any> trySet(key: String, value: T, ttl: Duration?): Boolean {
        val bucket = redissonClient.getBucket<Any>(key)
        return if (ttl != null) {
            bucket.trySet(value, ttl.toMillis(), TimeUnit.MILLISECONDS)
        } else {
            bucket.trySet(value)
        }
    }

    override fun increment(key: String, delta: Long): Long = redissonClient.getAtomicLong(key).addAndGet(delta)

    override fun decrement(key: String, delta: Long): Long = redissonClient.getAtomicLong(key).addAndGet(-delta)

    // ===== Lock Operations =====

    override fun tryLock(key: String, waitTime: Duration, leaseTime: Duration): Boolean = redissonClient.getLock(key).tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS)

    override fun tryLockWithWatchdog(key: String, waitTime: Duration): Boolean = redissonClient.getLock(key).tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)

    override fun unlock(key: String) = redissonClient.getLock(key).unlock()

    override fun isHeldByCurrentThread(key: String): Boolean = redissonClient.getLock(key).isHeldByCurrentThread

    override fun isLocked(key: String): Boolean = redissonClient.getLock(key).isLocked

    // ===== Pub/Sub Operations =====

    override fun publish(topic: String, message: Any): Long = redissonClient.getTopic(topic).publish(message)

    override fun subscribe(topic: String, consumer: (message: Any) -> Unit) {
        redissonClient.getTopic(topic).addListener(Any::class.java) { _, msg -> consumer(msg) }
    }

    // ===== Script Operations =====

    override fun <T : Any> executeScript(script: String, keys: List<String>, args: List<Any>): T {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getScript(StringCodec.INSTANCE).eval(
            org.redisson.api.RScript.Mode.READ_WRITE,
            script,
            org.redisson.api.RScript.ReturnType.VALUE,
            keys,
            *args.toTypedArray(),
        ) as T
    }

    override fun <T : Any> executeScriptBySha(sha: String, keys: List<String>, args: List<Any>): T {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getScript(StringCodec.INSTANCE).evalSha(
            org.redisson.api.RScript.Mode.READ_WRITE,
            sha,
            org.redisson.api.RScript.ReturnType.VALUE,
            keys,
            *args.toTypedArray(),
        ) as T
    }

    // ===== TTL Operations =====

    override fun expire(key: String, ttl: Duration): Boolean = redissonClient.getBucket<Any>(key).expire(ttl.toMillis(), TimeUnit.MILLISECONDS)

    override fun getTtl(key: String): Duration? {
        val remainTime = redissonClient.getBucket<Any>(key).remainTimeToLive()
        return if (remainTime > 0) Duration.ofMillis(remainTime) else null
    }

    override fun persist(key: String): Boolean = redissonClient.getBucket<Any>(key).clearExpire()

    // ===== Batch Operations =====

    override fun <T : Any> multiGet(keys: Collection<String>): Map<String, T> {
        val result = mutableMapOf<String, T>()
        for (key in keys) get<T>(key)?.let { result[key] = it }
        return result
    }

    override fun <T : Any> multiSet(map: Map<String, T>) {
        for ((key, value) in map) set(key, value)
    }

    override fun multiDelete(keys: Collection<String>): Long = redissonClient.getKeys().delete(*keys.toTypedArray())

    // ===== Sorted Set Operations =====

    override fun zAdd(key: String, member: Any, score: Double): Boolean = redissonClient.getScoredSortedSet<Any>(key).add(score, member)

    override fun <T : Any> zRange(key: String, start: Long, end: Long): List<T> {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getScoredSortedSet<Any>(key).valueRange(start.toInt(), end.toInt()).map { it as T }
    }

    override fun <T : Any> zRevRange(key: String, start: Long, end: Long): List<T> {
        @Suppress("UNCHECKED_CAST")
        return redissonClient.getScoredSortedSet<Any>(key).valueRangeReversed(start.toInt(), end.toInt()).map { it as T }
    }

    override fun zScore(key: String, member: Any): Double? = redissonClient.getScoredSortedSet<Any>(key).getScore(member)

    override fun zCard(key: String): Long = redissonClient.getScoredSortedSet<Any>(key).size().toLong()

    // ===== BitMap Operations =====

    override fun setBit(key: String, offset: Long, value: Boolean): Boolean {
        val bitSet = redissonClient.getBitSet(key)
        val previous = bitSet[offset]
        if (value) bitSet.set(offset) else bitSet.clear(offset)
        return previous
    }

    override fun getBit(key: String, offset: Long): Boolean = redissonClient.getBitSet(key)[offset]
    override fun bitCount(key: String): Long = redissonClient.getBitSet(key).cardinality()
}
