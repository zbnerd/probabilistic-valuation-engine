package maple.expectation.infrastructure.batch

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import maple.expectation.core.port.out.EquipmentFanOutPort
import maple.expectation.infrastructure.config.AdaptiveMicroBatchProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.fanout.NexonFanOutBatchLoader
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import org.springframework.cache.Cache
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.stereotype.Service

/**
 * Nexon Equipment Micro-Batch Adapter
 *
 * <h3>역할</h3>
 * <p>AdaptiveMicroBatchUserService를 활용한 장비 데이터 조회 어댑터.
 * Request Coalescing, Adaptive Routing, Batch Collection, Dedup을 자동 처리.
 *
 * <h3>Routing Logic (AdaptiveMicroBatchUserService)</h3>
 * <ul>
 *   <li>Fast Lane: Semaphore 여유 시 EquipmentFetchProvider.fetchWithCache(ocid) → @Cacheable 경로</li>
 *   <li>Batch Lane: Channel → 10ms window → NexonFanOutBatchLoader.load(ocids) → 병렬 실행</li>
 * </ul>
 *
 * <h3>Cache</h3>
 * <p>Caffeine "equipment" 캐시를 공유:
 * <ul>
 *   <li>Fast Lane: @Cacheable이 자동으로 put</li>
 *   <li>Batch Lane: AdaptiveMicroBatchUserService가 cache.put() 호출</li>
 * </ul>
 *
 * @see AdaptiveMicroBatchUserService 핵심 코어스칸 엔진
 * @see NexonFanOutBatchLoader Batch Lane 병렬 실행기
 * @see EquipmentFetchProvider Fast Lane @Cacheable 경로
 */
@Service
class NexonEquipmentMicroBatchAdapter(
    properties: AdaptiveMicroBatchProperties,
    logicExecutor: LogicExecutor,
    meterRegistry: MeterRegistry,
    cacheManager: org.springframework.cache.CacheManager,
    private val fetchProvider: EquipmentFetchProvider,
    private val batchLoader: NexonFanOutBatchLoader,
) : EquipmentFanOutPort {
    private val cache: Cache = cacheManager.getCache("equipment")
        ?: ConcurrentMapCache("equipment")

    private val delegate = AdaptiveMicroBatchUserService<EquipmentResponse>(
        properties = properties,
        logicExecutor = logicExecutor,
        meterRegistry = meterRegistry,
        cache = cache,
        singleLoader = { key -> fetchSingle(key) },
        batchLoader = { keys -> batchLoader.load(keys) },
    )

    /**
     * Batch Lane 코루틴 워커 시작
     *
     * <p>AdaptiveMicroBatchUserService는 Spring Bean이 아니므로 @PostConstruct가 자동 실행되지 않음.
     * 따라서 이 어댑터의 @PostConstruct에서 명시적으로 호출 필요.
     * (기존 GameCharacterMicroBatchAdapter, L2CacheMicroBatchAdapter에도 동일한 이슈 존재)
     */
    @PostConstruct
    fun init() {
        delegate.startBatchWorker()
    }

    /**
     * OCID로 장비 데이터 조회 (Adaptive Micro-Batch)
     *
     * <p>내부 흐름:
     * <ol>
     *   <li>L1 Cache HIT → 즉시 반환</li>
     *   <li>In-Flight Coalescing → 기존 요청 대기</li>
     *   <li>Fast Lane → EquipmentFetchProvider.fetchWithCache()</li>
     *   <li>Batch Lane → NexonFanOutBatchLoader.load()</li>
     * </ol>
     *
     * @param ocid 캐릭터 OCID
     * @return 장비 응답 (캐시 미스 + API 실패 시 null)
     */
    fun getByKey(ocid: String): EquipmentResponse? = delegate.getByKey(ocid)

    override fun preFetchByOcid(ocid: String): Boolean = delegate.getByKey(ocid) != null

    private fun fetchSingle(ocid: String): EquipmentResponse? = fetchProvider.fetchWithCache(ocid)
}
