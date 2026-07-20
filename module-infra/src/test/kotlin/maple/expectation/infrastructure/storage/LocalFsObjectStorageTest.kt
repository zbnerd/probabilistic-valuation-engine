package maple.expectation.infrastructure.storage

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LocalFsObjectStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder().build()

    private fun newStorage(basePath: String = tempDir.toString()): LocalFsObjectStorage =
        LocalFsObjectStorage(basePath, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), meterRegistry = null)

    @Test
    fun `artifact evidence report is produced when enabled`() {
        assumeTrue(System.getenv("ARTIFACT_EVIDENCE_ENABLED") == "1")
        val jvm = captureEvidenceJvm()
        assertEvidenceJvm(jvm)

        val fixture = ByteArray(FIXTURE_BYTES).also { bytes ->
            Random(FIXTURE_SEED).nextBytes(bytes)
        }
        val fixtureSha256 = sha256Hex(fixture)
        val evidenceRoot = Files.createDirectories(tempDir.resolve("artifact-evidence"))
        val measurements = Executors.newFixedThreadPool(CONCURRENCY).use { executor ->
            val warmupDirectory = Files.createDirectory(evidenceRoot.resolve("warmup"))
            val warmupStorage = LocalFsObjectStorage(warmupDirectory.toString(), executor, meterRegistry = null)
            runConcurrentPuts(warmupStorage, fixture, WARMUP_OBJECTS, executor)

            (1..REPETITIONS).map { repetition ->
                val repetitionDirectory = Files.createDirectory(evidenceRoot.resolve("repetition-$repetition"))
                val storage = LocalFsObjectStorage(repetitionDirectory.toString(), executor, meterRegistry = null)
                val elapsedNanos = runConcurrentPuts(storage, fixture, MEASURED_OBJECTS, executor)
                val mibPerSecond = measuredMib() / (elapsedNanos.toDouble() / NANOS_PER_SECOND)
                Triple(repetition, elapsedNanos, mibPerSecond)
            }
        }
        val medianMibPerSecond = measurements
            .map { measurement -> measurement.third }
            .sorted()[REPETITIONS / 2]
        val repetitions = measurements.map { measurement ->
            linkedMapOf<String, Any>(
                "repetition" to measurement.first,
                "elapsedNanos" to measurement.second,
                "mibPerSecond" to measurement.third,
            )
        }
        val report = evidenceReportPath()
        Files.createDirectories(report.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            report.toFile(),
            linkedMapOf(
                "schemaVersion" to 1,
                "benchmark" to "local-fs-object-storage-put",
                "jvm" to jvm.toJson(),
                "fixture" to linkedMapOf(
                    "seed" to FIXTURE_SEED,
                    "bytes" to fixture.size,
                    "sha256" to fixtureSha256,
                ),
                "warmupObjects" to WARMUP_OBJECTS,
                "measuredObjectsPerRepetition" to MEASURED_OBJECTS,
                "concurrency" to CONCURRENCY,
                "repetitionCount" to REPETITIONS,
                "measuredMibPerRepetition" to measuredMib(),
                "repetitions" to repetitions,
                "medianMibPerSecond" to medianMibPerSecond,
            ),
        )

        assertThat(report).exists()
    }

    private fun runConcurrentPuts(
        storage: LocalFsObjectStorage,
        fixture: ByteArray,
        objectCount: Int,
        executor: Executor,
    ): Long {
        val startedAt = System.nanoTime()
        val writes = Array(objectCount) { index ->
            CompletableFuture.runAsync(
                { storage.put("objects/object-${index.toString().padStart(4, '0')}.bin", fixture) },
                executor,
            )
        }
        val allWrites = CompletableFuture.allOf(*writes)
        val completedAtNanos = AtomicLong()
        val completionFailure = AtomicReference<Throwable?>()
        allWrites.whenComplete { _, failure ->
            completionFailure.set(failure)
            completedAtNanos.set(System.nanoTime())
        }
        await().atMost(EVIDENCE_TIMEOUT).until { completedAtNanos.getAcquire() != 0L }
        val elapsedNanos = completedAtNanos.getAcquire() - startedAt

        assertThat(completionFailure.getAcquire())
            .describedAs("all LocalFS evidence writes complete successfully")
            .isNull()
        assertThat(storage.listByPrefix("objects/")).hasSize(objectCount)
        return elapsedNanos
    }

    private fun measuredMib(): Double =
        FIXTURE_BYTES.toDouble() * MEASURED_OBJECTS / BYTES_PER_MIB

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

    private fun evidenceReportPath(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val moduleDirectory = if (workingDirectory.fileName.toString() == "module-infra") {
            workingDirectory
        } else {
            workingDirectory.resolve("module-infra")
        }
        return moduleDirectory.resolve("build/reports/artifact-evidence/local-fs-object-storage.json")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    @Test
    fun `put and get round-trip returns identical bytes`() {
        val storage = newStorage()
        val data = "hello world".toByteArray()
        storage.put("test/file.txt", data)
        val read = storage.get("test/file.txt")
        assertThat(read).isEqualTo(data)
    }

    @Test
    fun `put returns PutResult with SHA-256 hex checksum`() {
        val storage = newStorage()
        val data = "abc".toByteArray()
        val result = storage.put("test.txt", data)
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(result.checksum).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(result.size).isEqualTo(data.size.toLong())
    }

    @Test
    fun `put creates parent directories automatically`() {
        val storage = newStorage()
        storage.put("deeply/nested/path/file.txt", "data".toByteArray())
        assertThat(Files.exists(tempDir.resolve("deeply/nested/path/file.txt"))).isTrue
    }

    @Test
    fun `put is atomic - no tmp file remains after success`() {
        val storage = newStorage()
        storage.put("test.txt", "data".toByteArray())
        val tmpFiles = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `exists returns true after put, false for missing key`() {
        val storage = newStorage()
        storage.put("present.txt", "data".toByteArray())
        assertThat(storage.exists("present.txt")).isTrue
        assertThat(storage.exists("missing.txt")).isFalse
    }

    @Test
    fun `get on missing key throws NoSuchFileException`() {
        val storage = newStorage()
        org.junit.jupiter.api.assertThrows<java.nio.file.NoSuchFileException> {
            storage.get("missing.txt")
        }
    }

    @Test
    fun `delete on missing key is no-op`() {
        val storage = newStorage()
        // Should not throw
        storage.delete("missing.txt")
    }

    @Test
    fun `deleteByPrefix removes nested files and returns byte count`() {
        val storage = newStorage()
        storage.put("runs/run-1/chunk-1.gz", "12345".toByteArray())  // 5 bytes
        storage.put("runs/run-1/chunk-2.gz", "678".toByteArray())     // 3 bytes
        storage.put("runs/run-2/chunk-1.gz", "abc".toByteArray())     // 3 bytes
        val deleted = storage.deleteByPrefix("runs/run-1/")
        assertThat(deleted).isEqualTo(8L)
        assertThat(storage.exists("runs/run-1/chunk-1.gz")).isFalse
        assertThat(storage.exists("runs/run-1/chunk-2.gz")).isFalse
        assertThat(storage.exists("runs/run-2/chunk-1.gz")).isTrue
    }

    @Test
    fun `listByPrefix returns nested objects with full depth`() {
        val storage = newStorage()
        storage.put("runs/run-1/a.gz", "1".toByteArray())
        storage.put("runs/run-1/sub/b.gz", "2".toByteArray())
        storage.put("runs/run-2/c.gz", "3".toByteArray())
        val keys = storage.listByPrefix("runs/run-1/").map { it.key }
        assertThat(keys).containsExactlyInAnyOrder(
            "runs/run-1/a.gz",
            "runs/run-1/sub/b.gz",
        )
    }

    @Test
    fun `listByPrefix returns empty list for non-existent prefix`() {
        val storage = newStorage()
        val result = storage.listByPrefix("nonexistent/")
        assertThat(result).isEmpty()
    }

    @Test
    fun `getLastModified returns null for missing key`() {
        val storage = newStorage()
        val result = storage.getLastModified("missing.txt")
        assertThat(result).isNull()
    }

    @Test
    fun `getLastModified returns Instant for existing key`() {
        val storage = newStorage()
        storage.put("present.txt", "data".toByteArray())
        val result = storage.getLastModified("present.txt")
        assertThat(result).isNotNull
        assertThat(result).isBeforeOrEqualTo(Instant.now())
    }

    @Test
    fun `calculatePrefixSize matches sum of file sizes`() {
        val storage = newStorage()
        storage.put("p/a.txt", "12345".toByteArray())  // 5
        storage.put("p/b.txt", "678".toByteArray())     // 3
        assertThat(storage.calculatePrefixSize("p/")).isEqualTo(8L)
    }

    @Test
    fun `calculatePrefixSize returns 0 for non-existent prefix`() {
        val storage = newStorage()
        assertThat(storage.calculatePrefixSize("nonexistent/")).isEqualTo(0L)
    }

    private companion object {
        const val ONE_GIB = 1024L * 1024L * 1024L
        const val FIXTURE_SEED = 745L
        const val FIXTURE_BYTES = 1024 * 1024
        const val WARMUP_OBJECTS = 32
        const val MEASURED_OBJECTS = 256
        const val CONCURRENCY = 8
        const val REPETITIONS = 5
        const val BYTES_PER_MIB = 1024.0 * 1024.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val EVIDENCE_TIMEOUT: Duration = Duration.ofMinutes(10)
    }

    private data class EvidenceJvm(
        val inputArguments: List<String>,
        val runtimeInitialMemoryBytes: Long,
        val runtimeMaxMemoryBytes: Long,
        val heapInitialMemoryBytes: Long,
        val heapMaxMemoryBytes: Long,
    )
}
