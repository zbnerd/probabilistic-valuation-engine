package maple.expectation.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationPublisher
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationSubscriber
import maple.expectation.infrastructure.cache.invalidation.impl.PostgresNotifyPublisher
import maple.expectation.infrastructure.cache.invalidation.impl.PostgresNotifySubscriber
import maple.expectation.infrastructure.executor.LogicExecutor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * PostgreSQL LISTEN/NOTIFY 기반 캐시 무효화 Pub/Sub 설정
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 *
 * <p>cache.invalidation.impl=postgres 시 활성화
 *
 * <h3>Redis RTopic → PostgreSQL NOTIFY 전환</h3>
 *
 * <p>Redis Pub/Sub 없이 PostgreSQL 자체 기능만으로 인스턴스 간 캐시 무효화
 *
 * <h3>P1-9: SmartInitializingSingleton으로 초기화 순서 보장</h3>
 *
 * <p>모든 Singleton Bean 생성 완료 후 콜백 연결
 *
 * <h3>Callback 패턴 (순환참조 방지)</h3>
 *
 * <pre>
 * TieredCacheManager → TieredCache (Supplier callback)
 *    ↑ SmartInitializingSingleton.afterSingletonsInstantiated()
 * PostgresNotifyConfig → PostgresNotifyPublisher
 * </pre>
 */
@Configuration
@ConditionalOnProperty(
    name = ["cache.invalidation.impl"],
    havingValue = "postgres",
    matchIfMissing = false,
)
class PostgresNotifyConfig(
    private val dataSource: DataSource,
    private val cacheManager: CacheManager,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.instance-id:\${HOSTNAME:unknown}}") private val instanceId: String,
    @Value("\${app.cache.notify.poll-interval-ms:100}") private val pollIntervalMs: Long,
    @Value("\${app.cache.notify.reconnect-delay-ms:5000}") private val reconnectDelayMs: Long,
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(PostgresNotifyConfig::class.java)

    /**
     * @PostConstruct에서 재사용할 Publisher 인스턴스 (CGLIB 순환참조 방지)
     */
    private val publisherInstance = PostgresNotifyPublisher(
        JdbcTemplate(dataSource),
        objectMapper,
        executor,
        meterRegistry,
    )

    /** 캐시 무효화 이벤트 발행자 Bean */
    @Bean
    fun cacheInvalidationPublisher(): CacheInvalidationPublisher {
        log.info("[PostgresNotifyConfig] Creating PostgresNotifyPublisher bean")
        return publisherInstance
    }

    /**
     * 캐시 무효화 이벤트 구독자 Bean
     *
     * <p>P0-3: TieredCacheManager 직접 주입으로 L1 캐시 접근
     *
     * <p>@PostConstruct에서 자동 구독 시작
     */
    @Bean
    fun cacheInvalidationSubscriber(): CacheInvalidationSubscriber {
        log.info("[PostgresNotifyConfig] Creating PostgresNotifySubscriber bean")

        // P0-3: CacheManager가 TieredCacheManager인지 확인
        val tieredManager = cacheManager as? TieredCacheManager
        if (tieredManager == null) {
            log.warn(
                "[PostgresNotifyConfig] CacheManager is not TieredCacheManager, " +
                    "cache invalidation subscriber will not work properly",
            )
            return PostgresNotifySubscriber(dataSource, null, objectMapper, executor, meterRegistry, instanceId, pollIntervalMs, reconnectDelayMs)
        }

        return PostgresNotifySubscriber(dataSource, tieredManager, objectMapper, executor, meterRegistry, instanceId, pollIntervalMs, reconnectDelayMs)
    }

    /**
     * TieredCacheManager에 Callback 연결 (P0-2, P0-4 해결)
     *
     * <h4>P1-6: CAS 초기화 메서드 사용</h4>
     *
     * <p>@Setter → initializeInstanceId() / initializeInvalidationCallback()
     *
     * <h4>P1-9: SmartInitializingSingleton으로 초기화 순서 보장</h4>
     *
     * <p>모든 Singleton Bean 생성 완료 후 실행되므로, 이미 생성된 TieredCache 인스턴스도 AtomicReference를 통해 최신 instanceId와
     * callback을 참조 (Supplier-based Lazy Resolution)
     *
     * <p>중복 호출 시 CAS로 안전하게 무시
     */
    override fun afterSingletonsInstantiated() {
        val tieredManager = cacheManager as? TieredCacheManager
        if (tieredManager == null) {
            log.warn(
                "[PostgresNotifyConfig] CacheManager is not TieredCacheManager, skipping callback connection",
            )
            return
        }

        // P1-6: CAS 초기화 (중복 호출 방지)
        tieredManager.initializeInstanceId(instanceId)

        // Create callback wrapper to avoid AOP guardrail issues
        val callback = java.util.function.Consumer<CacheInvalidationEvent> { event ->
            publisherInstance.publish(event)
        }
        tieredManager.initializeInvalidationCallback(callback)

        log.info("[PostgresNotifyConfig] Callback connected: instanceId={}, impl=postgres", instanceId)
    }
}
