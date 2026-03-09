package maple.expectation.infrastructure.notification.discord

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import maple.expectation.infrastructure.monitoring.ai.AiSreService.AiAnalysisResult
import maple.expectation.infrastructure.notification.discord.dto.DiscordMessage
import maple.expectation.infrastructure.notification.discord.dto.DiscordMessage.Embed
import maple.expectation.infrastructure.notification.discord.dto.DiscordMessage.Field
import maple.expectation.infrastructure.notification.discord.dto.DiscordMessage.Footer
import org.springframework.stereotype.Component

/**
 * Discord 메시지 팩토리 (Issue #251 확장)
 *
 * Factory 패턴으로 Discord Embed 메시지 생성 로직을 캡슐화합니다.
 */
@Component
class DiscordMessageFactory {

    companion object {
        private const val ERROR_COLOR = 16711680 // 빨간색
        private const val AI_COLOR = 5793266 // 파란색
        private const val AI_DISCLAIMER = "⚠️ **이 분석은 AI가 생성한 결과이므로 검증이 필요합니다.**"
    }

    fun createCriticalEmbed(title: String, description: String, e: Throwable): DiscordMessage = DiscordMessage(
        listOf(
            Embed(
                title = "🚨 $title",
                description = description,
                color = ERROR_COLOR,
                fields = createFields(e),
                footer = Footer("MapleExpectation Alert System"),
                timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT),
            ),
        ),
    )

    fun createCriticalEmbedWithAi(
        title: String,
        description: String,
        e: Throwable,
        aiAnalysis: AiAnalysisResult?,
        systemSummary: String,
    ): DiscordMessage {
        val embeds = mutableListOf<Embed>()
        embeds.add(
            Embed(
                title = "🚨 $title",
                description = description,
                color = ERROR_COLOR,
                fields = createFieldsWithContext(e, systemSummary),
                footer = Footer("MapleExpectation Alert System"),
                timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT),
            ),
        )
        aiAnalysis?.let { embeds.add(createAiAnalysisEmbed(it)) }
        return DiscordMessage(embeds)
    }

    private fun createAiAnalysisEmbed(analysis: AiAnalysisResult): Embed {
        val fields = mutableListOf<Field>()
        fields.add(Field("🔍 Root Cause", analysis.rootCause, false))
        fields.add(Field("⚡ Severity", "${severityEmoji(analysis.severity)} ${analysis.severity}", true))
        fields.add(Field("🔗 Affected", analysis.affectedComponents, true))
        fields.add(Field("📋 Action Items", formatActionItems(analysis.actionItems), false))
        fields.add(Field("📊 Analysis Source", analysis.analysisSource, true))
        fields.add(Field("⚠️ Disclaimer", AI_DISCLAIMER, false))

        return Embed(
            title = "🤖 AI SRE 분석 리포트",
            description = "자동화된 에러 분석 결과입니다.",
            color = AI_COLOR,
            fields = fields,
            footer = Footer("Powered by GPT-4o-mini | ${analysis.analysisSource}"),
            timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT),
        )
    }

    private fun createFieldsWithContext(e: Throwable, systemSummary: String): List<Field> {
        val fields = mutableListOf<Field>()
        fields.add(Field("📄 Exception Type", e.javaClass.simpleName, true))
        fields.add(Field("💻 Server", getServerIp(), true))
        fields.add(Field("💬 Message", getShortMessage(e), false))
        if (systemSummary.isNotBlank()) {
            fields.add(Field("📊 System Context", truncate(systemSummary, 500), false))
        }
        fields.add(Field("📝 Stack Trace (Top 5)", "```java\n${getStackTrace(e)}\n```", false))
        return fields
    }

    private fun createFields(e: Throwable): List<Field> = listOf(
        Field("📄 Exception Type", e.javaClass.simpleName, true),
        Field("💻 Server IP", getServerIp(), true),
        Field("💬 Root Cause", getShortMessage(e), false),
        Field("Stack Trace (Top 5)", "```java\n${getStackTrace(e)}\n```", false),
    )

    private fun getServerIp(): String = System.getenv("HOSTNAME") ?: "Unknown"

    private fun getShortMessage(e: Throwable): String = e.message ?: "No message provided"

    private fun getStackTrace(e: Throwable): String = e.stackTrace.take(5).joinToString("\n") { it.toString() }

    private fun severityEmoji(severity: String): String = when (severity.uppercase()) {
        "CRITICAL" -> "🔴"
        "HIGH" -> "🟠"
        "MEDIUM" -> "🟡"
        "LOW" -> "🟢"
        else -> "⚪"
    }

    private fun formatActionItems(actionItems: String?): String = if (actionItems.isNullOrBlank()) "수동 점검 필요" else truncate(actionItems, 500)

    private fun truncate(text: String?, maxLength: Int): String = if (text == null) {
        ""
    } else if (text.length <= maxLength) {
        text
    } else {
        text.substring(0, maxLength - 3) + "..."
    }
}
