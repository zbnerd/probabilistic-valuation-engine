package maple.synchronizer.service

import java.time.Instant
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.redis.OcidMappingRedisWriter
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.domain.OcidMapping
import maple.synchronizer.storage.OcidMappingFileReader
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class OcidLookupServiceTest {
    private val fileReader = mock<OcidMappingFileReader>()
    private val repository = mock<OcidMappingRepository>()
    private val redisWriter = mock<OcidMappingRedisWriter>()

    private val service = OcidLookupService(fileReader, repository, redisWriter)

    @Test
    fun `ingest reads file, upserts db, writes redis on ocid-lookup endpoint`() {
        val event = SnapshotRunCompletedEvent(
            eventId = "event-1",
            runId = "run-1",
            endpoint = "ocid-lookup",
            manifestPath = "runs/run-1/ocid-lookup/manifest.jsonl",
            totalRecords = 2,
            totalFailed = 0,
            chunkCount = 1,
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
            finishedAt = Instant.parse("2026-01-01T00:01:00Z"),
            createdAt = Instant.parse("2026-01-01T00:01:00Z"),
        )
        val mappings = listOf(
            OcidMapping(userIgn = "f***l", ocid = "ocid-1"),
            OcidMapping(userIgn = "s***d", ocid = "ocid-2"),
        )
        whenever(fileReader.read(event.manifestPath)).thenReturn(mappings)
        doNothing().whenever(repository).batchUpsert(mappings)
        doNothing().whenever(redisWriter).writeOcidToRedis(mappings)

        service.ingest(event)

        verify(fileReader).read(event.manifestPath)
        verify(repository).batchUpsert(mappings)
        verify(redisWriter).writeOcidToRedis(mappings)
    }

    @Test
    fun `ingest skips db and redis when file is empty`() {
        val event = SnapshotRunCompletedEvent(
            eventId = "event-1",
            runId = "run-1",
            endpoint = "ocid-lookup",
            manifestPath = "runs/run-1/ocid-lookup/manifest.jsonl",
            totalRecords = 0,
            totalFailed = 0,
            chunkCount = 0,
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
            finishedAt = Instant.parse("2026-01-01T00:01:00Z"),
            createdAt = Instant.parse("2026-01-01T00:01:00Z"),
        )
        whenever(fileReader.read(event.manifestPath)).thenReturn(emptyList())

        service.ingest(event)

        verify(fileReader).read(event.manifestPath)
        verify(repository, never()).batchUpsert(org.mockito.kotlin.any())
        verify(redisWriter, never()).writeOcidToRedis(org.mockito.kotlin.any())
    }

    @Test
    fun `ingest swallows redis failure after db upsert`() {
        val event = SnapshotRunCompletedEvent(
            eventId = "event-1",
            runId = "run-1",
            endpoint = "ocid-lookup",
            manifestPath = "runs/run-1/ocid-lookup/manifest.jsonl",
            totalRecords = 1,
            totalFailed = 0,
            chunkCount = 1,
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
            finishedAt = Instant.parse("2026-01-01T00:01:00Z"),
            createdAt = Instant.parse("2026-01-01T00:01:00Z"),
        )
        val mappings = listOf(OcidMapping(userIgn = "f***l", ocid = "ocid-1"))
        whenever(fileReader.read(event.manifestPath)).thenReturn(mappings)
        doNothing().whenever(repository).batchUpsert(mappings)
        whenever(redisWriter.writeOcidToRedis(mappings)).thenThrow(RuntimeException("redis down"))

        service.ingest(event)

        verify(repository).batchUpsert(mappings)
        verify(redisWriter).writeOcidToRedis(mappings)
    }

    @Test
    fun `ingest is a no-op for non-ocid-lookup endpoint`() {
        val event = SnapshotRunCompletedEvent(
            eventId = "event-1",
            runId = "run-1",
            endpoint = "character-basic",
            manifestPath = "runs/run-1/character-basic/manifest.jsonl",
            totalRecords = 0,
            totalFailed = 0,
            chunkCount = 0,
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
            finishedAt = Instant.parse("2026-01-01T00:01:00Z"),
            createdAt = Instant.parse("2026-01-01T00:01:00Z"),
        )

        service.ingest(event)

        verify(fileReader, never()).read(org.mockito.kotlin.any())
        verify(repository, never()).batchUpsert(org.mockito.kotlin.any())
        verify(redisWriter, never()).writeOcidToRedis(org.mockito.kotlin.any())
    }
}
