package maple.expectation.infrastructure.monitoring.security

import java.util.regex.Pattern
import org.springframework.stereotype.Component

/**
 * PII 마스킹 필터 (Issue #251)
 *
 * <h3>[P0-Purple] 보안 요구사항</h3>
 *
 * <p>AI 분석 전송 전 민감 정보를 마스킹합니다.
 *
 * <h4>마스킹 대상</h4>
 *
 * <ul>
 *   <li>이메일 주소
 *   <li>IP 주소
 *   <li>UUID (userId, requestId 등)
 *   <li>API 키 패턴
 *   <li>JWT 토큰
 * </ul>
 */
@Component
class PiiMaskingFilter {

    companion object {
        private val EMAIL_PATTERN: Pattern =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        private val IPV4_PATTERN: Pattern = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        private val UUID_PATTERN: Pattern =
            Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        private val API_KEY_PATTERN: Pattern =
            Pattern.compile("(?i)(api[_-]?key|apikey|secret|password|token)\\s*[:=]\\s*['\\\"]?[^'\"\\s]{8,}['\\\"]?")
        private val JWT_PATTERN: Pattern =
            Pattern.compile("eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*")
        private val BEARER_PATTERN: Pattern = Pattern.compile("(?i)bearer\\s+[a-zA-Z0-9._-]+")
    }

    fun mask(input: String?): String? {
        if (input.isNullOrEmpty()) return input

        var result = input
        result = JWT_PATTERN.matcher(result).replaceAll("[JWT_MASKED]")
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [TOKEN_MASKED]")
        result = API_KEY_PATTERN.matcher(result).replaceAll("\$1: [REDACTED]")
        result = EMAIL_PATTERN.matcher(result).replaceAll("[EMAIL_MASKED]")
        result = IPV4_PATTERN.matcher(result).replaceAll("[IP_MASKED]")
        result = UUID_PATTERN.matcher(result).replaceAll("[UUID_MASKED]")
        return result
    }

    fun maskStackTrace(stackTrace: String?): String? {
        if (stackTrace == null) return null
        var masked = mask(stackTrace)!!
        masked = masked.replace(Regex("/home/[^/]+/"), "/home/[USER]/")
        masked = masked.replace(Regex("/Users/[^/]+/"), "/Users/[USER]/")
        masked = masked.replace(Regex("C:\\\\\\\\Users\\\\\\\\[^\\\\\\\\]+\\\\\\\\"), "C:\\\\\\\\Users\\\\\\\\[USER]\\\\\\\\")
        return masked
    }

    fun maskExceptionMessage(exception: Throwable?): String {
        if (exception == null) return "Unknown error"
        val message = exception.message
        if (message == null) return exception.javaClass.simpleName
        return mask(message)!!
    }
}
