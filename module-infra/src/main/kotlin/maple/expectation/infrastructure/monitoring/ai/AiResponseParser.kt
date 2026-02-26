package maple.expectation.infrastructure.monitoring.ai

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AiResponseParser(
    private val executor: LogicExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(AiResponseParser::class.java)
    }

    fun parseAiResponse(response: String, originalException: Throwable): AiSreService.AiAnalysisResult {
        return AiSreService.AiAnalysisResult.builder()
            .rootCause(extractSection(response, "Root Cause", "원인 분석 중"))
            .severity(extractSection(response, "Severity", "MEDIUM"))
            .affectedComponents(extractSection(response, "Affected Components", "확인 필요"))
            .actionItems(extractSection(response, "Action Items", "수동 점검 필요"))
            .analysisSource("AI_GPT4O_MINI")
            .disclaimer("이 분석은 AI가 생성한 결과이므로 검증이 필요합니다.")
            .build()
    }

    private fun extractSection(response: String, sectionName: String, defaultValue: String): String {
        val lines = response.split("\n")
        val result = StringBuilder()
        var capturing = false

        for (line in lines) {
            if (line.contains(sectionName) || line.contains("**$sectionName**")) {
                capturing = true
                val colonIndex = line.indexOf(":")
                if (colonIndex != -1 && colonIndex < line.length - 1) {
                    result.append(line.substring(colonIndex + 1).trim())
                }
                continue
            }
            if (capturing) {
                if (line.startsWith("**") || line.startsWith("#") || line.isBlank()) {
                    break
                }
                result.append(line.trim()).append(" ")
            }
        }

        val extracted = result.toString().trim()
        return if (extracted.isEmpty()) defaultValue else extracted
    }

    fun parseMitigationPlanJson(jsonResponse: String, incidentId: String): AiSreService.MitigationPlan {
        val cleanedResponse = removeMarkdownCodeBlocks(jsonResponse)

        return executor.executeWithFallback(
            { parseMitigationPlanInternal(cleanedResponse, incidentId) },
            { e -> createFallbackMitigationPlan(incidentId, e) },
            TaskContext.of("AiResponseParser", "ParseMitigationPlan", incidentId)
        )
    }

    @Throws(Exception::class)
    private fun parseMitigationPlanInternal(cleanedResponse: String, incidentId: String): AiSreService.MitigationPlan {
        val mapper = ObjectMapper()
        val planNode = mapper.readTree(cleanedResponse)

        val hypotheses: List<AiSreService.Hypothesis> = mapper.convertValue(
            planNode.get("hypotheses"),
            mapper.typeFactory.constructCollectionType(MutableList::class.java, AiSreService.Hypothesis::class.java)
        )

        val actions: List<AiSreService.Action> = mapper.convertValue(
            planNode.get("actions"),
            mapper.typeFactory.constructCollectionType(MutableList::class.java, AiSreService.Action::class.java)
        )

        val questions: List<AiSreService.ClarifyingQuestion> = mapper.convertValue(
            planNode.get("questions"),
            mapper.typeFactory.constructCollectionType(MutableList::class.java, AiSreService.ClarifyingQuestion::class.java)
        )

        val rollbackPlan = mapper.convertValue(planNode.get("rollbackPlan"), AiSreService.RollbackPlan::class.java)

        return AiSreService.MitigationPlan(
            incidentId,
            "AI_GPT4O_MINI",
            hypotheses,
            actions,
            questions,
            rollbackPlan,
            "AI가 생성한 완화 계획입니다. 검증 후 실행을 권장합니다."
        )
    }

    private fun removeMarkdownCodeBlocks(response: String): String {
        var cleaned = response
        if (cleaned.contains("```")) {
            val firstBacktick = cleaned.indexOf("```")
            if (firstBacktick != -1) {
                val newlineAfterFirstBlock = cleaned.indexOf("\n", firstBacktick)
                if (newlineAfterFirstBlock != -1) {
                    cleaned = cleaned.substring(newlineAfterFirstBlock + 1)
                }

                val lastBacktick = cleaned.lastIndexOf("```")
                if (lastBacktick != -1) {
                    cleaned = cleaned.substring(0, lastBacktick)
                }
            }
        }
        return cleaned
    }

    private fun createFallbackMitigationPlan(incidentId: String, e: Throwable): AiSreService.MitigationPlan {
        log.error("[AiResponseParser] JSON 파싱 실패, 기본 계획 반환: {}", e.message)
        return AiSreService.MitigationPlan(
            incidentId,
            "AI_PARSE_FAILED",
            listOf(AiSreService.Hypothesis("JSON 파싱 실패", "LOW", listOf(e.message ?: "Unknown error"))),
            listOf(AiSreService.Action(1, "수동 분석 실행", "LOW", "LLM 응답 확인 필요")),
            emptyList(),
            AiSreService.RollbackPlan("즉시", listOf("수동 개입")),
            "JSON 파싱 실패로 기본 계획을 반환했습니다."
        )
    }
}
