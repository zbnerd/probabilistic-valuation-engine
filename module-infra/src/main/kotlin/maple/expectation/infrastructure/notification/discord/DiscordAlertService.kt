package maple.expectation.infrastructure.notification.discord

import java.time.Duration
import java.util.Optional
import maple.expectation.infrastructure.monitoring.ai.AiSreService
import maple.expectation.infrastructure.monitoring.ai.AiSreService.AiAnalysisResult
import maple.expectation.infrastructure.monitoring.context.SystemContextProvider
import maple.expectation.infrastructure.notification.discord.dto.DiscordMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

/**
 * Discord 알림 서비스 (Issue #251 확장)
 *
 * AI SRE 통합:
 * - 에러 발생 시 AI 분석 자동 트리거
 * - 시스템 컨텍스트 자동 수집
 * - AI 분석 실패 시 기본 알림 전송 (Fallback)
 */
@Service
class DiscordAlertService(
    @Qualifier("mapleWebClient") private val webClient: WebClient,
    private val messageFactory: DiscordMessageFactory,
    private val aiSreService: Optional<AiSreService>,
    private val contextProvider: Optional<SystemContextProvider>,
    @Value("\${alert.discord.webhook-url:}") private val webhookUrl: String,
    @Value("\${ai.sre.enabled:false}") private val aiSreEnabled: Boolean,
) {
    companion object {
        private val log = LoggerFactory.getLogger(DiscordAlertService::class.java)
        private val ALERT_TIMEOUT = Duration.ofSeconds(3)
    }

    /** Critical Alert 전송 (기존 호환) */
    fun sendCriticalAlert(title: String, description: String, e: Throwable) {
        if (aiSreEnabled && aiSreService.isPresent) {
            sendCriticalAlertWithAi(title, description, e)
            return
        }

        val payload = messageFactory.createCriticalEmbed(title, description, e)
        send(payload)
    }

    /** AI 분석이 포함된 Critical Alert 전송 (Issue #251) */
    fun sendCriticalAlertWithAi(title: String, description: String, e: Throwable) {
        val systemSummary = getSystemSummary()
        val aiAnalysis = getAiAnalysis(e)

        val payload = messageFactory.createCriticalEmbedWithAi(title, description, e, aiAnalysis, systemSummary)
        send(payload)

        logAiAnalysisResult(e, aiAnalysis)
    }

    /** AI 분석 수행 (타임아웃 처리 포함) */
    private fun getAiAnalysis(e: Throwable): AiAnalysisResult? = aiSreService.flatMap { it.analyzeError(e) }.orElse(null)

    /** 시스템 컨텍스트 요약 수집 */
    private fun getSystemSummary(): String = contextProvider.map { it.buildSummary() }.orElse("")

    /** AI 분석 결과 로깅 */
    private fun logAiAnalysisResult(e: Throwable, aiAnalysis: AiAnalysisResult?) {
        if (aiAnalysis != null) {
            log.info(
                "[AiSre] 분석 완료: {} -> {} ({})",
                e.javaClass.simpleName,
                aiAnalysis.severity,
                aiAnalysis.analysisSource,
            )
        } else {
            log.debug("[AiSre] AI 분석 스킵 또는 실패: {}", e.javaClass.simpleName)
        }
    }

    private fun send(payload: DiscordMessage) {
        val maskedUrl = webhookUrl.substring(0, minOf(webhookUrl.length, 20)) + "..."

        webClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .toBodilessEntity()
            .timeout(ALERT_TIMEOUT)
            .subscribe(
                { log.info("[Discord] Alert sent successfully to {}", maskedUrl) },
                { error -> log.error("[Discord] Failed to send alert: {}", error.message) },
            )
    }
}
