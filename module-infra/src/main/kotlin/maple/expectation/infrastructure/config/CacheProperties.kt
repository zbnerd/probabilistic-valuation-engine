package maple.expectation.infrastructure.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * TieredCache 외부 설정 프로퍼티 (P1-2, P1-5, P0-4)
 *
 * <h4>설계 의도</h4>
 *
 * <ul>
 *   <li>P1-2: CacheConfig TTL/Size 하드코딩 제거 → YAML 외부화
 *   <li>P1-5: Lock timeout 하드코딩 제거 → YAML 외부화
 *   <li>P0-4: lockWaitSeconds 30초 → 5초 (cold cache burst 스레드 고갈 방지)
 * </ul>
 *
 * <h4>NexonApiProperties 패턴 참조</h4>
 *
 * <p>{@code @ConfigurationProperties} + {@code @Validated}로 타입 안전 바인딩
 *
 * @see CacheConfig
 */
@Validated
@ConfigurationProperties(prefix = "cache")
data class CacheProperties(
    /**
     * 캐시별 L1/L2 스펙 설정
     *
     * <p>key: 캐시 이름 (equipment, cubeTrials, ocidCache, characterBasic, expectationV4)
     */
    @field:NotNull @field:Valid
    var specs: Map<String, CacheSpec> = mapOf(),

    /** Singleflight (분산 락) 설정 */
    @field:NotNull @field:Valid
    var singleflight: Singleflight = Singleflight()
) {
    /**
     * 캐시별 L1/L2 스펙
     *
     * <ul>
     *   <li>l1TtlMinutes: L1(Caffeine) TTL (분)
     *   <li>l1MaxSize: L1 최대 엔트리 수
     *   <li>l2TtlMinutes: L2(Redis) TTL (분)
     *   <li>l2Serializer: L2 직렬화 방식 (json | jdk)
     * </ul>
     */
    data class CacheSpec(
        @Min(1) @Max(1440)
        var l1TtlMinutes: Int = 10,

        @Min(100) @Max(100000)
        var l1MaxSize: Int = 5000,

        @Min(1) @Max(1440)
        var l2TtlMinutes: Int = 15,

        @field:NotNull
        var l2Serializer: String = "json"
    )

    /**
     * Singleflight (분산 락) 설정
     *
     * <h4>P0-4 Fix: lockWaitSeconds 기본값 5초</h4>
     *
     * <p>캐시 valueLoader는 보통 100-500ms. 5초면 충분하고, 5초 초과 시 fallback 직접 실행이 합리적.
     *
     * <p>기존 30초 → 5초로 변경하여 cold cache burst 시 스레드 고갈 방지
     */
    data class Singleflight(
        @Min(1) @Max(60)
        var lockWaitSeconds: Int = 5
    )
}
