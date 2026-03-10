package maple.expectation.infrastructure.provider

import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.aop.annotation.NexonDataCache
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

/**
 * 장비 데이터 Fetch Provider (캐시 적용)
 *
 * ## ADR: .join() 유지 결정 (Issue #118)
 *
 * ### Context
 *
 * Issue #118에서 비동기 파이프라인 전환을 진행하며 모든 .join() 제거를 검토함.
 *
 * ### Decision
 *
 * 이 클래스의 .join()은 **의도적으로 유지**합니다.
 *
 * ### Rationale
 *
 * - Spring @Cacheable은 `CompletableFuture<T>` 반환 타입을 지원하지 않음
 * - @Cacheable(sync=true)는 Cache Stampede 방지용이며, 비동기 지원과 무관
 * - 캐시 프록시가 실제 값을 저장하려면 동기 반환이 필수
 * - 호출자(EquipmentCacheService)가 이미 비동기 래퍼를 제공하므로 전체 파이프라인은 논블로킹
 *
 * ### Consequences
 *
 * - 캐시 MISS 시 이 지점에서 일시적 블로킹 발생 (예상: 100~300ms)
 * - 캐시 HIT 시 블로킹 없음 (Caffeine L1 또는 Redis L2에서 즉시 반환)
 * - 전용 Executor(expectationComputeExecutor)에서 실행되어 톰캣 스레드 영향 없음
 *
 * ### Alternatives Considered
 *
 * - Option A: @Cacheable 제거 후 수동 캐싱 → 코드 복잡도 증가, 캐시 일관성 관리 어려움
 * - Option B: Reactor/Mono 전환 → 전체 스택 변경 필요, 과도한 변경
 * - Option C: 현상 유지 (선택) → 실용적, 성능 영향 미미
 *
 * @see maple.expectation.service.v2.cache.EquipmentCacheService
 */
@Component
class EquipmentFetchProvider(
    private val nexonApiClient: NexonApiClient,
) {
    /**
     * 캐시 적용 장비 데이터 조회
     *
     * **⚠️ ADR: .join() 의도적 유지**
     *
     * Spring @Cacheable이 CompletableFuture를 지원하지 않아 동기 반환 필수. 상세 사유는 클래스 Javadoc 참조.
     *
     * @param ocid 캐릭터 OCID
     * @return 장비 응답 (캐시 HIT 시 즉시 반환, MISS 시 API 호출)
     */
    @NexonDataCache
    @Cacheable(value = ["equipment"], key = "#ocid")
    fun fetchWithCache(ocid: String): EquipmentResponse = nexonApiClient
        .getItemDataByOcid(ocid)
        .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join()

    private companion object {
        private const val API_TIMEOUT_SECONDS = 10L
    }
}
