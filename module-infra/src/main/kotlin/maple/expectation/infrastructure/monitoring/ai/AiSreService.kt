package maple.expectation.infrastructure.monitoring.ai

import dev.langchain4j.model.chat.ChatLanguageModel
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.context.SystemContextProvider
import maple.expectation.infrastructure.monitoring.copilot.model.IncidentContext
import maple.expectation.infrastructure.monitoring.security.PiiMaskingFilter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["ai.sre.enabled"], havingValue = "true")
class AiSreService(
    private val chatModel: ChatLanguageModel,
    private val contextProvider: SystemContextProvider,
    private val piiFilter: PiiMaskingFilter,
    private val executor: LogicExecutor,
    @Qualifier("aiTaskExecutor") private val aiTaskExecutor: Executor,
    private val promptBuilder: AiPromptBuilder,
    private val responseParser: AiResponseParser,
) {
    companion object {
        private val log = LoggerFactory.getLogger(AiSreService::class.java)
    }

    fun analyzeErrorAsync(exception: Throwable): CompletableFuture<Optional<AiAnalysisResult>> = CompletableFuture.supplyAsync({ analyzeError(exception) }, aiTaskExecutor)

    @CircuitBreaker(name = "openAiApi", fallbackMethod = "fallbackAnalysis")
    fun analyzeError(exception: Throwable): Optional<AiAnalysisResult> {
        val context = TaskContext.of("AiSre", "AnalyzeError", exception.javaClass.simpleName)

        // V5 Migration (Issue #589): Redis-based throttling removed
        // Always proceed with analysis when enabled

        return executor.executeOrDefault(
            { performAnalysisInternal(exception) },
            Optional.empty(),
            context,
        )
    }

    private fun performAnalysisInternal(exception: Throwable): Optional<AiAnalysisResult> {
        val systemContext = contextProvider.buildContextForAi()
        val maskedStackTrace = piiFilter.maskStackTrace(getTopStackTrace(exception, 5)) ?: ""

        val prompt = promptBuilder.buildAnalysisPrompt(exception, maskedStackTrace, systemContext)
        val combinedPrompt = prompt.systemPrompt + "\n\n" + prompt.userPrompt

        val response = chatModel.generate(combinedPrompt)
            ?: return Optional.empty()

        return Optional.of(responseParser.parseAiResponse(response, exception))
    }

    @Suppress("UNUSED")
    fun fallbackAnalysis(exception: Throwable, cause: Throwable): Optional<AiAnalysisResult> {
        log.warn("[AiSre] LLM 분석 실패, 규칙 기반 분석으로 전환: {}", cause.message)

        val errorType = exception.javaClass.simpleName
        val message = exception.message ?: "Unknown error"

        val rootCause = analyzeByKeyword(errorType, message)
        val severity = determineSeverity(errorType, message)

        return Optional.of(
            AiAnalysisResult.builder()
                .rootCause(rootCause)
                .severity(severity)
                .affectedComponents(inferAffectedComponents(errorType))
                .actionItems(suggestActions(errorType))
                .analysisSource("RULE_BASED")
                .disclaimer("규칙 기반 분석 결과입니다. 수동 검증을 권장합니다.")
                .build(),
        )
    }

    private fun analyzeByKeyword(errorType: String, message: String): String {
        val combined = ("$errorType $message").lowercase()

        return when {
            combined.contains("timeout") || combined.contains("timed out") -> "타임아웃 발생 - 외부 서비스 응답 지연 또는 네트워크 문제"
            combined.contains("connection") && combined.contains("refused") -> "연결 거부 - 대상 서비스 다운 또는 방화벽 차단"
            combined.contains("circuit") && combined.contains("open") -> "서킷브레이커 오픈 - 연속 장애로 보호 모드 진입"
            combined.contains("redis") || combined.contains("redisson") -> "Redis 관련 오류 - 캐시/락 서비스 문제"
            combined.contains("hikari") || combined.contains("connection pool") -> "DB 커넥션 풀 문제 - 커넥션 부족 또는 누수 의심"
            combined.contains("outofmemory") || combined.contains("heap") -> "메모리 부족 - JVM 힙 메모리 소진"
            combined.contains("thread") && combined.contains("exhaust") -> "스레드 풀 고갈 - 동시 요청 초과"
            else -> "원인 분석 필요 - 수동 점검 권장"
        }
    }

    private fun determineSeverity(errorType: String, message: String): String {
        val combined = ("$errorType $message").lowercase()

        return when {
            combined.contains("outofmemory") || combined.contains("critical") -> "CRITICAL"
            combined.contains("circuit") || combined.contains("pool exhausted") -> "HIGH"
            combined.contains("timeout") || combined.contains("connection") -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun inferAffectedComponents(errorType: String): String = when {
        errorType.contains("Redis") -> "Redis, TieredCache, LockStrategy"
        errorType.contains("Hikari") || errorType.contains("DataSource") -> "MySQL, Repository Layer"
        errorType.contains("Nexon") || errorType.contains("External") -> "NexonApiClient, ExternalService"
        errorType.contains("CircuitBreaker") -> "Resilience4j, Service Layer"
        else -> "Unknown"
    }

    private fun suggestActions(errorType: String): String = when {
        errorType.contains("Timeout") -> "1. 대상 서비스 상태 확인\n2. 네트워크 지연 점검\n3. 타임아웃 값 검토"
        errorType.contains("Connection") -> "1. 연결 대상 서비스 확인\n2. 방화벽/보안그룹 점검\n3. DNS 확인"
        errorType.contains("Circuit") -> "1. 서킷브레이커 상태 확인\n2. 장애 원인 파악\n3. 수동 리셋 고려"
        else -> "1. 로그 상세 확인\n2. 메트릭 모니터링\n3. 개발팀 에스컬레이션"
    }

    private fun getTopStackTrace(exception: Throwable, count: Int): String {
        val elements = exception.stackTrace
        val sb = StringBuilder()

        for (i in 0 until Math.min(count, elements.size)) {
            sb.append("  at ").append(elements[i]).append("\n")
        }

        return sb.toString()
    }

    @CircuitBreaker(name = "openAiApi", fallbackMethod = "fallbackIncidentAnalysis")
    fun analyzeIncident(context: IncidentContext): MitigationPlan {
        val taskContext = TaskContext.of("AiSre", "AnalyzeIncident", context.incidentId)

        return executor.executeOrDefault(
            { performIncidentAnalysisInternal(context) },
            createDefaultMitigationPlan(context),
            taskContext,
        )
    }

    private fun performIncidentAnalysisInternal(context: IncidentContext): MitigationPlan {
        val systemContext = contextProvider.buildContextForAi()

        val prompt = promptBuilder.buildIncidentAnalysisPrompt(context, systemContext)

        val response = chatModel.generate(prompt.systemPrompt + "\n\n" + prompt.userPrompt)

        return responseParser.parseMitigationPlanJson(response, context.incidentId)
    }

    @Suppress("UNUSED")
    private fun fallbackIncidentAnalysis(context: IncidentContext, cause: Throwable): MitigationPlan {
        log.warn("[AiSre] LLM 인시던트 분석 실패, 기본 계획 사용: incidentId={}, cause={}", context.incidentId, cause.message)

        return createDefaultMitigationPlan(context)
    }

    private fun createDefaultMitigationPlan(context: IncidentContext): MitigationPlan {
        val defaultHypotheses = listOf(
            Hypothesis("자동 분석 불가 - 수동 점검 필요", "LOW", listOf("LLM 분석 실패", "시스템 로그 수동 확인 필요")),
        )

        val defaultActions = listOf(
            Action(1, "시스템 로그 확인", "LOW", "현재 상태 파악"),
            Action(2, "메트릭 모니터링", "LOW", "주요 지표 추적"),
            Action(3, "개발팀 에스컬레이션", "LOW", "수동 분석 의뢰"),
        )

        val defaultQuestions = listOf(
            ClarifyingQuestion("인시던트 발생 시점에 배포가 있었나요?", "배포 관련 문제 확인"),
        )

        val rollbackPlan = RollbackPlan("상태 악화 시 즉시 실행", listOf("이전 커밋으로 롤백", "영향도 재평가"))

        return MitigationPlan(
            context.incidentId,
            "RULE_BASED_FALLBACK",
            defaultHypotheses,
            defaultActions,
            defaultQuestions,
            rollbackPlan,
            "AI 분석 실패로 인한 기본 계획입니다. 수동 검증이 필수입니다.",
        )
    }

    data class AiAnalysisResult(
        val rootCause: String,
        val severity: String,
        val affectedComponents: String,
        val actionItems: String,
        val analysisSource: String,
        val disclaimer: String,
    ) {
        companion object {
            @JvmStatic
            fun builder(): AiAnalysisResultBuilder = AiAnalysisResultBuilder()
        }
    }

    class AiAnalysisResultBuilder {
        private var rootCause: String? = null
        private var severity: String? = null
        private var affectedComponents: String? = null
        private var actionItems: String? = null
        private var analysisSource: String? = null
        private var disclaimer: String? = null

        fun rootCause(rootCause: String): AiAnalysisResultBuilder {
            this.rootCause = rootCause
            return this
        }

        fun severity(severity: String): AiAnalysisResultBuilder {
            this.severity = severity
            return this
        }

        fun affectedComponents(affectedComponents: String): AiAnalysisResultBuilder {
            this.affectedComponents = affectedComponents
            return this
        }

        fun actionItems(actionItems: String): AiAnalysisResultBuilder {
            this.actionItems = actionItems
            return this
        }

        fun analysisSource(analysisSource: String): AiAnalysisResultBuilder {
            this.analysisSource = analysisSource
            return this
        }

        fun disclaimer(disclaimer: String): AiAnalysisResultBuilder {
            this.disclaimer = disclaimer
            return this
        }

        fun build(): AiAnalysisResult = AiAnalysisResult(
            rootCause ?: "",
            severity ?: "",
            affectedComponents ?: "",
            actionItems ?: "",
            analysisSource ?: "",
            disclaimer ?: "",
        )
    }

    data class MitigationPlan(
        val incidentId: String,
        val analysisSource: String,
        val hypotheses: List<Hypothesis>,
        val actions: List<Action>,
        val questions: List<ClarifyingQuestion>,
        val rollbackPlan: RollbackPlan,
        val disclaimer: String,
    )

    data class Hypothesis(
        val cause: String,
        val confidence: String,
        val evidence: List<String>,
    )

    data class Action(
        val step: Int,
        val action: String,
        val risk: String,
        val expectedOutcome: String,
    )

    data class ClarifyingQuestion(
        val question: String,
        val why: String,
    )

    data class RollbackPlan(
        val trigger: String,
        val steps: List<String>,
    )
}
