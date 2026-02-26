package maple.expectation.infrastructure.monitoring.ai

import org.springframework.stereotype.Component

@Component
class AiAnalysisFormatter {

    fun formatAsMarkdown(result: AiSreService.AiAnalysisResult): String {
        return """
            ## AI SRE 분석 결과

            **근본 원인**: ${result.rootCause}

            **심각도**: ${result.severity}

            **영향받는 컴포넌트**: ${result.affectedComponents}

            **조치사항**:
            ${indentActionItems(result.actionItems)}

            ---
            *분석 출처: ${result.analysisSource}*
            *${result.disclaimer}*
            """.trimIndent()
    }

    fun formatAsMarkdown(plan: AiSreService.MitigationPlan): String {
        val sb = StringBuilder()

        sb.append("# 인시던트 완화 계획 (ID: ${plan.incidentId})\n\n")
        sb.append("**분석 출처**: ${plan.analysisSource}\n\n")

        sb.append("## 원인 가설 (Hypotheses)\n\n")
        for (hypothesis in plan.hypotheses) {
            sb.append("### ${hypothesis.cause} (${hypothesis.confidence})\n")
            sb.append("${formatEvidenceList(hypothesis.evidence)}\n")
        }

        sb.append("\n## 조치 계획 (Actions)\n\n")
        for (action in plan.actions) {
            sb.append("### Step ${action.step}: ${action.action} (위험도: ${action.risk})\n")
            sb.append("- 기대 결과: ${action.expectedOutcome}\n")
        }

        if (plan.questions.isNotEmpty()) {
            sb.append("\n## 명확화 질문 (Clarifying Questions)\n\n")
            for (question in plan.questions) {
                sb.append("- **Q**: ${question.question}\n")
                sb.append("  - **왜 중요한가**: ${question.why}\n")
            }
        }

        sb.append("\n## 롤백 계획 (Rollback Plan)\n\n")
        sb.append("**실행 조건**: ${plan.rollbackPlan.trigger}\n\n")
        sb.append("**단계**:\n")
        for ((index, step) in plan.rollbackPlan.steps.withIndex()) {
            sb.append("${index + 1}. $step\n")
        }

        sb.append("\n---\n*${plan.disclaimer}*\n")

        return sb.toString()
    }

    fun formatForDiscord(result: AiSreService.AiAnalysisResult): String {
        return """
            **🤖 AI SRE 분석**

            **🔍 근본 원인**: ${result.rootCause}
            **⚠️ 심각도**: ${result.severity}
            **🎯 영향 컴포넌트**: ${result.affectedComponents}

            **📋 조치사항**:
            ${indentActionItems(result.actionItems)}

            ---
            *출처: ${result.analysisSource} | ${result.disclaimer}*
            """.trimIndent()
    }

    fun formatForDiscord(plan: AiSreService.MitigationPlan): String {
        val sb = StringBuilder()

        sb.append("**🚨 인시던트 완화 계획 (ID: ${plan.incidentId})**\n\n")

        sb.append("**🔍 원인 가설**:\n")
        plan.hypotheses.stream()
            .limit(3)
            .forEach { h ->
                val truncatedCause = if (h.cause.length > 50) h.cause.substring(0, 50) + "..." else h.cause
                sb.append("- $truncatedCause (${h.confidence})\n")
            }

        sb.append("\n**📋 조치 계획**:\n")
        plan.actions.stream()
            .limit(3)
            .forEach { a ->
                val truncatedAction = if (a.action.length > 50) a.action.substring(0, 50) + "..." else a.action
                sb.append("${a.step}. $truncatedAction (위험도: ${a.risk})\n")
            }

        sb.append("\n*출처: ${plan.analysisSource}*\n")

        return sb.toString()
    }

    private fun indentActionItems(actionItems: String): String {
        if (actionItems.isBlank()) {
            return "- 조치사항 없음"
        }

        if (actionItems.contains("1.") || actionItems.contains("- ")) {
            return actionItems
        }

        return actionItems.split("\n").joinToString("\n").replace(Regex("^(?!-)"), "- ")
    }

    private fun formatEvidenceList(evidence: List<String>): String {
        if (evidence.isEmpty()) {
            return "증거 없음"
        }

        val sb = StringBuilder()
        for (item in evidence) {
            sb.append("- $item\n")
        }
        return sb.toString()
    }
}
