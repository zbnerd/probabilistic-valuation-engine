package maple.expectation.infrastructure.resilience

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * MySQL Resilience 설정 프로퍼티 (Issue #218)
 *
 * <p>application.yml의 resilience.mysql-fallback 설정을 바인딩합니다.
 *
 * <p>P1 Externalization: syncBatchSize는 expectation.batch.mysql-fallback-sync-size에서 주입받습니다.
 */
@Component
@ConfigurationProperties(prefix = "resilience.mysql-fallback")
class MySQLFallbackProperties {

    /** MySQL Fallback 기능 활성화 여부 */
    var isEnabled: Boolean = true

    /** MySQL 상태 저장 키 (Redis Hash Tag 적용) */
    var stateKey: String = "{mysql}:state"

    /** TTL 관리용 분산 락 키 */
    var ttlLockKey: String = "{mysql}:ttl:lock"

    /** Compensation Log Stream 키 */
    var compensationStream: String = "{mysql}:compensation:stream"

    /** Compensation DLQ 키 */
    var compensationDlq: String = "{mysql}:compensation:dlq"

    /** Debounce 대기 시간 (초) - Flapping 방지 */
    var debounceSeconds: Int = 5

    /** 상태 키 TTL (초) - 인스턴스 크래시 대비 */
    var stateTtlSeconds: Int = 300

    /** Sync 배치 크기 - BatchProperties에서 주입됨 */
    var syncBatchSize: Int = 100 // 기본값 유지 (역호환성)

    /** Sync 최대 재시도 횟수 */
    var syncMaxRetries: Int = 3

    /** Consumer Group 이름 */
    var syncConsumerGroup: String = "compensation-sync"

    /** 대상 캐시 패턴 목록 */
    var targetCachePatterns: List<String> = listOf("equipment:*", "ocidCache:*")

    /** SCAN COUNT 설정 */
    var scanCount: Int = 1000

    /** Stream MAXLEN 설정 */
    var streamMaxLen: Int = 10000

    /** 분산 락 대기 시간 (초) */
    var lockWaitSeconds: Int = 5

    /** 분산 락 임대 시간 (초) */
    var lockLeaseSeconds: Int = 30
}
