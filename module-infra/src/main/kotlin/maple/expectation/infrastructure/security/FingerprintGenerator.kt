package maple.expectation.infrastructure.security

import maple.expectation.common.util.FingerprintUtil
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class FingerprintGenerator(
    @Value("\${auth.fingerprint.secret}") serverSecret: String,
    private val executor: LogicExecutor,
) {
    private val serverSecret: String

    init {
        requireNotNull(serverSecret) { "auth.fingerprint.secret must not be null" }
        this.serverSecret = serverSecret
    }

    fun generate(apiKey: String?): String {
        validateApiKey(apiKey)
        val key = requireNotNull(apiKey) { "apiKey must not be null after validation" }
        val context = TaskContext.of("Fingerprint", "ComputeHmac", "***")
        return executor.execute({ FingerprintUtil.generate(key, serverSecret) }, context)
    }

    fun verify(apiKey: String?, fingerprint: String?): Boolean {
        if (apiKey == null || fingerprint == null) return false
        return FingerprintUtil.verify(apiKey, fingerprint, serverSecret)
    }

    private fun validateApiKey(apiKey: String?) {
        requireNotNull(apiKey) { "apiKey must not be null" }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }
}
