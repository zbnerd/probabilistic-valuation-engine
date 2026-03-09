package maple.expectation.infrastructure.monitoring.alert

import maple.expectation.core.port.out.AlertNotificationPort
import maple.expectation.infrastructure.monitoring.copilot.model.SeverityMapping
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition
import maple.expectation.infrastructure.monitoring.copilot.notifier.DiscordNotifier
import org.springframework.stereotype.Component

/**
 * AlertNotificationPort 구현체 (ADR-005)
 *
 * DiscordNotifier를 래핑하여 Port 인터페이스 구현
 */
@Component
class AlertNotificationPortAdapter(
    private val discordNotifier: DiscordNotifier,
) : AlertNotificationPort {

    override fun send(content: String) {
        discordNotifier.send(content)
    }

    override fun formatIncidentMessage(
        incidentId: String,
        severity: String,
        signals: List<AlertNotificationPort.AnnotatedSignal>,
        hypotheses: List<String>,
        actions: List<String>,
    ): String {
        val annotatedSignals = signals.map { signal ->
            DiscordNotifier.AnnotatedSignal(
                signal = createSignalDefinition(signal),
                value = signal.value,
            )
        }

        return discordNotifier.formatIncidentMessage(
            incidentId,
            severity,
            annotatedSignals,
            hypotheses,
            actions,
        )
    }

    private fun createSignalDefinition(signal: AlertNotificationPort.AnnotatedSignal): SignalDefinition = SignalDefinition(
        id = signal.signalName.hashCode().toString(),
        dashboardUid = "",
        panelTitle = signal.signalName,
        datasourceType = "Prometheus",
        query = "",
        legend = "",
        unit = signal.signalUnit ?: "",
        severityMapping = SeverityMapping(
            warnThreshold = 0.0,
            critThreshold = 0.0,
            comparator = ">",
        ),
        sloTag = "",
        metadata = emptyMap(),
    )
}
