package maple.expectation.infrastructure.lock

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import java.util.Optional

/**
 * Lock Strategy 활성화 상태 로깅
 */
@Configuration
class LockStrategyConfiguration(
    private val redisLockStrategy: Optional<RedisDistributedLockStrategy>,
    private val mysqlLockStrategy: Optional<MySqlNamedLockStrategy>,
    private val resilientLockStrategy: Optional<ResilientLockStrategy>
) {

    private val log = LoggerFactory.getLogger(LockStrategyConfiguration::class.java)

    /**
     * 애플리케이션 시작 시 활성화된 락 전략 로깅
     */
    @PostConstruct
    fun logActiveLockStrategy() {
        val lockImpl = System.getProperty("lock.impl", "redis")

        if (resilientLockStrategy.isPresent) {
            log.info("✅ [Lock Strategy] Redis → MySQL Fallback 활성화 (ResilientLockStrategy)")
            log.info("   - Primary: Redisson RLock (Watchdog 모드)")
            log.info("   - Fallback: MySQL Named Lock (세션 기반)")
            log.info("   - Circuit Breaker: redisLock 인스턴스 적용")
        } else if (mysqlLockStrategy.isPresent) {
            log.info("✅ [Lock Strategy] MySQL Named Lock 활성화 (MySqlNamedLockStrategy)")
            log.info("   - 구현: GET_LOCK/RELEASE_LOCK (세션 고정)")
            log.info("   - 주의: tryLockImmediately() 지원 불가 → executeWithLock() 사용")
        } else if (redisLockStrategy.isPresent) {
            log.info("✅ [Lock Strategy] Redis 분산 락 활성화 (RedisDistributedLockStrategy)")
            log.info("   - 구현: Redisson RLock (Watchdog 자동 갱신)")
            log.info("   - 장점: 고성능, 저지연 (< 1ms), TTL 자동 관리")
        } else {
            log.warn("⚠️ [Lock Strategy] 활성화된 락 전략 없음 - 애플리케이션 기능 제한됨")
        }

        log.info("   - 설정값: lock.impl=$lockImpl")
        log.info("   - 기본값: redis (matchIfMissing=true)")
    }
}
