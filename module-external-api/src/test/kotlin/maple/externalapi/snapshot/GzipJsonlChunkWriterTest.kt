package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.storage.LocalFsObjectStorage
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GzipJsonlChunkWriterTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"), ZoneOffset.UTC)
    private val directExecutor = Executor { command -> command.run() }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `artifact evidence report is produced when enabled`() {
        assumeTrue(System.getenv("ARTIFACT_EVIDENCE_ENABLED") == "1")
        val jvm = captureEvidenceJvm()
        assertEvidenceJvm(jvm)

        val lines = deterministicJsonLines()
        val records = lines.mapIndexed { index, line ->
            SnapshotChunkRecord.PreSerialized(
                key = "artifact-$index",
                endpoint = "artifact-evidence",
                keyType = "INDEX",
                httpStatus = 200,
                fetchedAt = Instant.EPOCH,
                bodyBytes = line,
            )
        }
        val fixtureSha256 = sha256Hex(lines)
        val evidenceRoot = Files.createDirectories(tempDir.resolve("artifact-evidence"))

        val warmupDirectory = Files.createDirectory(evidenceRoot.resolve("warmup"))
        val warmupStorage = LocalFsObjectStorage(warmupDirectory.toString(), directExecutor, meterRegistry = null)
        val warmupTempFilesBefore = chunkTempFiles()
        val warmupChunks = (0 until WARMUP_CHUNKS).map { chunkIndex ->
            writeEvidenceChunk(warmupStorage, "warmup/chunk-$chunkIndex.jsonl.gz", chunkIndex, records)
        }
        awaitUploads(warmupChunks)
        val warmupTempFileDelta = assertNoChunkTempFileDelta(
            before = warmupTempFilesBefore,
            after = chunkTempFiles(),
            phase = "warmup",
        )

        val measurements = (1..REPETITIONS).map { repetition ->
            val repetitionDirectory = Files.createDirectory(evidenceRoot.resolve("repetition-$repetition"))
            val storage = LocalFsObjectStorage(repetitionDirectory.toString(), directExecutor, meterRegistry = null)
            val tempFilesBefore = chunkTempFiles()
            val startedAt = System.nanoTime()
            val chunks = (0 until MEASURED_CHUNKS).map { chunkIndex ->
                writeEvidenceChunk(
                    storage,
                    "measured/chunk-${chunkIndex.toString().padStart(2, '0')}.jsonl.gz",
                    chunkIndex,
                    records,
                )
            }
            val uploadCompletion = awaitUploads(chunks)
            val elapsedNanos = uploadCompletion.completedAtNanos - startedAt
            val tempFileDelta = assertNoChunkTempFileDelta(
                before = tempFilesBefore,
                after = chunkTempFiles(),
                phase = "repetition-$repetition",
            )
            val compressedBytes = uploadCompletion.receipts.sumOf { receipt -> receipt.compressedBytes }
            val recordCount = chunks.sumOf { chunk -> chunk.recordCount.toLong() }
            val elapsedSeconds = elapsedNanos.toDouble() / NANOS_PER_SECOND

            GzipEvidenceMeasurement(
                repetition = repetition,
                elapsedNanos = elapsedNanos,
                recordCount = recordCount,
                recordsPerSecond = recordCount / elapsedSeconds,
                compressedBytes = compressedBytes,
                compressedMibPerSecond = compressedBytes / BYTES_PER_MIB / elapsedSeconds,
                tempFileDelta = tempFileDelta,
            )
        }
        val repetitions = measurements.map { measurement ->
            linkedMapOf<String, Any>(
                "repetition" to measurement.repetition,
                "elapsedNanos" to measurement.elapsedNanos,
                "recordCount" to measurement.recordCount,
                "recordsPerSecond" to measurement.recordsPerSecond,
                "compressedBytes" to measurement.compressedBytes,
                "compressedMibPerSecond" to measurement.compressedMibPerSecond,
                "tempFiles" to measurement.tempFileDelta.toJson(),
            )
        }
        val report = evidenceReportPath()
        Files.createDirectories(report.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            report.toFile(),
            linkedMapOf(
                "schemaVersion" to 1,
                "benchmark" to "gzip-jsonl-chunk-writer-local-fs",
                "jvm" to jvm.toJson(),
                "fixture" to linkedMapOf(
                    "lineCount" to lines.size,
                    "bytesPerLine" to JSON_LINE_BYTES,
                    "totalBytes" to lines.sumOf { line -> line.size.toLong() },
                    "sha256" to fixtureSha256,
                ),
                "compressionLevel" to Deflater.BEST_SPEED,
                "warmupChunks" to WARMUP_CHUNKS,
                "warmupTempFiles" to warmupTempFileDelta.toJson(),
                "measuredChunksPerRepetition" to MEASURED_CHUNKS,
                "repetitionCount" to REPETITIONS,
                "repetitions" to repetitions,
                "medianRecordsPerSecond" to median(measurements.map { it.recordsPerSecond }),
                "medianCompressedMibPerSecond" to median(measurements.map { it.compressedMibPerSecond }),
            ),
        )

        assertThat(report).exists()
    }

    private fun writeEvidenceChunk(
        storage: LocalFsObjectStorage,
        chunkKey: String,
        partIndex: Int,
        records: List<SnapshotChunkRecord.PreSerialized>,
    ): ChunkStats {
        val writer = GzipJsonlChunkWriter(
            chunkKey = ArtifactKey.require(chunkKey),
            partIndex = partIndex,
            maxRecords = EVIDENCE_RECORDS,
            maxUncompressedBytes = EVIDENCE_RECORDS.toLong() * JSON_LINE_BYTES,
            objectMapper = objectMapper,
            artifactWriter = artifactWriter(storage),
            clock = fixedClock,
        )
        records.forEach(writer::appendPreSerialized)
        return writer.close().also { stats ->
            assertThat(stats.recordCount).isEqualTo(EVIDENCE_RECORDS)
            assertThat(stats.uncompressedBytes).isEqualTo(EVIDENCE_RECORDS.toLong() * JSON_LINE_BYTES)
        }
    }

    private fun awaitUploads(chunks: List<ChunkStats>): UploadCompletion {
        val receipts = java.util.Collections.synchronizedList(mutableListOf<ArtifactReceipt>())
        val uploads = chunks.map { chunk ->
            chunk.uploadFuture.thenAccept { receipt -> receipts.add(receipt) }
        }
        assertThat(uploads).hasSameSizeAs(chunks)
        val allUploads = CompletableFuture.allOf(*uploads.toTypedArray())
        val completedAtNanos = AtomicLong()
        val completionFailure = AtomicReference<Throwable?>()
        allUploads.whenComplete { _, failure ->
            completionFailure.set(failure)
            completedAtNanos.set(System.nanoTime())
        }
        await().atMost(EVIDENCE_TIMEOUT).until { completedAtNanos.getAcquire() != 0L }
        assertThat(completionFailure.getAcquire())
            .describedAs("all gzip evidence uploads complete successfully")
            .isNull()
        return UploadCompletion(
            completedAtNanos = completedAtNanos.getAcquire(),
            receipts = receipts.toList(),
        )
    }

    private fun deterministicJsonLines(): Array<ByteArray> = Array(EVIDENCE_RECORDS) { index ->
        val prefix = "{\"record\":$index,\"payload\":\""
        val suffix = "\"}\n"
        val payloadLength = JSON_LINE_BYTES - prefix.length - suffix.length
        require(payloadLength > 0) { "JSON evidence line metadata exceeds the fixed line size" }
        val payload = CharArray(payloadLength) { offset ->
            ('a'.code + ((index + offset) % 26)).toChar()
        }.concatToString()
        "$prefix$payload$suffix".toByteArray().also { line ->
            require(line.size == JSON_LINE_BYTES) {
                "JSON evidence line must be exactly $JSON_LINE_BYTES bytes, got ${line.size}"
            }
        }
    }

    private fun sha256Hex(lines: Array<ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach(digest::update)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun chunkTempFiles(): Set<Path> {
        val systemTempDirectory = Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(systemTempDirectory).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name.startsWith("artifact-gzip-") && name.endsWith(".jsonl.gz.tmp")
            }.map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList()
                .toSet()
        }
    }

    private fun assertNoChunkTempFileDelta(
        before: Set<Path>,
        after: Set<Path>,
        phase: String,
    ): TempFileDelta {
        val added = after - before
        val removed = before - after
        assertThat(added)
            .describedAs("$phase must not add gzip chunk temp paths")
            .isEmpty()
        assertThat(removed)
            .describedAs("$phase must not remove unrelated gzip chunk temp paths")
            .isEmpty()
        return TempFileDelta(
            beforeCount = before.size,
            afterCount = after.size,
            addedPaths = added.map { it.fileName.toString() }.sorted(),
            removedPaths = removed.map { it.fileName.toString() }.sorted(),
        )
    }

    private fun TempFileDelta.toJson(): Map<String, Any> = linkedMapOf(
        "beforeCount" to beforeCount,
        "afterCount" to afterCount,
        "addedCount" to addedPaths.size,
        "removedCount" to removedPaths.size,
        "delta" to afterCount - beforeCount,
        // Assertions above guarantee these stable empty sets; full ambient snapshots are intentionally omitted.
        "addedPaths" to addedPaths,
        "removedPaths" to removedPaths,
    )

    private fun evidenceReportPath(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val moduleDirectory = if (workingDirectory.fileName.toString() == "module-external-api") {
            workingDirectory
        } else {
            workingDirectory.resolve("module-external-api")
        }
        return moduleDirectory.resolve("build/reports/artifact-evidence/gzip-jsonl-chunk-writer.json")
    }

    private fun captureEvidenceJvm(): EvidenceJvm {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        return EvidenceJvm(
            inputArguments = ManagementFactory.getRuntimeMXBean().inputArguments,
            runtimeInitialMemoryBytes = Runtime.getRuntime().totalMemory(),
            runtimeMaxMemoryBytes = Runtime.getRuntime().maxMemory(),
            heapInitialMemoryBytes = heap.init,
            heapMaxMemoryBytes = heap.max,
        )
    }

    private fun assertEvidenceJvm(jvm: EvidenceJvm) {
        assertThat(jvm.inputArguments).contains("-Xms1g", "-Xmx1g", "-XX:+UseG1GC")
        assertThat(jvm.inputArguments).doesNotContain("-Xms512m", "-Xmx2048m")
        assertThat(jvm.runtimeInitialMemoryBytes).isEqualTo(ONE_GIB)
        assertThat(jvm.runtimeMaxMemoryBytes).isEqualTo(ONE_GIB)
        assertThat(jvm.heapInitialMemoryBytes).isEqualTo(ONE_GIB)
        assertThat(jvm.heapMaxMemoryBytes).isEqualTo(ONE_GIB)
    }

    private fun EvidenceJvm.toJson(): Map<String, Any> = linkedMapOf(
        "inputArguments" to inputArguments,
        "runtimeInitialMemoryBytes" to runtimeInitialMemoryBytes,
        "runtimeMaxMemoryBytes" to runtimeMaxMemoryBytes,
        "heapInitialMemoryBytes" to heapInitialMemoryBytes,
        "heapMaxMemoryBytes" to heapMaxMemoryBytes,
    )

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    @Test
    fun `close uploads gzipped JSONL via putFileAsync and returns stats`() {
        val storage = mock<ConditionalObjectStorage>()
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
            chunkKey = ArtifactKey.require("runs/abc/ranking-overall/part-000001.jsonl.gz"),
            partIndex = 1,
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            artifactWriter = artifactWriter(storage),
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
        val receipt = awaitReceipt(stats)

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
        assertThat(receipt.compressedBytes).isGreaterThan(0)
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

    @Test
    fun `serialization failure before first write creates no artifact temp file`() {
        val storage = mock<ConditionalObjectStorage>()
        whenever(storage.putFileAsync(any<String>(), any<Path>())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val path = invocation.getArgument<Path>(1)
            CompletableFuture.completedFuture(PutResult(key, Files.size(path), null))
        }
        val serializationFailure = IllegalArgumentException("serialization failed")
        val failingMapper = object : ObjectMapper() {
            override fun writeValueAsBytes(value: Any?): ByteArray = throw serializationFailure
        }
        val before = chunkTempFiles()
        val writer = GzipJsonlChunkWriter(
            chunkKey = ArtifactKey.require("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"),
            partIndex = 1,
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = failingMapper,
            artifactWriter = artifactWriter(storage),
            clock = fixedClock,
        )

        try {
            assertThatThrownBy {
                writer.append(
                    SnapshotChunkRecord.Success(
                        bodyBytes = byteArrayOf(1),
                        key = "broken",
                        endpoint = "ranking-overall",
                        keyType = "DATE_PAGE",
                        httpStatus = 200,
                        fetchedAt = Instant.EPOCH,
                    ),
                )
            }.isSameAs(serializationFailure)
            assertThat(chunkTempFiles()).isEqualTo(before)
            verify(storage, never()).putFileAsync(any<String>(), any<Path>())
        } finally {
            awaitReceipt(writer.close())
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
        val storage = mock<ConditionalObjectStorage>()
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
            chunkKey = ArtifactKey.require("runs/big/item-equipment/part-000001.jsonl.gz"),
            partIndex = 1,
            maxRecords = Int.MAX_VALUE,
            // Production knob is 128MB; 32MB here keeps the test fast and
            // still well above the heap budget the old code blew through.
            maxUncompressedBytes = 32L * 1024 * 1024,
            objectMapper = objectMapper,
            artifactWriter = artifactWriter(storage),
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
        val receipt = awaitReceipt(stats)

        assertThat(stats.recordCount).isEqualTo(recordCount)
        assertThat(stats.uncompressedBytes).isGreaterThan(32L * 1024 * 1024)
        assertThat(receipt.compressedBytes).isGreaterThan(0)
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
        val storage = mock<ConditionalObjectStorage>()
        whenever(storage.putFileAsync(any<String>(), any<Path>()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(PutResult("k", 1L, null)))
        val n = 400

        data class Result(
            val level: Int,
            val recPerSec: Double,
            val mbPerSec: Double,
            val meanUs: Double,
            val p50Us: Double,
            val p95Us: Double,
            val p99Us: Double,
            val ratio: Double,
        )

        fun bench(level: Int): Result {
            // warmup (separate writer, discarded)
            GzipJsonlChunkWriter(
                chunkKey = ArtifactKey.require("runs/warmup/item-equipment/part-1.jsonl.gz"),
                partIndex = 1,
                maxRecords = Int.MAX_VALUE,
                maxUncompressedBytes = 100L * 1024 * 1024 * 1024,
                objectMapper = objectMapper,
                artifactWriter = artifactWriter(storage, level),
                clock = Clock.systemUTC(),
            ).close()
            // measured
            val w = GzipJsonlChunkWriter(
                chunkKey = ArtifactKey.require("runs/bench/item-equipment/part-1.jsonl.gz"),
                partIndex = 1,
                maxRecords = Int.MAX_VALUE,
                maxUncompressedBytes = 100L * 1024 * 1024 * 1024,
                objectMapper = objectMapper,
                artifactWriter = artifactWriter(storage, level),
                clock = Clock.systemUTC(),
            )
            val rec = SnapshotChunkRecord.PreSerialized(
                key = "k",
                endpoint = "item-equipment",
                keyType = "OCID",
                httpStatus = 200,
                fetchedAt = Instant.EPOCH,
                bodyBytes = body,
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
            val receipt = awaitReceipt(stats)
            lats.sort()
            fun pct(p: Double) = lats[minOf(n - 1, (n * p).toInt())].toDouble()
            val ratio = if (receipt.compressedBytes > 0) {
                stats.uncompressedBytes.toDouble() / receipt.compressedBytes
            } else {
                0.0
            }
            return Result(
                level,
                n / secs,
                n * body.size / secs / 1e6,
                lats.average() / 1000.0,
                pct(0.5) / 1000.0,
                pct(0.95) / 1000.0,
                pct(0.99) / 1000.0,
                ratio,
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
        val frag = (
            """{"item_name":"잔혀된검","slot":"장비","ocid":"abc123","stats":{""" +
                """"str":999,"dex":888,"int":777,"luk":666,"hp":99999,"mp":99999,"attack":1234,"potential":"LEGENDARY","sockets":3}}"""
            ).toByteArray()
        while (baos.size() < targetBytes) baos.write(frag)
        return baos.toByteArray()
    }

    private data class GzipEvidenceMeasurement(
        val repetition: Int,
        val elapsedNanos: Long,
        val recordCount: Long,
        val recordsPerSecond: Double,
        val compressedBytes: Long,
        val compressedMibPerSecond: Double,
        val tempFileDelta: TempFileDelta,
    )

    private data class UploadCompletion(
        val completedAtNanos: Long,
        val receipts: List<ArtifactReceipt>,
    )

    private data class TempFileDelta(
        val beforeCount: Int,
        val afterCount: Int,
        val addedPaths: List<String>,
        val removedPaths: List<String>,
    )

    private data class EvidenceJvm(
        val inputArguments: List<String>,
        val runtimeInitialMemoryBytes: Long,
        val runtimeMaxMemoryBytes: Long,
        val heapInitialMemoryBytes: Long,
        val heapMaxMemoryBytes: Long,
    )

    private fun artifactWriter(
        storage: ConditionalObjectStorage,
        compressionLevel: Int = Deflater.BEST_SPEED,
    ): DefaultArtifactWriter = DefaultArtifactWriter(storage, directExecutor, compressionLevel)

    private fun awaitReceipt(stats: ChunkStats): ArtifactReceipt {
        val receipt = AtomicReference<ArtifactReceipt?>()
        val failure = AtomicReference<Throwable?>()
        stats.uploadFuture.whenComplete { value, error ->
            receipt.set(value)
            failure.set(error)
        }
        await().atMost(EVIDENCE_TIMEOUT).until { receipt.get() != null || failure.get() != null }
        assertThat(failure.get()).isNull()
        return requireNotNull(receipt.get())
    }

    private companion object {
        const val ONE_GIB = 1024L * 1024L * 1024L
        const val EVIDENCE_RECORDS = 10_000
        const val JSON_LINE_BYTES = 1024
        const val WARMUP_CHUNKS = 3
        const val MEASURED_CHUNKS = 20
        const val REPETITIONS = 5
        const val BYTES_PER_MIB = 1024.0 * 1024.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val EVIDENCE_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
