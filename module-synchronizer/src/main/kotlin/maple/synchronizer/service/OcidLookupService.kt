package maple.synchronizer.service

import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.redis.OcidMappingRedisWriter
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.OcidMappingFileReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OcidLookupService(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val ocidMappingRedisWriter: OcidMappingRedisWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(event: SnapshotRunCompletedEvent) {
        if (event.endpoint != "ocid-lookup") return

        log.info(
            "[OcidService] received: runId={} totalRecords={} manifestPath={}",
            event.runId, event.totalRecords, event.manifestPath,
        )

        val mappings = fileReader.read(event.manifestPath)
        if (mappings.isEmpty()) {
            log.warn("[OcidService] no mappings found in: {}", event.manifestPath)
            return
        }

        repository.batchUpsert(mappings)
        if (mappings.isNotEmpty()) {
            runCatching {
                ocidMappingRedisWriter.writeOcidToRedis(mappings)
            }.onFailure { ex ->
                log.error(
                    "[OcidService] Redis write failed after DB upsert: runId={} mappings={} - {}. Redis may be stale until next run.",
                    event.runId, mappings.size, ex.message, ex,
                )
            }
        }

        log.info("[OcidService] completed: runId={} processed={}", event.runId, mappings.size)
    }
}
