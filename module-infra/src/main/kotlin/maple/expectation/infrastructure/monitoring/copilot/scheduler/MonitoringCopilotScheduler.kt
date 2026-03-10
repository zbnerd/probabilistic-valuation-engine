package maple.expectation.infrastructure.monitoring.copilot.scheduler

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.ai.AiSreService
import maple.expectation.infrastructure.monitoring.copilot.dedup.SignalDeduplicationStrategy
import maple.expectation.infrastructure.monitoring.copilot.model.AnomalyEvent
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition
import maple.expectation.infrastructure.monitoring.copilot.pipeline.AlertNotificationService
import maple.expectation.infrastructure.monitoring.copilot.pipeline.AnomalyDetectionOrchestrator
import maple.expectation.infrastructure.monitoring.copilot.pipeline.SignalDefinitionLoader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["monitoring.copilot.enabled"], havingValue = "true")
class MonitoringCopilotScheduler(
    private val signalLoader: SignalDefinitionLoader,
    private val detectionOrchestrator: AnomalyDetectionOrchestrator,
    private val alertService: AlertNotificationService,
    private val aiSreService: AiSreService,
    private val executor: LogicExecutor,
    private val dedupStrategy: SignalDeduplicationStrategy,
) {
    companion object {
        private val log = LoggerFactory.getLogger(MonitoringCopilotScheduler::class.java)
    }

    @Value("\${monitoring.copilot.top-signals:10}")
    var topSignalsCount: Int = 10

    @Scheduled(fixedRateString = "\${monitoring.interval-seconds:15}000")
    fun monitorAndDetect() {
        val context = TaskContext.of("MonitoringCopilot", "ScheduledDetection")

        executor.executeVoidJava(
            {
                val now = System.currentTimeMillis()

                val signals = signalLoader.loadSignalDefinitions()

                if (signals.isEmpty()) {
                    log.debug("[MonitoringCopilot] No signals loaded, skipping detection cycle")
                    return@executeVoidJava
                }

                val topSignals = selectTopPrioritySignals(signals)
                log.debug("[MonitoringCopilot] Selected {} top priority signals", topSignals.size)

                val detectedAnomalies = detectionOrchestrator.detectAnomalies(topSignals, now)

                if (detectedAnomalies.isEmpty()) {
                    log.debug("[MonitoringCopilot] No anomalies detected in this cycle")
                    return@executeVoidJava
                }

                log.info("[MonitoringCopilot] Detected {} anomalies", detectedAnomalies.size)

                processIncident(detectedAnomalies, now)

                dedupStrategy.cleanup(now)
            },
            context,
        )
    }

    private fun selectTopPrioritySignals(signals: List<SignalDefinition>): List<SignalDefinition> = signals.stream()
        .filter { it.metadata != null && it.metadata.containsKey("priorityScore") }
        .sorted { s1, s2 ->
            val score1 = Integer.parseInt(s1.metadata["priorityScore"]!!)
            val score2 = Integer.parseInt(s2.metadata["priorityScore"]!!)
            Integer.compare(score2, score1)
        }
        .limit(topSignalsCount.toLong())
        .toList()

    private fun processIncident(anomalies: List<AnomalyEvent>, now: Long) {
        val signalCatalog = signalLoader.loadSignalDefinitions()

        val context = detectionOrchestrator.buildIncidentContext(anomalies, signalCatalog, emptyMap())

        alertService.sendAlert(context, java.util.Optional.of(aiSreService), signalCatalog)
    }
}
