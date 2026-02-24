package maple.expectation.infrastructure.external.impl

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.domain.repository.CharacterEquipmentRepository
import maple.expectation.domain.v2.NexonApiOutbox
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Nexon API Client Configuration - ResilientNexonApiClient 관련 Bean 설정
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>OutboxFallbackManager Bean 등록
 *   <li>AlertNotificationHelper Bean 등록
 *   <li>FallbackHandler Bean 등록
 * </ul>
 *
 * <p>이 설정 클래스는 {@link ResilientNexonApiClient}가 필요로 하는 의존성을 조립합니다.
 */
@Configuration
class NexonApiClientConfig(
    private val outboxRepository: NexonApiOutboxRepository,
    private val checkedExecutor: CheckedLogicExecutor,
    @org.springframework.beans.factory.annotation.Qualifier("alertTaskExecutor") private val alertTaskExecutor: Executor,
    private val transactionTemplate: TransactionTemplate,
    private val statelessAlertService: StatelessAlertService,
    private val equipmentRepository: CharacterEquipmentRepository,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(NexonApiClientConfig::class.java)
    }

    /**
     * Outbox Fallback Manager Bean
     *
     * <p>실패한 API 호출을 Outbox에 적재하는 전담 매니저
     *
     * @return OutboxFallbackManager 인스턴스
     */
    @Bean
    fun outboxFallbackManager(): OutboxFallbackManager {
        return OutboxFallbackManager(
            outboxRepository, checkedExecutor, transactionTemplate, alertTaskExecutor
        )
    }

    /**
     * Alert Notification Helper Bean
     *
     * <p>Best-effort 알림 발송을 담당하는 헬퍼 클래스
     *
     * @return AlertNotificationHelper 인스턴스
     */
    @Bean
    fun alertNotificationHelper(): AlertNotificationHelper {
        return AlertNotificationHelper(
            statelessAlertService,
            checkedExecutor,
            alertTaskExecutor
        )
    }

    /**
     * Fallback Handler Bean
     *
     * <p>API 호출 실패 시 fallback 로직을 담당하는 핸들러
     *
     * @return FallbackHandler 인스턴스
     */
    @Bean
    fun fallbackHandler(
        outboxFallbackManager: OutboxFallbackManager,
        alertNotificationHelper: AlertNotificationHelper
    ): FallbackHandler {
        return FallbackHandler(
            equipmentRepository,
            objectMapper,
            checkedExecutor,
            outboxFallbackManager,
            alertNotificationHelper
        )
    }
}
