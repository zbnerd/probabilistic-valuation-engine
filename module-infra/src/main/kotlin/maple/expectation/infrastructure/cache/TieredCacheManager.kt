package maple.expectation.infrastructure.cache

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import org.redisson.api.RedissonClient
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.cache.support.AbstractCacheManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.slf4j.LoggerFactory

/**
 * 2계층 캐시 매니저 (L1: Caffeine, L2: Redis)
 *
 * <h4>Issue #148: TieredCache에 분산 락 및 메트릭 지원 추가</h4>
 *
 * <ul>
 *   <li>RedissonClient: 분산 락 기반 Single-flight 패턴</li>
 *   <li>MeterRegistry: 캐시 히트/미스 메트릭 수집</li>
 * </ul>
 *
 * <h4>P1-6: @Setter 제거 → AtomicReference + CAS 패턴</h4>
 *
 * <ul>
 *   <li>mutable @Setter 제거하여 불변성 강화</li>
 *   <li>AtomicReference CAS로 중복 초기화 방지</li>
 *   <li>TieredCache에 Supplier 전달로 Lazy Resolution</li>
 * </ul>
 *
 * <h4>P2 Performance Fix: 인스턴스 풀링</h4>
 *
 * <ul>
 *   <li><b>문제</b>: getCache() 호출마다 새 TieredCache 인스턴스 생성</li>
 *   <li><b>해결</b>: ConcurrentHashMap으로 인스턴스 캐싱 (O(1) 조회)</li>
 *   <li><b>Green Agent 피드백 반영</b></li>
 * </ul>
 */
class TieredCacheManager(
    private val l1Manager: CacheManager,
    private val l2Manager: CacheManager,
    private val executor: LogicExecutor,
    private val redissonClient: RedissonClient, // Issue #148: 분산 락용
    val meterRegistry: MeterRegistry, // Issue #148: 메트릭 수집용 (public for access)
    private val lockWaitSeconds: Int // P0-4: 외부 설정
) : AbstractCacheManager() {
    companion object {
        private val log = LoggerFactory.getLogger(TieredCacheManager::class.java)
    }

    /** P2 FIX: TieredCache 인스턴스 풀 (동일 이름 캐시는 한 번만 생성) */
    private val cachePool: ConcurrentHashMap<String, Cache> = ConcurrentHashMap()

    /** P1-6: AtomicReference로 instanceId 관리 (CAS 중복 초기화 방지) */
    private val instanceIdRef: AtomicReference<String> = AtomicReference("unknown")

    /**
     * P1-6: AtomicReference로 invalidationCallback 관리 (CAS 중복 초기화 방지)
     *
     * <p>기본값: NO-OP Consumer (Feature Flag off 시 NPE 방지)
     */
    private val callbackRef: AtomicReference<Consumer<CacheInvalidationEvent>> =
        AtomicReference(Consumer {})

    override fun loadCaches(): Collection<out Cache> {
        return emptyList()
    }

    /**
     * 캐시 인스턴스 조회 (인스턴스 풀링 적용)
     *
     * <p><b>P2 Performance Fix:</b> ConcurrentHashMap.computeIfAbsent()로 O(1) 조회
     *
     * <p><b>스레드 안전성:</b> ConcurrentHashMap의 원자적 연산으로 동시성 보장
     *
     * @param name 캐시 이름
     * @return TieredCache 인스턴스 (재사용)
     */
    override fun getCache(name: String): Cache? {
        return cachePool.computeIfAbsent(name) { createTieredCache(it) }
    }

    /**
     * TieredCache 인스턴스 생성 (최초 1회만 호출됨)
     *
     * <h4>P1-6: Supplier 기반 Lazy Resolution</h4>
     *
     * <p>TieredCache 생성 시점에 instanceId/callback이 아직 초기화되지 않았을 수 있으므로 Supplier로 전달하여 실제 사용 시점에
     * AtomicReference에서 읽음
     */
    private fun createTieredCache(name: String): Cache {
        val l1 = l1Manager.getCache(name)
            ?: throw IllegalArgumentException("L1 cache not found: $name")
        val l2 = l2Manager.getCache(name)
            ?: throw IllegalArgumentException("L2 cache not found: $name")

        log.debug("[TieredCacheManager] Creating TieredCache instance: name={}", name)

        return TieredCache(
            l1 = l1,
            l2 = l2,
            executor = executor,
            redissonClient = redissonClient,
            meterRegistry = meterRegistry,
            lockWaitSeconds = lockWaitSeconds,
            instanceIdSupplier = { instanceIdRef.get() },
            callbackSupplier = { callbackRef.get() }
        )
    }

    /**
     * instanceId 초기화 (CAS - 중복 호출 방지)
     *
     * <h4>P1-6: @Setter 대체</h4>
     *
     * <p>"unknown"에서 한 번만 실제 값으로 전환 허용
     *
     * @param instanceId Scale-out Pub/Sub Self-skip용 인스턴스 ID
     * @return true if successfully initialized, false if already set
     */
    fun initializeInstanceId(instanceId: String): Boolean {
        val success = instanceIdRef.compareAndSet("unknown", instanceId)
        if (success) {
            log.info("[TieredCacheManager] instanceId initialized: {}", instanceId)
        } else {
            log.warn(
                "[TieredCacheManager] instanceId already initialized: current={}, attempted={}",
                instanceIdRef.get(),
                instanceId
            )
        }
        return success
    }

    /**
     * invalidationCallback 초기화 (원자적 교체)
     *
     * <h4>P1-6: @Setter 대체</h4>
     *
     * <p>NO-OP에서 실제 Publisher로 한 번 교체
     *
     * @param callback 캐시 무효화 이벤트 발행 콜백
     */
    fun initializeInvalidationCallback(callback: Consumer<CacheInvalidationEvent>) {
        callbackRef.set(callback)
        log.info("[TieredCacheManager] invalidationCallback initialized")
    }

    /**
     * L1 캐시 직접 접근 (Fast Path용) (#264)
     *
     * <h4>Issue #264: 캐시 히트 성능 최적화</h4>
     *
     * <ul>
     *   <li>L1(Caffeine) 캐시에서 직접 조회</li>
     *   <li>TieredCache/LogicExecutor 오버헤드 우회</li>
     *   <li>Executor 스레드 풀 경합 방지</li>
     * </ul>
     *
     * @param name 캐시 이름
     * @return L1 캐시 인스턴스 (Caffeine) - null 가능 (캐시 미등록 시)
     */
    fun getL1CacheDirect(name: String): Cache? {
        return l1Manager.getCache(name)
    }

    /**
     * 캐시에서 키 제거 (이벤트 핸들링용)
     *
     * @param cacheName 캐시 이름
     * @param key 제거할 키
     */
    fun evict(cacheName: String, key: Any) {
        getCache(cacheName)?.evict(key)
    }
}
