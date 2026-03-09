package maple.expectation.infrastructure.cache.per

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * X-Fetch (Probabilistic Early Recomputation) 어노테이션 (#219)
 *
 * <h3>Cache Stampede 방지</h3>
 *
 * <p>Lock 대기 없이 확률적으로 백그라운드 갱신을 트리거하여 Cache Stampede를 방지.
 *
 * <h4>알고리즘 (X-Fetch)</h4>
 *
 * <pre>
 * if (-log(random) * beta * delta >= (expiry - now)) {
 *     triggerBackgroundRefresh();
 * }
 * return staleData; // Non-Blocking
 * </pre>
 *
 * <h4>파라미터 설명</h4>
 *
 * <ul>
 *   <li>{@code beta}: 재계산 민감도 (1.0 = 표준, 높을수록 빨리 갱신)</li>
 *   <li>{@code delta}: 원본 계산 소요 시간 (자동 측정됨)</li>
 *   <li>{@code expiry}: 캐시 만료 시각</li>
 * </ul>
 *
 * <h4>사용 예시</h4>
 *
 * <pre>{@code
 * @ProbabilisticCache(cacheName = "equipment", key = "#ocid", ttlSeconds = 300, beta = 1.0)
 * fun fetchEquipment(ocid: String): EquipmentData {
 *     return nexonApi.getEquipment(ocid)
 * }
 * }</pre>
 *
 * @see ProbabilisticCacheAspect
 * @see CachedWrapper
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
annotation class ProbabilisticCache(
    /** 캐시 이름 (Redis key prefix) */
    val cacheName: String,

    /**
     * 캐시 키 SpEL 표현식
     *
     * <p>예: {@code "#ocid"}, {@code "#request.id"}
     */
    val key: String = "",

    /** TTL (초 단위) */
    val ttlSeconds: Long = 300,

    /**
     * Beta 파라미터 (재계산 민감도)
     *
     * <ul>
     *   <li>0.5: 덜 공격적 (만료 직전에만 갱신)</li>
     *   <li>1.0: 표준 (권장)</li>
     *   <li>2.0: 공격적 (TTL 중반부터 갱신 확률 증가)</li>
     * </ul>
     */
    val beta: Double = 1.0,
)
