package maple.expectation.core.port.out.redis

import java.time.Duration

/**
 * Redis 작업을 추상화한 Port 인터페이스
 *
 * DIP(Dependency Inversion Principle)를 준수하여 비즈니스 로직이
 * Redis 구현체(RedissonClient)에 직접 의존하지 않도록 한다.
 *
 * @see ADR-012: Redis Operation Port Abstraction
 */
interface RedisOperationPort {

    // ===== Basic Operations =====

    fun <T : Any> get(key: String): T?
    fun <T : Any> set(key: String, value: T, ttl: Duration? = null)
    fun delete(key: String): Boolean
    fun exists(key: String): Boolean

    // ===== Hash Operations =====

    fun <T : Any> hGet(key: String, field: String): T?
    fun <T : Any> hGetAll(key: String): Map<String, T>
    fun hSet(key: String, field: String, value: Any)
    fun hSetAll(key: String, map: Map<String, Any>)
    fun hDelete(key: String, vararg fields: String): Long

    // ===== Set Operations =====

    fun <T : Any> sMembers(key: String): Set<T>
    fun sAdd(key: String, vararg values: Any): Long
    fun sRem(key: String, vararg values: Any): Long
    fun sIsMember(key: String, value: Any): Boolean

    // ===== List Operations =====

    fun <T : Any> lRange(key: String, start: Long, end: Long): List<T>
    fun lPush(key: String, vararg values: Any): Long
    fun rPush(key: String, vararg values: Any): Long
    fun lPop(key: String): Any?
    fun rPop(key: String): Any?

    // ===== Atomic Operations =====

    fun <T : Any> getAndSet(key: String, newValue: T): T?

    /** SET NX - 키가 존재하지 않을 때만 설정 */
    fun <T : Any> trySet(key: String, value: T, ttl: Duration? = null): Boolean

    fun increment(key: String, delta: Long = 1): Long
    fun decrement(key: String, delta: Long = 1): Long

    // ===== Lock Operations =====

    /**
     * 분산 락 획득 (지정된 leaseTime)
     *
     * @param key 락 키
     * @param waitTime 대기 시간
     * @param leaseTime 락 유지 시간
     * @return 락 획득 성공 여부
     */
    fun tryLock(key: String, waitTime: Duration, leaseTime: Duration): Boolean

    /**
     * 분산 락 획득 (Watchdog 모드 - 30초마다 자동 갱신)
     *
     * @param key 락 키
     * @param waitTime 대기 시간
     * @return 락 획득 성공 여부
     */
    fun tryLockWithWatchdog(key: String, waitTime: Duration): Boolean

    /**
     * 분산 락 해제
     *
     * @param key 락 키
     */
    fun unlock(key: String)

    /**
     * 락이 현재 스레드에 의해 보유되어 있는지 확인
     *
     * @param key 락 키
     * @return 현재 스레드가 락을 보유 중인지 여부
     */
    fun isHeldByCurrentThread(key: String): Boolean

    /**
     * 락이 보유되어 있는지 확인 (모든 스레드)
     *
     * @param key 락 키
     * @return 락 보유 여부
     */
    fun isLocked(key: String): Boolean

    // ===== Pub/Sub Operations =====

    fun publish(topic: String, message: Any): Long
    fun subscribe(topic: String, consumer: (message: Any) -> Unit)

    // ===== Script Operations =====

    fun <T : Any> executeScript(script: String, keys: List<String>, args: List<Any>): T
    fun <T : Any> executeScriptBySha(sha: String, keys: List<String>, args: List<Any>): T

    // ===== TTL Operations =====

    fun expire(key: String, ttl: Duration): Boolean
    fun getTtl(key: String): Duration?
    fun persist(key: String): Boolean

    // ===== Batch Operations =====

    fun <T : Any> multiGet(keys: Collection<String>): Map<String, T>
    fun <T : Any> multiSet(map: Map<String, T>)
    fun multiDelete(keys: Collection<String>): Long

    // ===== Sorted Set Operations =====

    fun zAdd(key: String, member: Any, score: Double): Boolean
    fun <T : Any> zRange(key: String, start: Long, end: Long): List<T>
    fun <T : Any> zRevRange(key: String, start: Long, end: Long): List<T>
    fun zScore(key: String, member: Any): Double?
    fun zCard(key: String): Long

    // ===== BitMap Operations =====

    fun setBit(key: String, offset: Long, value: Boolean): Boolean
    fun getBit(key: String, offset: Long): Boolean
    fun bitCount(key: String): Long
}
