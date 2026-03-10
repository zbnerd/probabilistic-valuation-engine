package maple.expectation.core.port.out

import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * AI 분석 Port 인터페이스 (ADR-005)
 *
 * <p>책임: AI 기반 에러 분석 및 인시던트 완화 계획 생성
 *
 * <p>구현체:
 * <ul>
 *   <li>module-infra/monitoring/ai/AiAnalysisPortAdapter
 * </ul>
 */
interface AiAnalysisPort {

    /**
     * 에러 분석 (비동기)
     *
     * @param exception 분석할 예외
     * @return AI 분석 결과 (Optional - 실패 시 empty)
     */
    fun analyzeErrorAsync(exception: Throwable): CompletableFuture<Optional<AiAnalysisResult>>

    /**
     * 에러 분석 (동기)
     *
     * @param exception 분석할 예외
     * @return AI 분석 결과 (Optional - 실패 시 empty)
     */
    fun analyzeError(exception: Throwable): Optional<AiAnalysisResult>

    /**
     * 인시던트 분석 및 완화 계획 생성
     *
     * @param context 인시던트 컨텍스트
     * @return 구조화된 완화 계획
     */
    fun analyzeIncident(context: AiIncidentContext): AiMitigationPlan

    /**
     * AI 분석 결과
     *
     * @param rootCause 근본 원인
     * @param severity 심각도 (CRITICAL/HIGH/MEDIUM/LOW)
     * @param affectedComponents 영향받는 컴포넌트
     * @param actionItems 조치사항
     * @param analysisSource 분석 출처 (LLM/RULE_BASED/THROTTLED)
     * @param disclaimer 면책 조항
     */
    data class AiAnalysisResult(
        val rootCause: String,
        val severity: String,
        val affectedComponents: String,
        val actionItems: String,
        val analysisSource: String,
        val disclaimer: String,
    )

    /**
     * 인시던트 컨텍스트 (간소화된 버전)
     *
     * @param incidentId 인시던트 ID
     * @param summary 요약
     * @param metadata 메타데이터
     */
    data class AiIncidentContext(
        val incidentId: String,
        val summary: String,
        val metadata: Map<String, Any> = emptyMap(),
    )

    /**
     * 완화 계획
     *
     * @param incidentId 인시던트 ID
     * @param analysisSource 분석 출처
     * @param hypotheses 가설 리스트
     * @param actions 조치 항목 리스트
     * @param disclaimer 면책 조항
     */
    data class AiMitigationPlan(
        val incidentId: String,
        val analysisSource: String,
        val hypotheses: List<AiHypothesis>,
        val actions: List<AiAction>,
        val disclaimer: String,
    )

    /**
     * 가설 (원인 가설)
     *
     * @param cause 원인 설명
     * @param confidence 신뢰도 (HIGH/MEDIUM/LOW)
     * @param evidence 증거 리스트
     */
    data class AiHypothesis(
        val cause: String,
        val confidence: String,
        val evidence: List<String>,
    )

    /**
     * 조치 항목
     *
     * @param step 단계 번호
     * @param action 조치 내용
     * @param risk 위험도 (HIGH/MEDIUM/LOW)
     * @param expectedOutcome 예상 결과
     */
    data class AiAction(
        val step: Int,
        val action: String,
        val risk: String,
        val expectedOutcome: String,
    )
}
