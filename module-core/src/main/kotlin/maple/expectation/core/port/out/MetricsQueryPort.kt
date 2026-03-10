package maple.expectation.core.port.out

import java.time.Instant

/**
 * Prometheus 메트릭 쿼리 Port 인터페이스 (ADR-005)
 *
 * <p>책임: Prometheus 메트릭 데이터 조회
 *
 * <p>구현체:
 * <ul>
 *   <li>module-infra/monitoring/prometheus/MetricsQueryPortAdapter
 * </ul>
 */
interface MetricsQueryPort {

    /**
     * 시간 범위 메트릭 쿼리
     *
     * @param promql PromQL 쿼리 문자열
     * @param start 시작 시간
     * @param end 종료 시간
     * @param step 쿼리 간격 (예: "15s", "1m", "5m")
     * @return 메트릭 시계열 데이터 리스트
     */
    fun queryRange(promql: String, start: Instant, end: Instant, step: String): List<MetricTimeSeries>

    /**
     * 메트릭 시계열 데이터
     *
     * @param metric 메트릭 라벨 맵
     * @param values 데이터 포인트 리스트
     */
    data class MetricTimeSeries(
        val metric: Map<String, String>,
        val values: List<MetricValuePoint>,
    )

    /**
     * 메트릭 데이터 포인트
     *
     * @param timestamp 타임스탬프 (에포크 초)
     * @param value 값
     */
    data class MetricValuePoint(
        val timestamp: Long,
        val value: String,
    ) {
        /**
         * 값을 Double로 파싱
         */
        fun getValueAsDouble(): Double = value.toDoubleOrNull() ?: 0.0

        /**
         * 타임스탬프를 Instant로 변환
         */
        fun getTimestampAsInstant(): Instant = Instant.ofEpochSecond(timestamp)
    }
}
