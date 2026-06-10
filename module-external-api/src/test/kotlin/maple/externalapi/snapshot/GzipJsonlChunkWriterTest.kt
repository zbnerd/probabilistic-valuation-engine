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
    fun `close puts gzipped JSONL to ObjectStorage and returns stats`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        val bytesCaptor = argumentCaptor<ByteArray>()
        whenever(storage.put(keyCaptor.capture(), bytesCaptor.capture()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                val bytes: ByteArray = invocation.getArgument(1)
                PutResult(key, bytes.size.toLong(), null)
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

        verify(storage).put(any<String>(), any<ByteArray>())
        assertThat(keyCaptor.firstValue).isEqualTo("runs/abc/ranking-overall/part-000001.jsonl.gz")
        assertThat(stats.partIndex).isEqualTo(1)
        assertThat(stats.path).isEqualTo("part-000001.jsonl.gz")
        assertThat(stats.recordCount).isEqualTo(3)
        assertThat(stats.uncompressedBytes).isGreaterThan(0)
        assertThat(stats.compressedBytes).isGreaterThan(0)
        assertThat(stats.startedAt).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"))
        assertThat(stats.finishedAt).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"))

        val raw = GZIPInputStream(ByteArrayInputStream(bytesCaptor.firstValue)).bufferedReader().readText()
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
}
