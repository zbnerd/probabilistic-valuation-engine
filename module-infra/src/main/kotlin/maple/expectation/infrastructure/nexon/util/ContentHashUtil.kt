package maple.expectation.infrastructure.nexon.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * SHA-256 Content Hash 유틸리티
 *
 * <h3>용도</h3>
 * <ul>
 *   <li>Nexon API 메시지 무결성 검증
 *   <li>기존 NexonApiOutbox.computeContentHash()와 동일한 포맷 유지
 * </ul>
 *
 * <h3>포맷</h3>
 * <p>V1: "$requestId|$eventType|$payload" (기존 Outbox 호환)
 */
object ContentHashUtil {

    private val digestCache = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-256")
    }

    /**
     * V1: 기존 NexonApiOutbox.computeContentHash()와 동일한 포맷.
     * 마이그레이션 전환 중 기존 해시와의 호환성을 위해 반드시 동일해야 함.
     * 포맷: "$requestId|$eventType|$payload"
     */
    fun computeV1(requestId: String, eventType: String, payload: String): String {
        val digest = digestCache.get()
        digest.reset()
        val hash = digest.digest(
            ("$requestId|$eventType|$payload").toByteArray(StandardCharsets.UTF_8),
        )
        return HexFormat.of().formatHex(hash)
    }

    /**
     * 무결성 검증
     *
     * @return hash 일치 여부
     */
    fun verify(requestId: String, eventType: String, payload: String, expectedHash: String): Boolean {
        return computeV1(requestId, eventType, payload) == expectedHash
    }
}
