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
    fun `close uploads gzipped JSONL via putFileAsync and returns stats`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        val pathCaptor = argumentCaptor<Path>()
        var captured: ByteArray = ByteArray(0)
        // putFileAsync returns immediately with a CompletableFuture; the
        // mock simulates a completed-future upload by reading the temp file
        // synchronously inside the thenAnswer body.
        whenever(storage.putFileAsync(keyCaptor.capture(), pathCaptor.capture()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                val path: Path = invocation.getArgument(1)
                captured = Files.readAllBytes(path)
                java.util.concurrent.CompletableFuture.completedFuture(
                    PutResult(key, captured.size.toLong(), null),
                )
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

        verify(storage).putFileAsync(any<String>(), any<Path>())
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
    fun `close uploads 32MB chunk via putFileAsync without loading it all into heap`() {
        val storage = mock<ObjectStorage>()
        var captured: ByteArray = ByteArray(0)
        whenever(storage.putFileAsync(any<String>(), any<Path>()))
            .thenAnswer { invocation ->
                val path: Path = invocation.getArgument(1)
                captured = Files.readAllBytes(path)
                java.util.concurrent.CompletableFuture.completedFuture(
                    PutResult(invocation.getArgument(0), captured.size.toLong(), null),
                )
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
        verify(storage).putFileAsync(any<String>(), any<Path>())

        val raw = GZIPInputStream(ByteArrayInputStream(captured))
            .bufferedReader()
            .readText()
        val lines = raw.lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(recordCount)
    }

    /**
     * Microbench: gzip level 6 vs 1 on `appendPreSerialized` (the writer hot path),
     * on a ~218KB compressible body matching the real item-equipment payload size
     * (avg 218KB, 98% >100KB — the ceiling cause). Measures three axes:
     *   - rate (rec/s, MB/s deflate)
     *   - per-record latency (mean, p50, p95, p99)
     *   - compression ratio (uncompressed/compressed from ChunkStats)
     *
     * Body is SYNTHETIC (real size + real code path, synthetic repetitive content).
     * Real item-equip ratio @ level 6 is ~11.5:1 (endurance report); synthetic is
     * approximate. The level 6→1 RATE ratio is data-robust (~3x) regardless.
     *
     * Writes results to /tmp/gzip_bench_result.txt (System.out forbidden in tests).
     * Run: ./gradlew :module-external-api:test --tests '*GzipJsonlChunkWriterTest.bench*'
     */
    @Test
    fun `bench gzip level 6 vs 1 latency rate compression`() {
        val body = synthItemEquipBody(218 * 1024)
        val storage = mock<ObjectStorage>()
        whenever(storage.putFileAsync(any<String>(), any<Path>()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(PutResult("k", 1L, null)))
        val n = 400

        data class Result(
            val level: Int, val recPerSec: Double, val mbPerSec: Double,
            val meanUs: Double, val p50Us: Double, val p95Us: Double, val p99Us: Double,
            val ratio: Double,
        )

        fun bench(level: Int): Result {
            // warmup (separate writer, discarded)
            GzipJsonlChunkWriter(
                chunkKey = "runs/warmup/item-equipment/part-1.jsonl.gz",
                partIndex = 1, maxRecords = Int.MAX_VALUE,
                maxUncompressedBytes = 100L * 1024 * 1024 * 1024,
                objectMapper = objectMapper, objectStorage = storage,
                clock = Clock.systemUTC(), compressionLevel = level,
            ).close()
            // measured
            val w = GzipJsonlChunkWriter(
                chunkKey = "runs/bench/item-equipment/part-1.jsonl.gz",
                partIndex = 1, maxRecords = Int.MAX_VALUE,
                maxUncompressedBytes = 100L * 1024 * 1024 * 1024,
                objectMapper = objectMapper, objectStorage = storage,
                clock = Clock.systemUTC(), compressionLevel = level,
            )
            val rec = SnapshotChunkRecord.PreSerialized(
                key = "k", endpoint = "item-equipment", keyType = "OCID",
                httpStatus = 200, fetchedAt = Instant.EPOCH, bodyBytes = body,
            )
            val lats = LongArray(n)
            val t0 = System.nanoTime()
            repeat(n) { i ->
                val s = System.nanoTime()
                w.appendPreSerialized(rec)
                lats[i] = System.nanoTime() - s
            }
            val secs = (System.nanoTime() - t0) / 1e9
            val stats = w.close()
            lats.sort()
            fun pct(p: Double) = lats[minOf(n - 1, (n * p).toInt())].toDouble()
            val ratio = if (stats.compressedBytes > 0) stats.uncompressedBytes.toDouble() / stats.compressedBytes else 0.0
            return Result(
                level, n / secs, n * body.size / secs / 1e6,
                lats.average() / 1000.0, pct(0.5) / 1000.0, pct(0.95) / 1000.0, pct(0.99) / 1000.0, ratio,
            )
        }

        val r6 = bench(6)
        val r1 = bench(1)
        val msg = buildString {
            appendLine("gzip level 6 vs 1 — synthetic 218KB item-equip-sized body (hot path: appendPreSerialized)")
            appendLine("             rate(rec/s)  rate(MB/s)  lat-mean  p50   p95   p99     ratio")
            appendLine(String.format("level 6   :  %8.0f    %7.1f    %6.0f  %5.0f %5.0f %5.0f   %5.1f:1", r6.recPerSec, r6.mbPerSec, r6.meanUs, r6.p50Us, r6.p95Us, r6.p99Us, r6.ratio))
            appendLine(String.format("level 1   :  %8.0f    %7.1f    %6.0f  %5.0f %5.0f %5.0f   %5.1f:1", r1.recPerSec, r1.mbPerSec, r1.meanUs, r1.p50Us, r1.p95Us, r1.p99Us, r1.ratio))
            appendLine(String.format("delta     :  %.2fx rate, %.2fx lower mean latency, ratio %.1f:1->%.1f:1", r1.recPerSec / r6.recPerSec, r6.meanUs / r1.meanUs, r6.ratio, r1.ratio))
            appendLine("body=$body bytes (synthetic). real item-equip ratio @ level 6 ~ 11.5:1 (endurance report)")
        }
        java.io.File("/tmp/gzip_bench_result.txt").writeText(msg)
        // Guard: level 1 must be materially faster than level 6 on the writer
        // hot path. Fails (surfacing the full comparison table in `msg`) if
        // someone reverts the default to level 6 or breaks the level wiring.
        assertThat(r1.recPerSec).`as`(msg).isGreaterThanOrEqualTo(r6.recPerSec * 1.5)
    }

    /** Synthesize a ~[targetBytes] compressible body resembling item-equipment JSON (~10:1 ratio). */
    private fun synthItemEquipBody(targetBytes: Int): ByteArray {
        val baos = java.io.ByteArrayOutputStream(targetBytes + 1024)
        val frag = ("""{"item_name":"잔혀된검","slot":"장비","ocid":"abc123","stats":{""" +
            """"str":999,"dex":888,"int":777,"luk":666,"hp":99999,"mp":99999,"attack":1234,"potential":"LEGENDARY","sockets":3}}""").toByteArray()
        while (baos.size() < targetBytes) baos.write(frag)
        return baos.toByteArray()
    }
}
