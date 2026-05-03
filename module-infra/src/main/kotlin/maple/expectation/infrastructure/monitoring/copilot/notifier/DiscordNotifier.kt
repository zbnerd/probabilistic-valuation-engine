package maple.expectation.infrastructure.monitoring.copilot.notifier

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import maple.expectation.error.exception.InternalSystemException
import maple.expectation.infrastructure.config.DiscordTimeoutProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DiscordNotifier(
    httpClient: HttpClient,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val timeoutProperties: DiscordTimeoutProperties,
) {
    companion object {
        private const val CONTENT_TYPE = "application/json"
        private const val MAX_RETRIES = 1
        private val log = LoggerFactory.getLogger(DiscordNotifier::class.java)
    }

    private val httpClient: HttpClient = httpClient

    @Value("\${alert.discord.webhook-url:}")
    private lateinit var webhookUrl: String

    fun send(content: String) {
        executor.executeWithTranslation(
            {
                sendInternal(content)
                null
            },
            { e, ctx ->
                InternalSystemException(
                    "Discord webhook send failed [${ctx.toTaskName()}]: ${e.message}",
                    e,
                )
            },
            TaskContext.of("DiscordNotifier", "SendWebhook"),
        )
    }

    @Throws(Exception::class)
    private fun sendInternal(content: String) {
        val payload = DiscordWebhookPayload(content)
        val jsonPayload = objectMapper.writeValueAsString(payload)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", CONTENT_TYPE)
            .timeout(Duration.ofSeconds(timeoutProperties.webhookTimeoutSeconds.toLong()))
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build()

        val response = sendWithRetry(request, 0)

        if (response.statusCode() >= 400) {
            log.error("[DiscordNotifier] Failed to send webhook: HTTP {} - {}", response.statusCode(), response.body())
            throw InternalSystemException("Discord webhook failed: HTTP ${response.statusCode()} - ${response.body()}")
        }

        log.info("[DiscordNotifier] Incident alert sent successfully")
    }

    @Throws(Exception::class)
    private fun sendWithRetry(request: HttpRequest, attempt: Int): HttpResponse<String> {
        val response = sendHttpRequest(request)

        if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
            log.warn("[DiscordNotifier] Rate limited (429), retrying... (attempt {}/{})", attempt + 1, MAX_RETRIES + 1)

            val delayMs = extractRetryAfter(response)
            sleep(delayMs)

            return sendWithRetry(request, attempt + 1)
        }

        return response
    }

    @Throws(Exception::class, InterruptedException::class)
    private fun sendHttpRequest(request: HttpRequest): HttpResponse<String> = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    private fun extractRetryAfter(response: HttpResponse<String>): Long = executor.executeOrDefault(
        {
            response.headers()
                .firstValue("Retry-After")
                .map { retryAfter -> Integer.parseInt(retryAfter) * 1000L }
                .orElse(timeoutProperties.retryAfterDefaultMs)
        },
        timeoutProperties.retryAfterDefaultMs,
        TaskContext.of("DiscordNotifier", "ExtractRetryAfter"),
    )

    private fun sleep(millis: Long) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis))
    }

    fun formatIncidentMessage(
        incidentId: String,
        severity: String,
        signals: List<AnnotatedSignal>,
        hypotheses: List<String>,
        actions: List<String>,
    ): String {
        val sb = StringBuilder()

        val emoji = if ("CRIT" == severity) "🚨" else "⚠️"
        sb.append("$emoji **INCIDENT ALERT** `$incidentId` [$severity]\n\n")

        sb.append("**📊 SYMPTOMS**\n")
        val signalCount = java.lang.Math.min(3, signals.size)
        for (i in 0 until signalCount) {
            val signal = signals[i]
            sb.append(
                String.format(
                    "%d. **%s**: `%.4f` %s\n",
                    i + 1,
                    signal.signal.panelTitle,
                    signal.value,
                    signal.signal.unit ?: "",
                ),
            )
        }
        sb.append("\n")

        if (hypotheses.isNotEmpty()) {
            sb.append("**🤖 ROOT CAUSE ANALYSIS**\n")
            val hypCount = java.lang.Math.min(2, hypotheses.size)
            for (i in 0 until hypCount) {
                sb.append(String.format("%d. %s\n", i + 1, hypotheses[i]))
            }
            sb.append("\n")
        }

        if (actions.isNotEmpty()) {
            sb.append("**🔧 REMEDIATION**\n")
            val actionCount = java.lang.Math.min(2, actions.size)
            for (i in 0 until actionCount) {
                sb.append(String.format("%d. %s\n", i + 1, actions[i]))
            }
        }

        return sb.toString()
    }

    private data class DiscordWebhookPayload(val content: String)

    data class AnnotatedSignal(val signal: SignalDefinition, val value: Double)
}
