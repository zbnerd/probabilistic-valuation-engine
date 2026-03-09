package maple.expectation.infrastructure.monitoring.copilot.pipeline

import java.time.Instant
import java.time.temporal.ChronoUnit
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.copilot.client.PrometheusClient
import maple.expectation.infrastructure.monitoring.copilot.detector.AnomalyDetector
import maple.expectation.infrastructure.monitoring.copilot.model.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["monitoring.copilot.enabled"], havingValue = "true")
class AnomalyDetectionOrchestrator(
    private val prometheusClient: PrometheusClient,
    private val detector: AnomalyDetector,
    private val executor: LogicExecutor,
) {
    companion object {
        private val log = LoggerFactory.getLogger(AnomalyDetectionOrchestrator::class.java)
    }

    @Value("\${monitoring.copilot.prometheus.step:15s}")
    var queryStep: String = "15s"

    @Value("\${monitoring.copilot.query-range-seconds:300}")
    var queryRangeSeconds: Int = 300

    fun detectAnomalies(signals: List<SignalDefinition>, currentTimestamp: Long): List<AnomalyEvent> {
        val allAnomalies = mutableListOf<AnomalyEvent>()

        val metrics = queryMetrics(signals, currentTimestamp)

        for (signal in signals) {
            val series = metrics[signal.id]

            if (series.isNullOrEmpty()) {
                log.debug("[AnomalyDetectionOrchestrator] No metrics for signal: {}", signal.id)
                continue
            }

            val anomaly = detector.detect(
                signal,
                series,
                currentTimestamp,
                ZScoreConfig(
                    enabled = true,
                    windowPoints = 60,
                    threshold = 3.0,
                    minRequiredPoints = 10,
                ),
            )

            anomaly.ifPresent { event ->
                allAnomalies.add(event)
                log.debug("[AnomalyDetectionOrchestrator] Anomaly detected: {} = {}", signal.panelTitle, event.currentValue)
            }
        }

        log.info("[AnomalyDetectionOrchestrator] Detection complete: {}/{} signals with anomalies", allAnomalies.size, signals.size)

        return allAnomalies
    }

    private fun queryMetrics(signals: List<SignalDefinition>, currentTimestamp: Long): Map<String, List<TimeSeries>> {
        val metrics = mutableMapOf<String, List<TimeSeries>>()

        val end = Instant.ofEpochMilli(currentTimestamp)
        val start = end.minus(queryRangeSeconds.toLong(), ChronoUnit.SECONDS)

        for (signal in signals) {
            // Skip signals without query
            val query = signal.query ?: continue

            val prometheusSeries: List<PrometheusClient.TimeSeries> =
                executor.executeOrDefault(
                    { prometheusClient.queryRange(query, start, end, queryStep) },
                    emptyList(),
                    TaskContext.of("AnomalyDetectionOrchestrator", "QueryPrometheus", signal.id),
                )

            if (prometheusSeries.isNotEmpty()) {
                val modelSeries = prometheusSeries.map { convertToModelTimeSeries(it) }
                metrics[signal.id] = modelSeries

                log.debug("[AnomalyDetectionOrchestrator] Queried {}: {} series", signal.panelTitle, modelSeries.size)
            }
        }

        return metrics
    }

    fun buildIncidentContext(
        anomalies: List<AnomalyEvent>,
        signals: List<SignalDefinition>,
        metrics: Map<String, List<TimeSeries>>,
    ): IncidentContext {
        val incidentId = generateIncidentId(anomalies)
        val summary = buildSummary(anomalies)
        val evidence = buildEvidence(anomalies, signals, metrics)

        val metadata = mutableMapOf<String, Any>()
        metadata["anomalyCount"] = anomalies.size
        metadata["timestamp"] = Instant.now().toString()
        metadata["detectionMethod"] = "ZScore"

        return IncidentContext(incidentId, summary, anomalies, evidence, metadata)
    }

    private fun convertToModelTimeSeries(promSeries: PrometheusClient.TimeSeries): TimeSeries {
        val label = promSeries.metric.toString()

        val points = promSeries.values.map { vp ->
            MetricPoint(vp.timestamp * 1000, vp.getValueAsDouble())
        }

        return TimeSeries(label, points)
    }

    private fun generateIncidentId(anomalies: List<AnomalyEvent>): String {
        val signature = anomalies.stream()
            .limit(3)
            .map { it.signalId }
            .sorted()
            .reduce { a, b -> "$a,$b" }
            .orElse("unknown")

        val hash = signature.hashCode()
        val epochMinute = Instant.now().epochSecond / 60

        return "INC-$epochMinute-${Math.abs(hash).toString(16).padStart(8, '0')}"
    }

    private fun buildSummary(anomalies: List<AnomalyEvent>): String {
        val critCount = anomalies.stream().filter { "CRITICAL" == it.severity }.count()
        val warnCount = anomalies.stream().filter { "WARNING" == it.severity }.count()

        return "$critCount CRIT, $warnCount WARN anomalies detected"
    }

    private fun buildEvidence(
        anomalies: List<AnomalyEvent>,
        signals: List<SignalDefinition>,
        metrics: Map<String, List<TimeSeries>>,
    ): List<EvidenceItem> {
        val signalMap = signals.associateBy { it.id }
        val evidence = mutableListOf<EvidenceItem>()

        for (anomaly in anomalies) {
            val signal = signalMap[anomaly.signalId] ?: continue

            val body = """
                |PromQL: ${signal.query}
                |Current: ${anomaly.currentValue} ${signal.unit ?: ""}
                |Baseline: ${anomaly.baselineValue ?: 0.0} ${signal.unit ?: ""}
                |Reason: ${anomaly.reason}
            """.trimMargin()

            evidence.add(EvidenceItem("PROMQL", signal.panelTitle, body))
        }

        return evidence
    }
}
