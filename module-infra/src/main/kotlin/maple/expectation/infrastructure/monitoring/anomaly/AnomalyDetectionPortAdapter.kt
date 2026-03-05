package maple.expectation.infrastructure.monitoring.anomaly

import maple.expectation.core.port.out.AnomalyDetectionPort
import maple.expectation.infrastructure.monitoring.copilot.detector.AnomalyDetector
import maple.expectation.infrastructure.monitoring.copilot.model.SeverityMapping
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition
import maple.expectation.infrastructure.monitoring.copilot.model.TimeSeries
import maple.expectation.infrastructure.monitoring.copilot.model.MetricPoint
import maple.expectation.infrastructure.monitoring.copilot.model.ZScoreConfig
import org.springframework.stereotype.Component
import java.util.Optional

/**
 * AnomalyDetectionPort 구현체 (ADR-005)
 *
 * <p>AnomalyDetector를 래핑하여 Port 인터페이스 구현
 *
 * @param anomalyDetector 이상 탐지 알고리즘 구현체
 */
@Component
class AnomalyDetectionPortAdapter(
    private val anomalyDetector: AnomalyDetector
) : AnomalyDetectionPort {

    override fun detect(
        signal: AnomalyDetectionPort.DetectionSignal,
        timeSeriesList: List<AnomalyDetectionPort.DetectionTimeSeries>,
        nowMillis: Long,
        config: AnomalyDetectionPort.ZScoreDetectionConfig
    ): Optional<AnomalyDetectionPort.DetectedAnomaly> {
        // Port 모델을 내부 모델로 변환
        val internalSignal = SignalDefinition(
            id = signal.id,
            dashboardUid = "",
            panelTitle = signal.panelTitle,
            datasourceType = "Prometheus",
            query = signal.query,
            legend = "",
            unit = signal.unit ?: "",
            severityMapping = signal.severityMapping?.let { sm ->
                SeverityMapping(
                    warnThreshold = sm.warnThreshold ?: 0.0,
                    critThreshold = sm.critThreshold ?: 0.0,
                    comparator = sm.comparator ?: ">"
                )
            } ?: SeverityMapping(warnThreshold = 0.0, critThreshold = 0.0, comparator = ">"),
            sloTag = "",
            metadata = emptyMap()
        )

        val internalTimeSeries = timeSeriesList.map { ts ->
            TimeSeries(
                label = ts.label,
                points = ts.points.map { mp ->
                    MetricPoint(
                        epochMillis = mp.timestampMillis,
                        value = mp.value
                    )
                }
            )
        }

        val internalConfig = ZScoreConfig(
            enabled = config.enabled,
            windowPoints = config.windowPoints,
            threshold = config.threshold,
            minRequiredPoints = config.minRequiredPoints
        )

        // 탐지 수행
        val result = anomalyDetector.detect(internalSignal, internalTimeSeries, nowMillis, internalConfig)

        // 결과를 Port 모델로 변환
        return result.map { anomaly ->
            AnomalyDetectionPort.DetectedAnomaly(
                signalId = anomaly.signalId,
                severity = anomaly.severity,
                reason = anomaly.reason ?: "",
                detectedAtMillis = anomaly.detectedAtMillis,
                currentValue = anomaly.currentValue,
                baselineValue = anomaly.baselineValue
            )
        }
    }
}
