package maple.expectation.infrastructure.cache.equipment

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * Equipment 데이터의 fingerprint 생성기
 *
 * updatedAt을 epoch second로 변환하여 캐시 키에 사용합니다.
 *
 * null 처리 정책: updatedAt이 null이면 "0"을 반환하고 메트릭에 기록합니다.
 */
@Component
class EquipmentFingerprintGenerator(
    private val checkedExecutor: CheckedLogicExecutor,
    meterRegistry: MeterRegistry
) {
    private val fingerprintNullCounter: Counter = Counter.builder("expectation.fingerprint.null.count")
        .description("updatedAt이 null인 fingerprint 생성 횟수")
        .register(meterRegistry)

    /**
     * updatedAt을 epoch second로 변환
     *
     * @param updatedAt equipment의 마지막 업데이트 시각
     * @return epoch second 문자열 (null이면 "0")
     */
    fun generate(updatedAt: LocalDateTime?): String {
        if (updatedAt == null) {
            log.debug("[Fingerprint] updatedAt is null, using '0'")
            fingerprintNullCounter.increment()
            return "0"
        }

        val epochSecond = updatedAt.toEpochSecond(ZoneOffset.UTC)
        return epochSecond.toString()
    }

    /**
     * 테이블 버전을 SHA-256 URL-safe 해시로 변환
     *
     * 금융수준 캐시 키 충돌 방지를 위해 SHA-256 사용
     *
     * @param tableVersion 원본 테이블 버전 문자열
     * @return SHA-256 해시 앞 8자 (base64url, 충돌 확률 극히 낮음)
     */
    fun hashTableVersion(tableVersion: String?): String {
        if (tableVersion.isNullOrEmpty()) {
            return "00000000"
        }

        val hash = sha256(tableVersion)
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        return base64.substring(0, 8)
    }

    /**
     * SHA-256 해시 (thread-safe)
     */
    private fun sha256(input: String): ByteArray {
        return checkedExecutor.executeUnchecked(
            task = {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.digest(input.toByteArray(StandardCharsets.UTF_8))
            },
            context = maple.expectation.infrastructure.executor.TaskContext.of("Fingerprint", "Sha256", input),
            mapper = { e -> IllegalStateException("SHA-256 algorithm not available", e) }
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(EquipmentFingerprintGenerator::class.java)
    }
}
