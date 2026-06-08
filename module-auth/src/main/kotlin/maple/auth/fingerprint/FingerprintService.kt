package maple.auth.fingerprint

import maple.expectation.common.util.FingerprintUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class FingerprintService(
    @Value("\${auth.fingerprint.secret}") private val serverSecret: String,
) {
    fun generate(apiKey: String): String = FingerprintUtil.generate(apiKey, serverSecret)

    fun verify(apiKey: String, fingerprint: String): Boolean = FingerprintUtil.verify(apiKey, fingerprint, serverSecret)
}
