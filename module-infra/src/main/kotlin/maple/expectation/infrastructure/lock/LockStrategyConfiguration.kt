package maple.expectation.infrastructure.lock

import jakarta.annotation.PostConstruct
import java.util.Optional
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

/**
 * Lock Strategy 활성화 상태 로깅
 */
@Configuration
class LockStrategyConfiguration(
    private val postgresLockStrategy: Optional<PostgresAdvisoryLockStrategy>,
) {

    private val log = LoggerFactory.getLogger(LockStrategyConfiguration::class.java)

    /**
     * 애플리케이션 시작 시 활성화된 락 전략 로깅
     */
    @PostConstruct
    fun logActiveLockStrategy() {
        if (postgresLockStrategy.isPresent) {
            log.info("✅ [Lock Strategy] PostgreSQL Advisory Lock 활성화 (PostgresAdvisoryLockStrategy)")
            log.info("   - 구현: pg_try_advisory_xact_lock (트랜잭션 스코프)")
            log.info("   - 장점: 데이터베이스 네이티브 락, 자동 해제, 분산 환경 지원")
        } else {
            log.warn("⚠️ [Lock Strategy] 활성화된 락 전략 없음 - 애플리케이션 기능 제한됨")
        }
    }
}
