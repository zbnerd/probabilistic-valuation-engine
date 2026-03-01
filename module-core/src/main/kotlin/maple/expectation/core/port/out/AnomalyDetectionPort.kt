package maple.expectation.core.port.out

import java.util.Optional

/**
 * 이상 탐지 Port 인터페이스 (ADR-005)
 *
 * <p>책임: 메트릭 데이터 기반 이상 탐지
 *
 * <p>구현체:
 * <ul>
 *   <li>module-infra/monitoring/anomaly/AnomalyDetectionPortAdapter
 * </ul>
 */
interface AnomalyDetectionPort {

    /**
     * 이상 탐지 수행
     *
     * @param signal 신호 정의
     * @param timeSeriesList 시계열 데이터 리스트
     * @param nowMillis 현재 타임스탬프 (밀리초)
     * @param config Z-Score 설정
     * @return 탐지된 이상 (없으면 empty)
     */
    fun detect(
        signal: DetectionSignal,
        timeSeriesList: List<DetectionTimeSeries>,
        nowMillis: Long,
        config: ZScoreDetectionConfig
    ): Optional<DetectedAnomaly>

    /**
     * 탐지 신호 정의
     *
     * @param id 신호 ID
     * @param panelTitle 패널 제목
     * @param query PromQL 쿼리
     * @param unit 단위
     * @param severityMapping 심각도 매핑
     */
    data class DetectionSignal(
        val id: String,
        val panelTitle: String,
        val query: String,
        val unit: String?,
        val severityMapping: SeverityMapping?
    )

    /**
     * 심각도 매핑
     *
     * @param warnThreshold 경고 임계값
     * @param critThreshold 위험 임계값
     * @param comparator 비교 연산자
     */
    data class SeverityMapping(
        val warnThreshold: Double?,
        val critThreshold: Double?,
        val comparator: String?
    )

    /**
     * 탐지용 시계열 데이터
     *
     * @param label 라벨
     * @param points 데이터 포인트 리스트
     */
    data class DetectionTimeSeries(
        val label: String,
        val points: List<DetectionMetricPoint>
    )

    /**
     * 탐지용 메트릭 포인트
     *
     * @param timestampMillis 타임스탬프 (밀리초)
     * @param value 값
     */
    data class DetectionMetricPoint(
        val timestampMillis: Long,
        val value: Double
    )

    /**
     * Z-Score 탐지 설정
     *
     * @param enabled 활성화 여부
     * @param windowPoints 윈도우 포인트 수
     * @param threshold 임계값 (기본 3.0)
     * @param minRequiredPoints 최소 필요 포인트 수
     */
    data class ZScoreDetectionConfig(
        val enabled: Boolean = true,
        val windowPoints: Int = 60,
        val threshold: Double = 3.0,
        val minRequiredPoints: Int = 10
    )

    /**
     * 탐지된 이상
     *
     * @param signalId 신호 ID
     * @param severity 심각도 (CRIT/WARN)
     * @param reason 사유
     * @param detectedAtMillis 탐지 시각
     * @param currentValue 현재 값
     * @param baselineValue 기준 값
     */
    data class DetectedAnomaly(
        val signalId: String,
        val severity: String,
        val reason: String,
        val detectedAtMillis: Long,
        val currentValue: Double,
        val baselineValue: Double?
    )
}
