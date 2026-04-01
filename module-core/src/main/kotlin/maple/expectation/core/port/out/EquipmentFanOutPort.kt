package maple.expectation.core.port.out

/**
 * Equipment FanOut Port (DIP - Hexagonal Architecture)
 *
 * <h3>역할</h3>
 * <p>Micro-Batch Coalescing을 통한 장비 데이터 프리페치.
 * V5 Controller에서 PostgreSQL 캐시 미스 시 장비 데이터를 미리 캐시에 적재.
 *
 * <h3>작동</h3>
 * <ul>
 *   <li>L1 Cache HIT → 즉시 반환 (true)
 *   <li>In-Flight Coalescing → 기존 요청 대기 후 결과 반환
 *   <li>Fast Lane → EquipmentFetchProvider.fetchWithCache() (@Cacheable)
 *   <li>Batch Lane → NexonFanOutBatchLoader.load() (병렬 실행)
 * </ul>
 *
 * @see maple.expectation.infrastructure.batch.NexonEquipmentMicroBatchAdapter
 */
interface EquipmentFanOutPort {

    /**
     * OCID로 장비 데이터 프리페치 (Micro-Batch Coalescing)
     *
     * @param ocid 캐릭터 OCID
     * @return 캐시 적재 성공 여부
     */
    fun preFetchByOcid(ocid: String): Boolean
}
