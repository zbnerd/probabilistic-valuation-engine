package maple.expectation.infrastructure.ratelimit.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Rate Limiting 설정 프로퍼티 (Issue #152)
 *
 * <p>Bucket4j 기반 분산 Rate Limiting 설정
 *
 * <h4>5-Agent Council 합의</h4>
 *
 * <ul>
 *   <li><b>Blue Agent</b>: @ConfigurationProperties + @Validated 패턴
 *   <li><b>Red Agent</b>: fail-fast 원칙 - 필수값 검증
 *   <li><b>Purple Agent</b>: Admin 바이패스 + IP 스푸핑 방지
 * </ul>
 *
 * <p>CLAUDE.md 섹션 5 준수: No Hardcoding - 모든 값은 설정 파일로 관리
 */
@Validated
@ConfigurationProperties(prefix = "ratelimit")
data class RateLimitProperties(
    /**
     * Rate Limiting 활성화 여부 (기본: true)
     *
     * <p>운영 중 긴급 비활성화가 필요할 경우 사용
     */
    @get:NotNull
    var enabled: Boolean = true,

    /**
     * Redis 장애 시 동작 (기본: fail-open)
     *
     * <ul>
     *   <li>fail-open: Redis 장애 시 요청 허용 (가용성 > 보안)
     *   <li>fail-close: Redis 장애 시 요청 거부 (보안 > 가용성)
     * </ul>
     */
    @get:NotBlank
    var failureMode: String = "fail-open",

    /**
     * Rate Limit 키 접두사 (Redis Cluster Hash Tag 포함)
     *
     * <p>CLAUDE.md 섹션 8-1 준수: Cluster Hash Tag로 동일 슬롯 보장
     */
    @get:NotBlank
    var keyPrefix: String = "{ratelimit}",

    /** IP 기반 Rate Limiting 설정 */
    @get:NotNull
    var ip: IpLimitConfig = IpLimitConfig(),

    /** User 기반 Rate Limiting 설정 */
    @get:NotNull
    var user: UserLimitConfig = UserLimitConfig(),

    /**
     * 신뢰할 수 있는 프록시 헤더 (순서대로 확인)
     *
     * <p>Purple Agent P1 FIX: IP 스푸핑 방지 - 신뢰할 수 있는 프록시에서만 X-Forwarded-For 사용
     */
    var trustedHeaders: List<String> = listOf("X-Forwarded-For", "X-Real-IP"),

    /** Rate Limit 바이패스 대상 경로 (Swagger, Actuator 등) */
    var bypassPaths: List<String> = listOf("/swagger-ui/**", "/v3/api-docs/**", "/actuator/health", "/actuator/info")
) {
    /** fail-open 모드인지 확인 */
    fun isFailOpen(): Boolean {
        return "fail-open".equals(failureMode, ignoreCase = true)
    }

    /**
     * IP 기반 Rate Limiting 설정
     */
    data class IpLimitConfig(
        /** 활성화 여부 (기본: true) */
        @get:NotNull
        var enabled: Boolean = true,

        /** 시간 창 당 허용 요청 수 (기본: 100) */
        @get:Min(1)
        var capacity: Int = 100,

        /** 시간 창 크기 (기본: 1분) */
        @get:NotNull
        var window: Duration = Duration.ofMinutes(1),

        /**
         * 토큰 리필 주기당 리필 개수 (기본: 10)
         *
         * <p>Greedy Refill: window 동안 capacity까지 균등 리필
         */
        @get:Min(1)
        var refillTokens: Int = 10,

        /**
         * 토큰 리필 주기 (기본: 6초)
         *
         * <p>계산: window / (capacity / refillTokens) = 60s / 10 = 6s
         */
        @get:NotNull
        var refillPeriod: Duration = Duration.ofSeconds(6)
    )

    /**
     * User 기반 Rate Limiting 설정
     */
    data class UserLimitConfig(
        /** 활성화 여부 (기본: true) */
        @get:NotNull
        var enabled: Boolean = true,

        /**
         * 시간 창 당 허용 요청 수 (기본: 200)
         *
         * <p>인증된 사용자는 IP보다 높은 한도 적용
         */
        @get:Min(1)
        var capacity: Int = 200,

        /** 시간 창 크기 (기본: 1분) */
        @get:NotNull
        var window: Duration = Duration.ofMinutes(1),

        /** 토큰 리필 주기당 리필 개수 (기본: 20) */
        @get:Min(1)
        var refillTokens: Int = 20,

        /** 토큰 리필 주기 (기본: 6초) */
        @get:NotNull
        var refillPeriod: Duration = Duration.ofSeconds(6)
    )
}
