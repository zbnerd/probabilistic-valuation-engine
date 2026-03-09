package maple.expectation.infrastructure.monitoring.ai

import dev.langchain4j.model.input.PromptTemplate
import maple.expectation.infrastructure.monitoring.copilot.model.AnomalyEvent
import maple.expectation.infrastructure.monitoring.copilot.model.EvidenceItem
import maple.expectation.infrastructure.monitoring.copilot.model.IncidentContext
import maple.expectation.infrastructure.monitoring.security.PiiMaskingFilter
import org.springframework.stereotype.Component

@Component
class AiPromptBuilder(
    private val piiFilter: PiiMaskingFilter,
) {
    companion object {
        private const val SYSTEM_PROMPT = """
            You are an SRE expert for MapleExpectation system.

            Architecture Context:
            - TieredCache: Caffeine (L1) → Redis (L2) → MySQL (L3)
            - CircuitBreaker: Resilience4j (nexonApi, likeSyncDb, redisLock, openAiApi)
            - Virtual Threads: Java 21 enabled
            - Distributed Lock: Redis (primary) → MySQL Named Lock (fallback)
            - Buffer Pattern: In-memory → Redis → DB (eventual consistency)

            Analyze the error and provide:
            1) **Root Cause**: 근본 원인 (한 문장)
            2) **Severity**: CRITICAL / HIGH / MEDIUM / LOW
            3) **Affected Components**: 영향받는 컴포넌트 목록
            4) **Action Items**: 즉시 조치사항 (번호 목록)

            Be concise. Response in Korean.
            """

        private val ANALYSIS_TEMPLATE = PromptTemplate.from(
            """
            Error Information:
            - Type: {{errorType}}
            - Message: {{errorMessage}}
            - Stack Trace (top 5):
            {{stackTrace}}

            System Context:
            {{systemContext}}

            Analyze this error and provide actionable insights.
            """,
        )

        private const val INCIDENT_ANALYSIS_SYSTEM_PROMPT = """
            You are an SRE incident commander for MapleExpectation system.

            Architecture Context:
            - TieredCache: Caffeine (L1) → Redis (L2) → MySQL (L3)
            - CircuitBreaker: Resilience4j (nexonApi, likeSyncDb, redisLock, openAiApi)
            - Virtual Threads: Java 21 enabled
            - Distributed Lock: Redis (primary) → MySQL Named Lock (fallback)
            - Buffer Pattern: In-memory → Redis → DB (eventual consistency)
            - Chaos Engineering: Nightmare tests N01-N18

            Task: Analyze the incident and provide a mitigation plan.

            Response Format (JSON):
            {
              "hypotheses": [
                {
                  "cause": "Root cause description",
                  "confidence": "HIGH/MEDIUM/LOW",
                  "evidence": ["Supporting evidence 1", "evidence 2"]
                }
              ],
              "actions": [
                {
                  "step": 1,
                  "action": "Action description",
                  "risk": "HIGH/MEDIUM/LOW",
                  "expectedOutcome": "Expected result"
                }
              ],
              "questions": [
                {
                  "question": "Clarifying question",
                  "why": "Why this matters"
                }
              ],
              "rollbackPlan": {
                "trigger": "When to rollback",
                "steps": ["Rollback step 1", "step 2"]
              }
            }

            Be specific and actionable. Response in Korean.
            """

        private val INCIDENT_ANALYSIS_TEMPLATE = PromptTemplate.from(
            """
            Incident Summary: {{summary}}
            Incident ID: {{incidentId}}

            Anomaly Events ({{anomalyCount}} detected):
            {{anomalies}}

            Evidence Items ({{evidenceCount}} items):
            {{evidence}}

            System Context:
            {{systemContext}}

            Additional Metadata:
            {{metadata}}

            Analyze this incident and provide a structured mitigation plan in JSON format.
            """,
        )
    }

    fun buildAnalysisPrompt(
        exception: Throwable,
        maskedStackTrace: String,
        systemContext: String,
    ): PromptWithSystem {
        val maskedMessage = piiFilter.maskExceptionMessage(exception)
        val maskedContext = piiFilter.mask(systemContext)

        val prompt = ANALYSIS_TEMPLATE.apply(
            mapOf(
                "errorType" to exception.javaClass.simpleName,
                "errorMessage" to maskedMessage,
                "stackTrace" to maskedStackTrace,
                "systemContext" to maskedContext,
            ),
        )

        return PromptWithSystem(SYSTEM_PROMPT, prompt.text())
    }

    fun buildIncidentAnalysisPrompt(
        context: IncidentContext,
        systemContext: String,
    ): PromptWithSystem {
        val anomaliesText = formatAnomalies(context.anomalies)
        val evidenceText = formatEvidence(context.evidence)
        val metadataText = formatMetadata(context.metadata)

        val maskedAnomalies = piiFilter.mask(anomaliesText)
        val maskedEvidence = piiFilter.mask(evidenceText)
        val maskedContext = piiFilter.mask(systemContext)
        val maskedMetadata = piiFilter.mask(metadataText)

        val prompt = INCIDENT_ANALYSIS_TEMPLATE.apply(
            mapOf(
                "summary" to context.summary,
                "incidentId" to context.incidentId,
                "anomalyCount" to context.anomalies.size,
                "anomalies" to maskedAnomalies,
                "evidenceCount" to context.evidence.size,
                "evidence" to maskedEvidence,
                "systemContext" to maskedContext,
                "metadata" to maskedMetadata,
            ),
        )

        return PromptWithSystem(INCIDENT_ANALYSIS_SYSTEM_PROMPT, prompt.text())
    }

    private fun formatAnomalies(anomalies: List<AnomalyEvent>): String {
        if (anomalies.isEmpty()) {
            return "이상 징후 없음"
        }

        val sb = StringBuilder()
        for ((index, anomaly) in anomalies.withIndex()) {
            sb.append(
                """
                |[${index + 1}] ${anomaly.signalId}
                |    - 심각도: ${anomaly.severity}
                |    - 사유: ${anomaly.reason}
                |    - 감지 시각: ${anomaly.detectedAtMillis}
                |    - 현재값: ${anomaly.currentValue} (기준: ${anomaly.baselineValue})
                """.trimMargin(),
            )
        }
        return sb.toString()
    }

    private fun formatEvidence(evidence: List<*>): String {
        if (evidence.isEmpty()) {
            return "증거 없음"
        }

        val sb = StringBuilder()
        for ((index, item) in evidence.withIndex()) {
            when (item) {
                is EvidenceItem -> {
                    sb.append(
                        """
                        |[${index + 1}] ${item.title} (${item.type})
                        |    ${item.body}
                        """.trimMargin(),
                    )
                }
                is maple.expectation.infrastructure.monitoring.copilot.model.RichEvidence -> {
                    sb.append(
                        """
                        |[${index + 1}] ${item.signalName} (PromQL Evidence)
                        |    Current: ${item.currentValue}, Baseline: ${item.baselineValue}, Deviation: ${item.formattedDeviation()}
                        |    Query: ${item.promql}
                        """.trimMargin(),
                    )
                }
                else -> {
                    sb.append("[${index + 1}] $item\n")
                }
            }
        }
        return sb.toString()
    }

    private fun formatMetadata(metadata: Map<String, Any>): String {
        if (metadata.isEmpty()) {
            return "추가 정보 없음"
        }

        val sb = StringBuilder()
        for ((key, value) in metadata) {
            sb.append("- $key: $value\n")
        }
        return sb.toString()
    }

    data class PromptWithSystem(val systemPrompt: String, val userPrompt: String)
}
