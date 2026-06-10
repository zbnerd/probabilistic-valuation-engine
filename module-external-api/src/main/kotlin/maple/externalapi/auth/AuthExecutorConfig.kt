package maple.externalapi.auth

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * Auth executor configuration (Issue #1206).
 *
 * <p>AuthCharacterFetchConsumer 의 `@Qualifier("authCharacterFetchExecutor")` 인자 wired.
 * 별도 platform thread pool — Kafka listener 의 dispatch 와 분리.
 *
 * <p>Thread type: <b>platform thread</b> (not VT). RunBlocking(Dispatchers.Default) 으로 VT carrier
 * block 위험 회피 (#1128 precedent, Issue #1208 follow-up).
 *
 * <h3>Sizing (per #1128 sizing principles)</h3>
 * <ul>
 *   <li>core: 2 (low-volume, auth API call 만)</li>
 *   <li>max: 4 (1:2 ratio per P2-25)</li>
 *   <li>queue: 100</li>
 * </ul>
 */
@Configuration
class AuthExecutorConfig {

    private val log = LoggerFactory.getLogger(AuthExecutorConfig::class.java)

    @Bean(name = ["authCharacterFetchExecutor"])
    fun authCharacterFetchExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 4
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("auth-character-fetch-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)
        executor.initialize()
        log.info("[AuthExecutorConfig] authCharacterFetchExecutor initialized: core=2, max=4, queue=100")
        return executor
    }
}
