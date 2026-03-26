package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Nexon API 클라이언트 타임아웃 설정 프로퍼티
 *
 * application.yml에서 다음과 같이 설정:
 *
 * ```kotlin
 * nexon:
 *   api:
 *     connect-timeout: 3s
 *     response-timeout: 5s
 *     cache-follower-timeout-seconds: 32
 *     latch-initial-ttl-seconds: 60
 *     latch-finalize-ttl-seconds: 10
 * ```
 *
 * ## 타임아웃 계층 설계 (상한 보장)
 *
 * ```text
 * ┌─────────────────────────────────────────────────────────────┐
 * │                 TimeLimiter (28s)                           │
 * │  ┌───────────────────────────────────────────────────────┐  │
 * │  │                 Retry (3 attempts × 500ms)            │  │
 * │  │  ┌─────────────────────────────────────────────────┐  │  │
 * │  │  │           HTTP Client                           │  │  │
 * │  │  │  - connectTimeout: 3s                           │  │  │
 * │  │  │  - responseTimeout: 5s                          │  │  │
 * │  │  └─────────────────────────────────────────────────┘  │  │
 * │  └───────────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────────┘
 * 상한 예산: 3*(3s+5s) + 2*0.5s + 3s = 24 + 1 + 3 = 28s
 * cacheFollowerTimeout: 32s (TimeLimiter 28s + 여유 4s)
 * ```
 */
@Validated
@ConfigurationProperties(prefix = "nexon.api")
class NexonApiProperties {

    /**
     * TCP 연결 타임아웃
     *
     * 서버와의 TCP 연결(핸드셰이크)이 완료되어야 하는 최대 시간
     *
     * 기본값: 3초
     */
    @NotNull
    var connectTimeout: Duration = Duration.ofSeconds(3)

    /**
     * HTTP 응답 타임아웃 (읽기 타임아웃 성격)
     *
     * 요청 전송 후 응답을 수신하는 과정에서 허용되는 최대 대기 시간
     *
     * Reactor Netty HttpClient.responseTimeout()에 적용됨
     *
     * 기본값: 5초
     */
    @NotNull
    var responseTimeout: Duration = Duration.ofSeconds(5)

    /**
     * 캐시 Follower 대기 타임아웃 (초)
     *
     * Leader가 API 호출을 완료할 때까지 Follower가 대기하는 최대 시간
     *
     * TimeLimiter보다 약간 길게 설정 권장 (안전 마진)
     *
     * 기본값: 32초 (TimeLimiter 28초 + 여유 4초)
     *
     * 허용 범위: 5 ~ 120초
     */
    @Min(5)
    @Max(120)
    var cacheFollowerTimeoutSeconds: Int = 32

    /**
     * 래치 초기 TTL (초)
     *
     * 리더가 래치를 생성할 때 설정하는 TTL
     *
     * 리더 크래시 시 팔로워가 영원히 대기하는 것을 방지
     *
     * 기본값: 60초 (cacheFollowerTimeout보다 충분히 길게)
     */
    @Min(30)
    @Max(300)
    var latchInitialTtlSeconds: Int = 60

    /**
     * 래치 정리 후 TTL (초)
     *
     * 리더가 작업 완료 후 래치에 설정하는 짧은 TTL
     *
     * 팔로워가 캐시 조회할 시간만큼 유지 후 자동 정리
     *
     * 기본값: 10초
     */
    @Min(5)
    @Max(60)
    var latchFinalizeTtlSeconds: Int = 10

    /**
     * 장비 데이터 로드 타임아웃 (초)
     *
     * {@link maple.expectation.application.service.expectation.EquipmentExpectationServiceV4#loadEquipmentDataAsync}
     * 에서 사용하는 application-level 타임아웃
     *
     * <p><strong>중요:</strong> Resilience4j TimeLimiter(28s)와 동기화 필요
     *
     * <p>계산 공식: 3*(connectTimeout + responseTimeout) + 2*retryWait + margin
     * <ul>
     *   <li>3회 재시도: 3*(3s+5s) = 24s</li>
     *   <li>재시도 대기: 2*0.5s = 1s</li>
     *   <li>안전 마진: 3s</li>
     *   <li>총계: 28s</li>
     * </ul>
     *
     * 기본값: 28초 (Resilience4j TimeLimiter와 일치)
     *
     * 허용 범위: 10 ~ 60초
     */
    @Min(10)
    @Max(60)
    var dataLoadTimeoutSeconds: Int = 28

}
