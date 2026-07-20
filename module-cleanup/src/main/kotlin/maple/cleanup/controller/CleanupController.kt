package maple.cleanup.controller

import maple.cleanup.inbox.InboxProperties
import maple.cleanup.service.RunCleanupService
import maple.cleanup.service.StaleKafkaSkipService
import maple.common.cleanup.RunCleanupResult
import maple.expectation.common.storage.ObjectStorage
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.inbox.CleanupInboxEntry
import maple.pipeline.artifact.inbox.CleanupInboxStore
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP endpoints for Airflow-triggered cleanup.
 *
 * - POST /api/internal/cleanup/runs            → whole-run GC for runs/
 * - POST /api/internal/cleanup/calculator-runs  → whole-run GC for calculator/runs/
 * - POST /api/internal/cleanup/inbox           → drain event queue + delete per event
 *
 * Inbox deletion uses ObjectStorage (object keys are absolute storage keys, not
 * filesystem paths). InboxProperties.basePath is retained for backward compat
 * with existing YAML, but is no longer used for deletion.
 */
@RestController
@RequestMapping("/api/internal/cleanup")
class CleanupController(
    private val runCleanupService: RunCleanupService,
    private val inboxStore: CleanupInboxStore,
    private val inboxProperties: InboxProperties,
    private val objectStorage: ObjectStorage,
    private val staleKafkaSkipService: StaleKafkaSkipService,
) {
    private val log = LoggerFactory.getLogger(CleanupController::class.java)

    @PostMapping("/runs")
    fun cleanupRuns(): ResponseEntity<RunCleanupResult> {
        log.info("[CleanupController] POST /runs")
        return ResponseEntity.ok(runCleanupService.cleanupRuns())
    }

    @PostMapping("/stale-kafka")
    fun scanStaleKafka(
        @org.springframework.web.bind.annotation.RequestBody request: StaleKafkaRequest,
    ): ResponseEntity<List<StaleKafkaSkipService.ScanResult>> {
        log.info("[CleanupController] POST /stale-kafka topics={} keepRunIds={}", request.topics, request.keepRunIds)
        val results = request.topics.map { topic ->
            staleKafkaSkipService.scanForStaleMessages(
                topic = topic,
                consumerGroup = request.consumerGroup,
                keepRunIds = request.keepRunIds.toSet(),
            )
        }
        return ResponseEntity.ok(results)
    }

    data class StaleKafkaRequest(
        val topics: List<String>,
        val consumerGroup: String,
        val keepRunIds: List<String>,
    )

    @PostMapping("/calculator-runs")
    fun cleanupCalculatorRuns(): ResponseEntity<RunCleanupResult> {
        log.info("[CleanupController] POST /calculator-runs")
        return ResponseEntity.ok(runCleanupService.cleanupCalculatorRuns())
    }

    @PostMapping("/inbox")
    fun cleanupInbox(): ResponseEntity<InboxCleanupResponse> {
        val pending = inboxStore.pendingCount()
        if (pending > inboxProperties.maxPending) {
            log.warn(
                "[CleanupController] durable inbox above alert threshold: pending={} threshold={}",
                pending,
                inboxProperties.maxPending,
            )
        }
        var afterKey: ArtifactKey? = null
        var scanned = 0
        var completed = 0
        var retainedForRetry = 0
        var deletedTargets = 0

        while (scanned < inboxProperties.maxDrainEntriesPerRequest) {
            val limit = minOf(
                inboxProperties.drainPageSize,
                inboxProperties.maxDrainEntriesPerRequest - scanned,
            )
            val page = inboxStore.listPage(afterKey, limit)
            if (page.entries.isEmpty()) break

            page.entries.forEach { (inboxKey, entry) ->
                scanned++
                val deletion = deleteTargets(entry)
                deletedTargets += deletion.deletedTargets
                if (deletion.allDeleted && deleteInboxEntry(inboxKey)) {
                    completed++
                } else {
                    retainedForRetry++
                }
            }
            afterKey = page.entries.last().first
        }

        val response = InboxCleanupResponse(
            scanned = scanned,
            completed = completed,
            retainedForRetry = retainedForRetry,
            deletedTargets = deletedTargets,
        )
        log.info("[CleanupController] durable inbox cleanup: {}", response)
        return ResponseEntity.ok(response)
    }

    private fun deleteTargets(entry: CleanupInboxEntry): TargetDeletion {
        val objectDeleted = deleteObject(entry.event.objectKey)
        val sourceDeleted = entry.event.sourceObjectKey?.let(::deleteObject)
        return TargetDeletion(
            allDeleted = objectDeleted && sourceDeleted != false,
            deletedTargets = (if (objectDeleted) 1 else 0) + (if (sourceDeleted == true) 1 else 0),
        )
    }

    private fun deleteObject(objectKey: String): Boolean = runCatching {
        objectStorage.delete(objectKey)
    }.fold(
        onSuccess = { true },
        onFailure = { failure ->
            log.error("[CleanupController] target delete failed: {} - {}", objectKey, failure.message, failure)
            false
        },
    )

    private fun deleteInboxEntry(key: ArtifactKey): Boolean = runCatching {
        inboxStore.delete(key)
    }.fold(
        onSuccess = { true },
        onFailure = { failure ->
            log.error("[CleanupController] inbox delete failed: {} - {}", key, failure.message, failure)
            false
        },
    )

    private data class TargetDeletion(
        val allDeleted: Boolean,
        val deletedTargets: Int,
    )
}
