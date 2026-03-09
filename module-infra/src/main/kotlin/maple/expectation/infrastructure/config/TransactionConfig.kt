package maple.expectation.infrastructure.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 트랜잭션 설정 (Issue #158, P1-11)
 *
 * ## TransactionTemplate 정책
 *
 * - **transactionTemplate** (Primary): 범용 읽기/쓰기 템플릿
 * - **readOnlyTransactionTemplate**: Expectation 경로 전용 읽기 전용
 *
 * ## Multi-DataSource Architecture (P1-11)
 *
 * ### Current State (Single DataSource)
 * - **Primary TransactionManager**: `transactionManager` (MySQL/JPA)
 * - All JPA repositories use explicit `@Transactional("transactionManager")` qualifier
 * - Prevents ambiguity when multiple transaction managers exist
 *
 * ### Future State (MongoDB Read Replicas)
 * - **Secondary TransactionManager**: `mongoTransactionManager` (MongoDB)
 * - MongoDB read replicas will require separate transaction management
 * - All repositories already qualified to support multi-datasource migration
 *
 * ### Migration Path
 * 1. Add `mongoTransactionManager` bean when enabling read replicas
 * 2. MongoDB repositories will use `@Transactional("mongoTransactionManager")`
 * 3. MySQL repositories continue using `@Transactional("transactionManager")`
 *
 * @see [Issue #158](https://github.com/issue/158): Expectation API 캐시 타겟 전환
 * @see <a href="../../docs/adr/013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 */
@Configuration
class TransactionConfig {

    /**
     * 기본 TransactionTemplate (읽기/쓰기 가능)
     *
     * 범용 트랜잭션 템플릿. 테스트 및 쓰기 작업에 사용.
     *
     * @param transactionManager Spring이 제공하는 트랜잭션 매니저
     * @return 읽기/쓰기 TransactionTemplate
     */
    @Bean
    @Primary
    fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Expectation 경로 전용 읽기 전용 TransactionTemplate
     *
     * P0-5 정책: readOnly=true, timeout=5초
     *
     * ## 사용 목적
     *
     * - Tx 안에서는 CharacterSnapshot만 생성하고 종료
     * - Lazy 로딩/세션 종료 리스크 제거
     * - follower 대기, 캐시 조회는 Tx 밖에서 수행
     *
     * @param transactionManager Spring이 제공하는 트랜잭션 매니저
     * @return 읽기 전용 TransactionTemplate
     */
    @Bean(name = ["readOnlyTransactionTemplate"])
    fun readOnlyTransactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate {
        val template = TransactionTemplate(transactionManager)
        template.isReadOnly = true
        // Issue #225: 5s → 10s (Timeout Hierarchy 정렬 - MySQL lock_wait 8s보다 여유 있게)
        template.timeout = 10
        return template
    }
}
