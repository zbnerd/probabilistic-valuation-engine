package maple.expectation.infrastructure.monitoring.copilot.ingestor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.copilot.model.SeverityMapping
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Grafana Dashboard JSON Ingestor
 */
@Component
class GrafanaJsonIngestor(
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
) {

    private val log = LoggerFactory.getLogger(GrafanaJsonIngestor::class.java)

    companion object {
        private const val PANEL_TYPE_ROW = "row"
        private const val HIGH_PRIORITY_SCORE = 100
        private const val MEDIUM_PRIORITY_SCORE = 50
        private const val LOW_PRIORITY_SCORE = 10
    }

    fun ingestDashboards(dir: Path): List<SignalDefinition> {
        if (!Files.exists(dir)) {
            log.warn("Dashboard directory does not exist: {}", dir)
            return emptyList()
        }

        return executor.executeOrDefault(
            { ingestDashboardsInternal(dir) },
            emptyList(),
            TaskContext.of("GrafanaJsonIngestor", "IngestDashboards", dir.toString()),
        )
    }

    private fun ingestDashboardsInternal(dir: Path): List<SignalDefinition> {
        val signals = mutableListOf<SignalDefinition>()

        Files.walk(dir).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".json") }
                .forEach { jsonPath ->
                    val dashboardSignals = parseDashboard(jsonPath)
                    signals.addAll(dashboardSignals)
                }
        }

        log.info("Ingested {} signals from {} dashboards", signals.size, dir)
        return signals
    }

    private fun parseDashboard(jsonPath: Path): List<SignalDefinition> = executor.executeOrDefault(
        { parseDashboardInternal(jsonPath) },
        emptyList(),
        TaskContext.of("GrafanaJsonIngestor", "ParseDashboard", jsonPath.fileName.toString()),
    )

    private fun parseDashboardInternal(jsonPath: Path): List<SignalDefinition> {
        val jsonContent = Files.readString(jsonPath)
        val root = objectMapper.readTree(jsonContent)
        val dashboardUid = root.path("uid").asText()
        val dashboardTitle = root.path("title").asText()

        val signals = mutableListOf<SignalDefinition>()
        val panels = root.path("panels")

        extractPanelsRecursively(panels, dashboardUid, dashboardTitle, signals)

        return signals
    }

    private fun extractPanelsRecursively(
        panels: JsonNode,
        dashboardUid: String,
        dashboardTitle: String,
        signals: MutableList<SignalDefinition>,
    ) {
        if (panels == null || panels.isMissingNode) {
            return
        }

        for (panel in panels) {
            val panelType = panel.path("type").asText()

            if (PANEL_TYPE_ROW == panelType) {
                val nestedPanels = panel.path("panels")
                extractPanelsRecursively(nestedPanels, dashboardUid, dashboardTitle, signals)
                continue
            }

            val targets = panel.path("targets")
            if (targets == null || targets.isEmpty || !targets.isArray) {
                continue
            }

            val panelTitle = panel.path("title").asText("Untitled Panel")
            val panelId = panel.path("id").asInt(-1)

            for (target in targets) {
                val expr = target.path("expr").asText() ?: continue
                if (expr.isBlank()) continue

                val refId = target.path("refId").asText("A")
                val datasourceType = detectDatasourceType(panel, target)
                if (datasourceType.equals("prometheus", ignoreCase = true).not()) {
                    continue
                }

                val thresholds = extractThresholds(panel)
                val priority = calculatePriority(panelTitle, expr)

                val metadata = mutableMapOf<String, String>()
                metadata["dashboardTitle"] = dashboardTitle
                metadata["panelId"] = panelId.toString()
                metadata["priorityScore"] = priority.toString()

                signals.add(
                    SignalDefinition(
                        id = generateStableId(dashboardUid, panelId, refId, expr),
                        dashboardUid = dashboardUid,
                        panelTitle = panelTitle,
                        datasourceType = datasourceType,
                        query = expr,
                        legend = target.path("legendFormat").asText(""),
                        unit = panel.path("fieldConfig").path("defaults").path("unit").asText(""),
                        severityMapping = thresholds,
                        sloTag = inferSloTag(panelTitle, expr),
                        metadata = metadata,
                    ),
                )
            }
        }
    }

    private fun detectDatasourceType(panel: JsonNode, target: JsonNode): String {
        var ds = target.path("datasource").path("type").asText()
        if (ds.isNotBlank()) {
            return ds
        }
        ds = panel.path("datasource").path("type").asText()
        if (ds.isNotBlank()) {
            return ds
        }
        return "prometheus"
    }

    private fun extractThresholds(panel: JsonNode): SeverityMapping {
        val steps = panel.path("fieldConfig").path("defaults").path("thresholds").path("steps")

        if (!steps.isArray || steps.size() < 2) {
            return SeverityMapping(warnThreshold = 0.0, critThreshold = 0.0, comparator = ">")
        }

        var warnThreshold: Double? = null
        var critThreshold: Double? = null

        var stepCount = 0
        for (step in steps) {
            if (!step.hasNonNull("value")) {
                continue
            }
            val value = step.get("value").asDouble()
            if (stepCount == 0) {
                warnThreshold = value
            } else if (stepCount == 1) {
                critThreshold = value
                break
            }
            stepCount++
        }

        return SeverityMapping(
            warnThreshold = warnThreshold ?: 0.0,
            critThreshold = critThreshold ?: 0.0,
            comparator = ">",
        )
    }

    private fun calculatePriority(title: String, expr: String): Int {
        val lower = (title + " " + expr).lowercase()

        if (lower.contains("p99") ||
            lower.contains("error") ||
            lower.contains("timeout") ||
            lower.contains("deadletter") ||
            lower.contains("dlq") ||
            lower.contains("lag") ||
            lower.contains("pending") ||
            lower.contains("pool") ||
            lower.contains("gc") ||
            lower.contains("oom") ||
            lower.contains("lock")
        ) {
            return HIGH_PRIORITY_SCORE
        }

        if (lower.contains("hit rate") || lower.contains("throughput") || lower.contains("latency")) {
            return MEDIUM_PRIORITY_SCORE
        }

        return LOW_PRIORITY_SCORE
    }

    private fun inferSloTag(title: String, expr: String): String {
        val lower = (title + " " + expr).lowercase()
        if (lower.contains("p99")) return "latency.p99"
        if (lower.contains("error") || expr.contains("5..")) return "error.rate"
        return "generic"
    }

    private fun generateStableId(dashboardUid: String, panelId: Int, refId: String, expr: String): String {
        val raw = "$dashboardUid|$panelId|$refId|$expr"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(raw.toByteArray())
        return HexFormat.of().formatHex(hash).substring(0, 16)
    }
}
