package maple.expectation.infrastructure.monitoring.ai.config

import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.openai.OpenAiChatModel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ZAiConfiguration {
    companion object {
        private val log = LoggerFactory.getLogger(ZAiConfiguration::class.java)
    }

    @Value("\${langchain4j.glm-4.chat-model.base-url}")
    private lateinit var baseUrl: String

    @Value("\${langchain4j.glm-4.chat-model.api-key:#{null}}")
    private var apiKey: String? = null

    @Value("\${langchain4j.glm-4.chat-model.model-name:glm-4.7}")
    private var modelName: String = "glm-4.7"

    @Value("\${langchain4j.glm-4.chat-model.timeout:60s}")
    private var timeout: String = "60s"

    @Value("\${langchain4j.glm-4.chat-model.log-requests:false}")
    private var logRequests: Boolean = false

    @Value("\${langchain4j.glm-4.chat-model.log-responses:false}")
    private var logResponses: Boolean = false

    @Bean
    @ConditionalOnProperty(name = ["langchain4j.glm-4.chat-model.api-key"])
    fun zAiChatModel(): ChatLanguageModel? {
        if (apiKey.isNullOrBlank()) {
            log.warn("[Z.ai] API 키가 미설정이어서 빈을 반환합니다.")
            return null
        }

        log.info("[Z.ai] GLM-4.7 모델 초기화: baseUrl={}, model={}", baseUrl, modelName)

        return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .timeout(java.time.Duration.parse("PT" + timeout))
            .logRequests(logRequests)
            .logResponses(logResponses)
            .build()
    }
}
