package maple.expectation.infrastructure.cache.equipment

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Equipment 데이터의 fingerprint 생성기
 *
 * updatedAt을 epoch second로 변환하여 캐시 키에 사용합니다.
 *
 * null 처리 정책:
 * updatedAt이 null이면 "0"을 반환하고 메트릭에 기록합니다.
 * 이 경우 TTL 기반으로 캐시가 무효화됩니다.
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
@Component
class EquipmentFingerprintGenerator(
    private val checkedExecutor: CheckedLogicExecutor,
    meterRegistry: MeterRegistry,
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

        // UTC 기준으로 epoch second 변환 (환경별 일관성 보장)
        val epochSecond = updatedAt.toEpochSecond(ZoneOffset.UTC)
        return epochSecond.toString()
    }

    /**
     * 테이블 버전을 SHA-256 URL-safe 해시로 변환 (충돌 방지)
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
        // Base64 URL-safe 인코딩 후 앞 8자만 사용 (48bit, 충돌 확률 ~1/281조)
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        return base64.substring(0, 8)
    }

    /**
     * SHA-256 해시 (thread-safe)
     *
     * NoSuchAlgorithmException은 JVM에서 SHA-256을 항상 지원하므로 사실상 발생하지 않지만,
     * checked 예외이므로 CheckedLogicExecutor로 처리합니다.
     */
    private fun sha256(input: String): ByteArray {
        val context = TaskContext.of("FingerprintGenerator", "Sha256", input.take(10))
        return checkedExecutor.executeUnchecked(
            { MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8)) },
            context,
        ) { e -> IllegalStateException("SHA-256 algorithm not available", e) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(EquipmentFingerprintGenerator::class.java)
    }
}
