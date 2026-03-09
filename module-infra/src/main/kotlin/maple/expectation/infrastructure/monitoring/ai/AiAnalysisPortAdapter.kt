package maple.expectation.infrastructure.monitoring.ai

import java.util.Optional
import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.out.AiAnalysisPort
import maple.expectation.infrastructure.monitoring.copilot.model.IncidentContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * AiAnalysisPort 구현체 (ADR-005)
 *
 * <p>AiSreService를 래핑하여 Port 인터페이스 구현
 *
 * @param aiSreService AI SRE 분석 서비스
 */
@Component
@ConditionalOnProperty(name = ["ai.sre.enabled"], havingValue = "true")
class AiAnalysisPortAdapter(
    private val aiSreService: AiSreService,
) : AiAnalysisPort {

    override fun analyzeErrorAsync(exception: Throwable): CompletableFuture<Optional<AiAnalysisPort.AiAnalysisResult>> = aiSreService.analyzeErrorAsync(exception)
        .thenApply { optionalResult ->
            optionalResult.map { result ->
                AiAnalysisPort.AiAnalysisResult(
                    rootCause = result.rootCause,
                    severity = result.severity,
                    affectedComponents = result.affectedComponents,
                    actionItems = result.actionItems,
                    analysisSource = result.analysisSource,
                    disclaimer = result.disclaimer,
                )
            }
        }

    override fun analyzeError(exception: Throwable): Optional<AiAnalysisPort.AiAnalysisResult> = aiSreService.analyzeError(exception)
        .map { result ->
            AiAnalysisPort.AiAnalysisResult(
                rootCause = result.rootCause,
                severity = result.severity,
                affectedComponents = result.affectedComponents,
                actionItems = result.actionItems,
                analysisSource = result.analysisSource,
                disclaimer = result.disclaimer,
            )
        }

    override fun analyzeIncident(context: AiAnalysisPort.AiIncidentContext): AiAnalysisPort.AiMitigationPlan {
        // 간소화된 컨텍스트를 전체 컨텍스트로 변환
        val fullContext = IncidentContext(
            incidentId = context.incidentId,
            summary = context.summary,
            anomalies = emptyList(),
            evidence = emptyList(),
            metadata = context.metadata,
        )

        val plan = aiSreService.analyzeIncident(fullContext)

        return AiAnalysisPort.AiMitigationPlan(
            incidentId = plan.incidentId,
            analysisSource = plan.analysisSource,
            hypotheses = plan.hypotheses.map { h ->
                AiAnalysisPort.AiHypothesis(
                    cause = h.cause,
                    confidence = h.confidence,
                    evidence = h.evidence,
                )
            },
            actions = plan.actions.map { a ->
                AiAnalysisPort.AiAction(
                    step = a.step,
                    action = a.action,
                    risk = a.risk,
                    expectedOutcome = a.expectedOutcome,
                )
            },
            disclaimer = plan.disclaimer,
        )
    }
}
