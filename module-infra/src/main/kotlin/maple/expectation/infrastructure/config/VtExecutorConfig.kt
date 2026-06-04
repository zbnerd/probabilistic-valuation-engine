package maple.expectation.infrastructure.config

import jakarta.annotation.PreDestroy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * VT Executor Configuration — external-api, synchronizer 전용 named virtual thread executors
 *
 * <p>각 컴포넌트가 inline으로 VirtualThreadExecutorManager를 생성하던 패턴을
 * 중앙 집중식 @Bean 관리로 전환.
 *
 * <p>포함 Bean:
 * <ul>
 *   <li>externalApiSchedulerExecutor
 *   <li>authCharacterFetchExecutor
 *   <li>internalApiExecutor
 *   <li>urgentCharacterRequestExecutor
 *   <li>kafkaResultChunkExecutor
 *   <li>basicSnapshotChunkExecutor
 * </ul>
 *
 * @see CoreExecutorConfig
 */
@Configuration
class VtExecutorConfig {

    private val log = LoggerFactory.getLogger(VtExecutorConfig::class.java)
    private val executors = mutableListOf<ExecutorService>()

    @Bean(name = ["externalApiSchedulerExecutor"])
    @ConditionalOnMissingBean(name = ["externalApiSchedulerExecutor"])
    fun externalApiSchedulerExecutor(): ExecutorService =
        createTrackedExecutor("ExternalApiScheduler")

    @Bean(name = ["authCharacterFetchExecutor"])
    @ConditionalOnMissingBean(name = ["authCharacterFetchExecutor"])
    fun authCharacterFetchExecutor(): ExecutorService =
        createTrackedExecutor("AuthCharacterFetchConsumer")

    @Bean(name = ["internalApiExecutor"])
    @ConditionalOnMissingBean(name = ["internalApiExecutor"])
    fun internalApiExecutor(): ExecutorService =
        createTrackedExecutor("InternalApiController")

    @Bean(name = ["urgentCharacterRequestExecutor"])
    @ConditionalOnMissingBean(name = ["urgentCharacterRequestExecutor"])
    fun urgentCharacterRequestExecutor(): ExecutorService =
        createTrackedExecutor("UrgentCharacterRequestConsumer")

    @Bean(name = ["kafkaResultChunkExecutor"])
    @ConditionalOnMissingBean(name = ["kafkaResultChunkExecutor"])
    fun kafkaResultChunkExecutor(): ExecutorService =
        createTrackedExecutor("KafkaResultChunkConsumer")

    @Bean(name = ["basicSnapshotChunkExecutor"])
    @ConditionalOnMissingBean(name = ["basicSnapshotChunkExecutor"])
    fun basicSnapshotChunkExecutor(): ExecutorService =
        createTrackedExecutor("BasicSnapshotChunkConsumer")

    private fun createTrackedExecutor(name: String): ExecutorService {
        val es = Executors.newVirtualThreadPerTaskExecutor()
        synchronized(executors) { executors.add(es) }
        return es
    }

    @PreDestroy
    fun shutdownAll() {
        synchronized(executors) {
            executors.forEach { es ->
                es.shutdown()
                if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("[VtExecutorConfig] executor did not terminate in 5s, forcing shutdown")
                    es.shutdownNow()
                }
            }
            log.info("[VtExecutorConfig] {} VT executors shut down", executors.size)
        }
    }
}
