package maple.expectation.infrastructure.monitoring.ai

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.Optional
import java.util.concurrent.CompletableFuture

@Service
@ConditionalOnProperty(name = ["ai.sre.enabled"], havingValue = "false", matchIfMissing = true)
class NoOpAiSreService {
    companion object {
        private val log = LoggerFactory.getLogger(NoOpAiSreService::class.java)
    }

    fun analyzeErrorAsync(exception: Throwable): CompletableFuture<Optional<AiSreService.AiAnalysisResult>> {
        return CompletableFuture.completedFuture(Optional.empty())
    }

    fun analyzeError(exception: Throwable): Optional<AiSreService.AiAnalysisResult> {
        log.debug("[NoOpAiSre] AI SRE 비활성화 상태 - 분석 스킵: {}", exception.javaClass.simpleName)
        return Optional.empty()
    }
}
