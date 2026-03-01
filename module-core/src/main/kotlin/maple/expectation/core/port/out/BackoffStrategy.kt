package maple.expectation.core.port.out

/**
 * CAS 재시도 대기 전략 인터페이스 (#266 ADR 정합성)
 *
 * <h3>역할</h3>
 *
 * <p>CAS(Compare-And-Swap) 연산 실패 시 재시도 간 대기 시간을 결정합니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>ExponentialBackoff: 1ns부터 시작하여 512ns까지 지수적 증가 (운영 환경)
 *   <li>NoOpBackoff: 대기 없음 (테스트 환경)
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): 전략 패턴으로 OCP 준수
 *   <li>Yellow (QA): 테스트에서 NoOpBackoff로 결정적 테스트 수행
 * </ul>
 */
interface BackoffStrategy {

    /**
     * 재시도 간 대기 시간 계산
     *
     * @param attempt 현재 시도 횟수 (0부터 시작)
     * @return 대기 시간 (나노초)
     */
    fun waitNanos(attempt: Int): Long

    /**
     * 지수 백오프 구현체
     *
     * <p>1ns → 2ns → 4ns → 8ns → 16ns → 32ns → 64ns → 128ns → 256ns → 512ns (최대)
     */
    class ExponentialBackoff : BackoffStrategy {
        override fun waitNanos(attempt: Int): Long {
            // 2^attempt, max 512 (2^9)
            val power = attempt.coerceIn(0, 9)
            return 1L shl power
        }
    }

    /**
     * 대기 없음 구현체 (테스트용)
     */
    class NoOpBackoff : BackoffStrategy {
        override fun waitNanos(attempt: Int): Long = 0L
    }
}
