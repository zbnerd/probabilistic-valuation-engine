package maple.expectation.infrastructure.monitoring.ai.config

import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.openai.OpenAiChatModel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
@ConditionalOnProperty(name = ["ai.sre.enabled"], havingValue = "true")
class OpenAIConfiguration {
    companion object {
        private val log = LoggerFactory.getLogger(OpenAIConfiguration::class.java)
    }

    @Value("\${langchain4j.open-ai.chat-model.api-key:}")
    private var apiKey: String = ""

    @Value("\${langchain4j.open-ai.chat-model.model-name:gpt-4o-mini}")
    private var modelName: String = "gpt-4o-mini"

    @Value("\${langchain4j.open-ai.chat-model.timeout:60s}")
    private var timeout: String = "60s"

    @Value("\${langchain4j.open-ai.chat-model.log-requests:false}")
    private var logRequests: Boolean = false

    @Value("\${langchain4j.open-ai.chat-model.log-responses:false}")
    private var logResponses: Boolean = false

    @Bean
    @Primary
    @ConditionalOnProperty(name = ["langchain4j.open-ai.chat-model.api-key"], matchIfMissing = false)
    fun openAIChatModel(): ChatLanguageModel {
        log.info("[OpenAI] GPT 모델 초기화: model={}", modelName)

        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .timeout(java.time.Duration.parse("PT" + timeout))
            .logRequests(logRequests)
            .logResponses(logResponses)
            .build()
    }
}
