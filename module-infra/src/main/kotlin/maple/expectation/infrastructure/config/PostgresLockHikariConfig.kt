package maple.expectation.infrastructure.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.jdbc.core.JdbcTemplate

/**
 * PostgreSQL Advisory Lock 전용 HikariCP 설정
 *
 * <p>[목적] - PostgreSQL Advisory Lock 사용 시 메인 커넥션 풀이 고갈되는 것을 방지 - 락 전용 커넥션 풀을 별도로 운영하여 애플리케이션
 * 안정성 확보
 *
 * <p>[설계 원칙] - Fixed Pool Size: Min과 Max를 동일하게 설정하여 연결 비용(Handshake) 제거 - Size 40: 기본값, prod에서는
 * 필요에 따라 오버라이드 - Fail-fast: 락 획득 타임아웃을 짧게 가져가서 스레드 고갈 방지
 *
 * <p>[ADR-003] PostgreSQL Advisory Lock 전용 설정 - MySQL LockHikariConfig과 배타적으로 활성화 (Profile로 구분) -
 * pgtest, pgprod 프로파일에서만 활성화
 *
 * @see LockHikariConfig MySQL용 락 풀 (pgtest/pgprod 프로파일에서 비활성화)
 */
@Configuration
@Profile("pgtest | pgprod")
@Conditional(PostgresLockHikariConfig.PostgresDatasourceCondition::class)
class PostgresLockHikariConfig(
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val username: String,
    @Value("\${spring.datasource.password}") private val password: String,
    @Value("\${lock.datasource.pool-size:40}") private val poolSize: Int,
) {

    private val log = LoggerFactory.getLogger(PostgresLockHikariConfig::class.java)

    @Bean(name = ["lockDataSource"])
    fun lockDataSource(): DataSource {
        log.info("[PostgresLockPool] JDBC URL: {}", jdbcUrl)
        val config = HikariConfig()

        // 기본 연결 정보
        config.jdbcUrl = jdbcUrl
        config.username = username
        config.password = password
        config.driverClassName = "org.postgresql.Driver"

        // Fixed Pool Size: 연결 비용 제거
        config.maximumPoolSize = poolSize
        config.minimumIdle = poolSize

        // Fail-fast 전략
        config.connectionTimeout = 5000 // 5초 안에 연결 못 얻으면 에러 (스레드 보호)
        config.idleTimeout = 300000
        config.maxLifetime = 600000
        config.poolName = "PostgresLockPool"

        // 검증 설정 (JDBC4 isValid 사용)
        config.validationTimeout = 3000

        // P0-6 Fix: Micrometer 메트릭 비활성화 (순환 참조 데드락 방지)
        // 주요 HikariPool 메트릭은 기본 DataSource에서 충분히 수집 가능

        log.info(
            "[PostgresLockPool] Initialized dedicated PostgreSQL advisory lock pool (Fixed Size: {}, Metrics: disabled)",
            poolSize,
        )

        return HikariDataSource(config)
    }

    @Bean(name = ["lockJdbcTemplate"])
    fun lockJdbcTemplate(): JdbcTemplate = JdbcTemplate(lockDataSource())

    /**
     * Condition that only matches when datasource URL is a PostgreSQL URL.
     *
     * <p>Issue #563: When using @ActiveProfiles("pgtest") with Testcontainers, Spring may
     * resolve datasource URL from base application.yml (H2) instead of Testcontainers-provided URL
     * during early bean initialization. This condition prevents the lock pool from being created
     * when H2 is detected, allowing tests to run without the lock pool.
     */
    class PostgresDatasourceCondition : Condition {
        private val log = LoggerFactory.getLogger(PostgresDatasourceCondition::class.java)

        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val environment = context.environment
            val datasourceUrl = environment.getProperty("spring.datasource.url") ?: return false

            // Check for actual PostgreSQL JDBC URL (jdbc:postgresql:)
            // H2 with MODE=PostgreSQL should NOT match (jdbc:h2:mem:...;MODE=PostgreSQL)
            val isPostgres = datasourceUrl.startsWith("jdbc:postgresql:", ignoreCase = true)

            if (!isPostgres) {
                log.info(
                    "[PostgresLockPool] Skipping lock pool creation - datasource URL is not PostgreSQL: {}",
                    datasourceUrl.substringBefore("?"),
                )
            }

            return isPostgres
        }
    }
}
