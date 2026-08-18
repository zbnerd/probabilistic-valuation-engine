package maple.pipeline.artifact.retention

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import maple.expectation.common.storage.ObjectInfo
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix
import maple.pipeline.artifact.identity.ArtifactSegment
import maple.pipeline.artifact.identity.CalculatorArtifactLayout
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.lifecycle.RunState
import maple.pipeline.artifact.storage.ConditionalObjectStorage

class ArtifactRunCatalog(
    private val objectStorage: ConditionalObjectStorage,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val runTimestampFormatter = DateTimeFormatter.ofPattern(RUN_TIMESTAMP_PATTERN)
        .withZone(zoneId)

    fun list(root: ArtifactPrefix): List<ArtifactRunInfo> {
        require(isSupportedRoot(root)) { "artifact run catalog root is not supported" }
        val objects = listAll(root)
        return objects.groupBy { objectInfo -> runId(root, objectInfo.key) }
            .map { (runId, runObjects) -> classify(root, runId, runObjects) }
            .sortedBy(ArtifactRunInfo::runId)
    }

    private fun listAll(root: ArtifactPrefix): List<ObjectInfo> {
        val objects = mutableListOf<ObjectInfo>()
        var afterKey: ArtifactKey? = null
        do {
            val page = objectStorage.listPage(root, afterKey, PAGE_SIZE)
            objects.addAll(page.objects)
            afterKey = page.nextAfterKey
        } while (afterKey != null)
        return objects
    }

    private fun classify(
        root: ArtifactPrefix,
        runId: String,
        objects: List<ObjectInfo>,
    ): ArtifactRunInfo {
        val createdAt = parseCreatedAt(runId)
        val parsed = parseEndpoints(root, runId, objects)
        val endpoints = parsed.endpoints.entries
            .map { (endpoint, artifacts) -> classifyEndpoint(root, endpoint, artifacts) }
            .sortedBy(ArtifactEndpointInfo::endpoint)
        val state = aggregateState(createdAt, parsed.invalidArtifact, endpoints)
        return ArtifactRunInfo(
            runId = runId,
            prefix = ArtifactKey.require("${root.value}$runId"),
            createdAt = createdAt.getOrElse { objects.minOf(ObjectInfo::lastModified) },
            sizeBytes = objects.sumOf(ObjectInfo::size),
            state = state,
            endpoints = endpoints,
        )
    }

    private fun parseEndpoints(
        root: ArtifactPrefix,
        runId: String,
        objects: List<ObjectInfo>,
    ): ParsedRunArtifacts {
        val endpoints = linkedMapOf<String, EndpointArtifacts>()
        var invalidArtifact = false
        for (objectInfo in objects) {
            val relative = objectInfo.key.removePrefix("${root.value}$runId/")
            if (ArtifactKey.parse(objectInfo.key).isFailure) {
                invalidArtifact = true
            } else if (relative == ROOT_RUNNING_MARKER) {
                endpoints.getOrPut(RANKING_ENDPOINT, ::EndpointArtifacts).running = true
            } else {
                val endpoint = relative.substringBefore('/')
                val endpointPath = relative.substringAfter('/', missingDelimiterValue = "")
                val endpointIsValid = runCatching { ArtifactSegment.require(endpoint) }.isSuccess
                if (!endpointIsValid || endpointPath.isEmpty()) {
                    invalidArtifact = true
                } else {
                    endpoints.getOrPut(endpoint, ::EndpointArtifacts).record(endpointPath, objectInfo.key)
                }
            }
        }
        return ParsedRunArtifacts(endpoints, invalidArtifact)
    }

    private fun classifyEndpoint(
        root: ArtifactPrefix,
        endpoint: String,
        artifacts: EndpointArtifacts,
    ): ArtifactEndpointInfo {
        val state = when {
            artifacts.running && artifacts.success -> RunState.ArtifactSucceededPublicationPending
            artifacts.running -> RunState.Running
            root == CalculatorArtifactLayout.runPrefix -> RunState.Published
            artifacts.success -> RunState.Published
            else -> RunState.Incomplete(INCOMPLETE_ENDPOINT_REASON)
        }
        return ArtifactEndpointInfo(endpoint, artifacts.manifestKey, state)
    }

    private fun aggregateState(
        createdAt: Result<Instant>,
        invalidArtifact: Boolean,
        endpoints: List<ArtifactEndpointInfo>,
    ): RunState {
        if (createdAt.isFailure) return RunState.Invalid(INVALID_RUN_ID_REASON)
        if (invalidArtifact) return RunState.Invalid(INVALID_ARTIFACT_REASON)
        return endpoints.map(ArtifactEndpointInfo::state)
            .minByOrNull(::protectionRank)
            ?: RunState.Incomplete(INCOMPLETE_ENDPOINT_REASON)
    }

    private fun protectionRank(state: RunState): Int = when (state) {
        is RunState.Invalid -> 0
        RunState.ArtifactSucceededPublicationPending -> 1
        RunState.Running, RunState.PublishedWithOrphanMarker -> 2
        is RunState.Incomplete, RunState.Absent -> 3
        RunState.Published -> 4
    }

    private fun parseCreatedAt(runId: String): Result<Instant> = runCatching {
        require(RUN_ID_PATTERN.matches(runId)) { INVALID_RUN_ID_REASON }
        runTimestampFormatter.parse(runId.substringBeforeLast('-')) { temporal -> Instant.from(temporal) }
    }

    private fun runId(root: ArtifactPrefix, key: String): String {
        require(key.startsWith(root.value)) { "artifact listing returned an object outside the requested root" }
        val runId = key.removePrefix(root.value).substringBefore('/')
        require(runId.isNotEmpty()) { "artifact listing contains an empty run ID" }
        return runId
    }

    private fun isSupportedRoot(root: ArtifactPrefix): Boolean =
        root == SourceArtifactLayout.runPrefix || root == CalculatorArtifactLayout.runPrefix

    private class EndpointArtifacts {
        var running: Boolean = false
        var success: Boolean = false
        var manifestKey: ArtifactKey? = null

        fun record(relativePath: String, key: String) {
            if (relativePath == SUCCESS_MARKER) success = true
            if (relativePath == MANIFEST_FILE) manifestKey = ArtifactKey.require(key)
            if (relativePath == RUNNING_MARKER || relativePath.endsWith("/$RUNNING_MARKER")) running = true
        }
    }

    private data class ParsedRunArtifacts(
        val endpoints: Map<String, EndpointArtifacts>,
        val invalidArtifact: Boolean,
    )

    private companion object {
        const val PAGE_SIZE: Int = 1_000
        const val RUN_TIMESTAMP_PATTERN: String = "yyyyMMdd-HHmmss"
        const val ROOT_RUNNING_MARKER: String = "_RUNNING"
        const val RUNNING_MARKER: String = "_RUNNING"
        const val SUCCESS_MARKER: String = "_SUCCESS"
        const val MANIFEST_FILE: String = "manifest.json"
        const val RANKING_ENDPOINT: String = "ranking-overall"
        const val INVALID_RUN_ID_REASON: String = "run ID is invalid"
        const val INVALID_ARTIFACT_REASON: String = "artifact key or endpoint is invalid"
        const val INCOMPLETE_ENDPOINT_REASON: String = "endpoint artifacts are incomplete"
        val RUN_ID_PATTERN: Regex = Regex("""\d{8}-\d{6}-\d+""")
    }
}
