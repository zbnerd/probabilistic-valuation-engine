package maple.expectation.infrastructure.monitoring.copilot.pipeline

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.ai.AiSreService
import maple.expectation.infrastructure.monitoring.copilot.model.*
import maple.expectation.infrastructure.monitoring.copilot.notifier.DiscordNotifier
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["monitoring.copilot.enabled"], havingValue = "true")
class AlertNotificationService(
    private val discordNotifier: DiscordNotifier,
    private val executor: LogicExecutor,
    private val deDuplicationCache: DeDuplicationCache,
) {
    companion object {
        private val log = LoggerFactory.getLogger(AlertNotificationService::class.java)
    }

    fun sendAlert(
        context: IncidentContext,
        aiSreService: java.util.Optional<AiSreService>,
        signalDefinitions: List<SignalDefinition>,
    ) {
        val now = System.currentTimeMillis()

        deDuplicationCache.cleanOld(now)

        if (deDuplicationCache.isRecent(context.incidentId, now)) {
            log.info("[AlertNotificationService] Incident {} already recent, skipping", context.incidentId)
            return
        }

        val plan = aiSreService
            .map { service -> service.analyzeIncident(context) }
            .orElseGet { createDefaultMitigationPlan(context) }

        sendDiscordAlert(context, plan, signalDefinitions, now)
    }

    fun sendAlertWithPlan(
        context: IncidentContext,
        plan: AiSreService.MitigationPlan,
        signalDefinitions: List<SignalDefinition>,
    ) {
        val now = System.currentTimeMillis()

        deDuplicationCache.cleanOld(now)

        if (deDuplicationCache.isRecent(context.incidentId, now)) {
            log.info("[AlertNotificationService] Incident {} already recent, skipping", context.incidentId)
            return
        }

        sendDiscordAlert(context, plan, signalDefinitions, now)
    }

    fun forceSendAlert(
        context: IncidentContext,
        plan: AiSreService.MitigationPlan,
        signalDefinitions: List<SignalDefinition>,
    ) {
        val now = System.currentTimeMillis()
        log.info("[AlertNotificationService] Force sending alert: {}", context.incidentId)
        sendDiscordAlert(context, plan, signalDefinitions, now)
    }

    private fun sendDiscordAlert(
        context: IncidentContext,
        plan: AiSreService.MitigationPlan,
        signalDefinitions: List<SignalDefinition>,
        timestamp: Long,
    ) {
        executor.executeVoidJava(
            {
                val signalMap = signalDefinitions.associateBy { it.id }

                val annotatedSignals = context.anomalies.take(3).map { anomaly ->
                    DiscordNotifier.AnnotatedSignal(
                        requireNotNull(signalMap[anomaly.signalId]) { "SignalDefinition not found for signalId=${anomaly.signalId}" },
                        anomaly.currentValue,
                    )
                }

                val hypotheses = plan.hypotheses.take(2).map { h ->
                    "**${h.cause}** (confidence: ${h.confidence})"
                }

                val actions = plan.actions.take(2).map { a ->
                    "${a.step}. ${a.action} [risk: ${a.risk}]"
                }

                val severity = if (context.anomalies.stream().anyMatch { "CRIT" == it.severity }) "CRIT" else "WARN"

                val message = discordNotifier.formatIncidentMessage(
                    context.incidentId,
                    severity,
                    annotatedSignals,
                    hypotheses,
                    actions,
                )

                discordNotifier.send(message)

                deDuplicationCache.track(context.incidentId, timestamp)

                log.info("[AlertNotificationService] Alert sent: {}", context.incidentId)
            },
            TaskContext.of("AlertNotificationService", "SendDiscord", context.incidentId),
        )
    }

    fun getCacheSize(): Int = deDuplicationCache.size()

    fun clearCache() {
        deDuplicationCache.clear()
    }

    private fun createDefaultMitigationPlan(context: IncidentContext): AiSreService.MitigationPlan {
        log.warn("[AlertNotificationService] AI SRE not available, using default mitigation plan")

        val defaultHypotheses = listOf(
            AiSreService.Hypothesis(
                "AI SRE service not available - manual analysis required",
                "LOW",
                listOf("AI analysis disabled", "Manual investigation needed"),
            ),
        )

        val defaultActions = listOf(
            AiSreService.Action(1, "Review system logs", "LOW", "Identify root cause"),
            AiSreService.Action(2, "Check metrics dashboard", "LOW", "Verify anomaly details"),
            AiSreService.Action(3, "Escalate to on-call engineer", "LOW", "Human intervention required"),
        )

        val rollbackPlan = AiSreService.RollbackPlan(
            "If symptoms worsen",
            listOf("Revert recent changes", "Scale up resources"),
        )

        return AiSreService.MitigationPlan(
            context.incidentId,
            "RULE_BASED_FALLBACK",
            defaultHypotheses,
            defaultActions,
            emptyList(),
            rollbackPlan,
            "AI SRE service not available. Please enable ai.sre.enabled=true for AI-powered analysis.",
        )
    }
}
