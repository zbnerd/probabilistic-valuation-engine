package maple.expectation.common.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object FingerprintUtil {
    private const val HMAC_ALGORITHM = "HmacSHA256"

    fun generate(apiKey: String, serverSecret: String): String {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(serverSecret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        val hash = mac.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    fun verify(apiKey: String, fingerprint: String, serverSecret: String): Boolean {
        val computed = generate(apiKey, serverSecret)
        return MessageDigest.isEqual(
            computed.toByteArray(StandardCharsets.UTF_8),
            fingerprint.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
