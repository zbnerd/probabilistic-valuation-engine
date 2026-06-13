package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

class GzipJsonlChunkWriterTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `close uploads gzipped JSONL via putFile and returns stats`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        val pathCaptor = argumentCaptor<Path>()
        var captured: ByteArray = ByteArray(0)
        whenever(storage.putFile(keyCaptor.capture(), pathCaptor.capture()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                val path: Path = invocation.getArgument(1)
                captured = Files.readAllBytes(path)
                PutResult(key, captured.size.toLong(), null)
            }

        val writer = GzipJsonlChunkWriter(
            chunkKey = "runs/abc/ranking-overall/part-000001.jsonl.gz",
            partIndex = 1,
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            objectStorage = storage,
            clock = fixedClock,
        )

        repeat(3) { i ->
            writer.append(
                SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(mapOf("character_name" to "char$i")),
                    key = "char$i",
                    endpoint = "ranking-overall",
                    keyType = "DATE_PAGE",
                    httpStatus = 200,
                    fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
                ),
            )
        }

        val stats = writer.close()

        verify(storage).putFile(any<String>(), any<Path>())
        assertThat(keyCaptor.firstValue).isEqualTo("runs/abc/ranking-overall/part-000001.jsonl.gz")
        // The Path argument is captured; its underlying file is deleted by
        // the writer after putFile returns, so we cannot assert existence
        // here. The path is verified by the captured bytes being
        // successfully decompressed (below).
        assertThat(stats.partIndex).isEqualTo(1)
        assertThat(stats.path).isEqualTo("part-000001.jsonl.gz")
        assertThat(stats.recordCount).isEqualTo(3)
        assertThat(stats.uncompressedBytes).isGreaterThan(0)
        assertThat(stats.compressedBytes).isGreaterThan(0)
        assertThat(stats.startedAt).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"))
        assertThat(stats.finishedAt).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"))

        val raw = GZIPInputStream(ByteArrayInputStream(captured))
            .bufferedReader()
            .readText()
        val lines = raw.lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(3)
        lines.forEach { line ->
            val node = objectMapper.readTree(line)
            // Writer serializes the full SnapshotChunkRecord.Success envelope; the original
            // bodyBytes is base64-encoded under "bodyBytes".
            assertThat(node.has("key")).isTrue()
            assertThat(node.has("bodyBytes")).isTrue()
            val decoded = String(java.util.Base64.getDecoder().decode(node.get("bodyBytes").asText()))
            assertThat(decoded).contains("\"character_name\"")
        }
    }

    /**
     * Regression for the OOM that crashed pipeline runs at
     * `max-uncompressed-bytes: 128MB`.
     *
     * The previous heap-buffered implementation allocated a
     * `ByteArrayOutputStream` large enough to hold the entire chunk and a
     * second copy via `toByteArray()` (~256MB peak), exceeding the 1GB
     * writer-thread heap when deflater state was added.
     *
     * With the temp-file + putFile refactor, the writer thread's heap
     * footprint is bounded by the deflater window (~32KB) regardless of
     * chunk size, so a 32MB chunk must close cleanly. We assert:
     *  1. close() returns without OOM,
     *  2. putFile is called exactly once (no fallback to put/putStream),
     *  3. the bytes uploaded to storage decompress to a valid JSONL
     *     line count equal to the record count.
     */
    @Test
    fun `close uploads 32MB chunk via putFile without loading it all into heap`() {
        val storage = mock<ObjectStorage>()
        var captured: ByteArray = ByteArray(0)
        whenever(storage.putFile(any<String>(), any<Path>()))
            .thenAnswer { invocation ->
                val path: Path = invocation.getArgument(1)
                captured = Files.readAllBytes(path)
                PutResult(invocation.getArgument(0), captured.size.toLong(), null)
            }

        val writer = GzipJsonlChunkWriter(
            chunkKey = "runs/big/item-equipment/part-000001.jsonl.gz",
            partIndex = 1,
            maxRecords = Int.MAX_VALUE,
            // Production knob is 128MB; 32MB here keeps the test fast and
            // still well above the heap budget the old code blew through.
            maxUncompressedBytes = 32L * 1024 * 1024,
            objectMapper = objectMapper,
            objectStorage = storage,
            clock = fixedClock,
        )

        // Each record's JSON envelope is ~250 bytes after Jackson serialization.
        // 160_000 records × ~250B ≈ 40MB uncompressed, comfortably over the cap.
        val recordCount = 160_000
        repeat(recordCount) { i ->
            writer.append(
                SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(
                        mapOf(
                            "character_name" to "char_$i",
                            "ocid" to "ocid_$i",
                            "guild" to "guild_$i",
                            "level" to 250 + (i % 10),
                        ),
                    ),
                    key = "char_$i",
                    endpoint = "item-equipment",
                    keyType = "OCID",
                    httpStatus = 200,
                    fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
                ),
            )
        }

        val stats = writer.close()

        assertThat(stats.recordCount).isEqualTo(recordCount)
        assertThat(stats.uncompressedBytes).isGreaterThan(32L * 1024 * 1024)
        assertThat(stats.compressedBytes).isGreaterThan(0)
        verify(storage).putFile(any<String>(), any<Path>())

        val raw = GZIPInputStream(ByteArrayInputStream(captured))
            .bufferedReader()
            .readText()
        val lines = raw.lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(recordCount)
    }
}
