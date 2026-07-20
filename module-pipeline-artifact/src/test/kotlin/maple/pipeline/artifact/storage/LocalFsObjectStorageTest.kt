package maple.pipeline.artifact.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalFsObjectStorageTest : ObjectStorageContract() {

    @TempDir
    lateinit var tempDir: Path

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val ownedExecutors = mutableListOf<ExecutorService>()

    private fun newStorage(basePath: String = tempDir.toString()): LocalFsObjectStorage = LocalFsObjectStorage(basePath, newUploadExecutor(), meterRegistry = null)

    override fun contractStorage(): ConditionalObjectStorage = newStorage()

    override fun contractKey(relative: String): String = "contract/$relative"

    private fun newUploadExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor().also(ownedExecutors::add)

    @AfterEach
    fun closeOwnedExecutors() {
        ownedExecutors.forEach(ExecutorService::close)
        ownedExecutors.clear()
    }

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

    private fun measuredMib(): Double = FIXTURE_BYTES.toDouble() * MEASURED_OBJECTS / BYTES_PER_MIB

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
        val moduleDirectory = if (workingDirectory.fileName.toString() == "module-pipeline-artifact") {
            workingDirectory
        } else {
            workingDirectory.resolve("module-pipeline-artifact")
        }
        return moduleDirectory.resolve("build/reports/artifact-evidence/local-fs-object-storage.json")
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
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
    fun `putFileAsync borrows caller file until completion`() {
        val storage = newStorage()
        val source = Files.createTempFile("caller-owned-", ".bin")
        Files.writeString(source, "payload")

        val upload = storage.putFileAsync("a/b.bin", source).toCompletableFuture()

        await().until(upload::isDone)
        assertThat(upload).isCompleted
        assertThat(upload).isNotCompletedExceptionally
        assertThat(Files.readString(source)).isEqualTo("payload")
        assertThat(storage.get("a/b.bin")).isEqualTo("payload".toByteArray())
        Files.delete(source)
    }

    @Test
    fun `putFile borrows caller file from another filesystem`() {
        val sharedMemory = Path.of("/dev/shm")
        assumeTrue(Files.isDirectory(sharedMemory))
        val storage = newStorage()
        val source = Files.createTempFile(sharedMemory, "caller-owned-", ".bin")
        Files.writeString(source, "cross-filesystem")

        val result = runCatching {
            storage.putFile("cross-fs/value.bin", source)
            assertThat(Files.readString(source)).isEqualTo("cross-filesystem")
            assertThat(storage.get("cross-fs/value.bin")).isEqualTo("cross-filesystem".toByteArray())
        }
        Files.deleteIfExists(source)

        assertThat(result.exceptionOrNull()).isNull()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `putStream leaves caller stream open`() {
        val storage = newStorage()
        val input = CloseTrackingInputStream("stream".toByteArray())

        storage.putStream("streams/sync.bin", input)

        assertThat(input.wasClosed).isFalse
        assertThat(storage.get("streams/sync.bin")).isEqualTo("stream".toByteArray())
        input.close()
    }

    @Test
    fun `putStreamMultipart leaves caller stream open`() {
        val storage = newStorage()
        val input = CloseTrackingInputStream("multipart".toByteArray())
        val upload = storage.putStreamMultipart("streams/async.bin", input)

        await().until(upload::isDone)
        assertThat(upload).isCompleted
        assertThat(upload).isNotCompletedExceptionally
        assertThat(input.wasClosed).isFalse
        assertThat(storage.get("streams/async.bin")).isEqualTo("multipart".toByteArray())
        input.close()
    }

    @Test
    fun `putIfAbsent preserves same existing bytes on replay`() {
        val storage = newStorage()
        val created = AtomicReference<PutIfAbsentResult?>()
        val replayed = AtomicReference<PutIfAbsentResult?>()
        val first = storage.putIfAbsent("inbox/event.json", "canonical".toByteArray()).toCompletableFuture()
            .thenAccept(created::set)
        await().until(first::isDone)
        val replay = storage.putIfAbsent("inbox/event.json", "canonical".toByteArray()).toCompletableFuture()
            .thenAccept(replayed::set)

        await().until(replay::isDone)

        assertThat(first).isCompleted
        assertThat(replay).isCompleted
        assertThat(created.get()).isInstanceOf(PutIfAbsentResult.Created::class.java)
        val existing = replayed.get()
        assertThat(existing).isInstanceOf(PutIfAbsentResult.Existing::class.java)
        if (existing is PutIfAbsentResult.Existing) {
            assertThat(existing.bytes).isEqualTo("canonical".toByteArray())
        }
    }

    @Test
    fun `putIfAbsent preserves original bytes on conflicting replay`() {
        val storage = newStorage()
        val first = storage.putIfAbsent("inbox/conflict.json", "original".toByteArray()).toCompletableFuture()
        await().until(first::isDone)
        val replayed = AtomicReference<PutIfAbsentResult?>()
        val replay = storage.putIfAbsent("inbox/conflict.json", "different".toByteArray()).toCompletableFuture()
            .thenAccept(replayed::set)

        await().until(replay::isDone)

        assertThat(replay).isCompleted
        val existing = replayed.get()
        assertThat(existing).isInstanceOf(PutIfAbsentResult.Existing::class.java)
        if (existing is PutIfAbsentResult.Existing) {
            assertThat(existing.bytes).isEqualTo("original".toByteArray())
        }
        assertThat(storage.get("inbox/conflict.json")).isEqualTo("original".toByteArray())
    }

    @Test
    fun `putIfAbsent creates exactly one object under a race`() {
        val storage = newStorage()
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<PutIfAbsentResult>()
        val first = storage.putIfAbsent("inbox/race.json", "first".toByteArray()).toCompletableFuture()
            .thenAccept(outcomes::add)
        val second = storage.putIfAbsent("inbox/race.json", "second".toByteArray()).toCompletableFuture()
            .thenAccept(outcomes::add)

        await().until { first.isDone && second.isDone }

        assertThat(first).isCompleted
        assertThat(second).isCompleted
        assertThat(outcomes.filterIsInstance<PutIfAbsentResult.Created>()).hasSize(1)
        assertThat(outcomes.filterIsInstance<PutIfAbsentResult.Existing>()).hasSize(1)
        assertThat(storage.get("inbox/race.json"))
            .isIn("first".toByteArray(), "second".toByteArray())
    }

    @Test
    fun `listPage traverses 1001 objects without gaps or duplicates`() {
        val storage = newStorage()
        val pageDirectory = Files.createDirectories(tempDir.resolve("paged"))
        repeat(1_001) { index ->
            Files.writeString(pageDirectory.resolve("object-${index.toString().padStart(4, '0')}.json"), index.toString())
        }
        val prefix = maple.pipeline.artifact.identity.ArtifactPrefix.require("paged/")

        val first = storage.listPage(prefix, afterKey = null, limit = 1_000)
        val second = storage.listPage(prefix, afterKey = first.nextAfterKey, limit = 1_000)
        val keys = (first.objects + second.objects).map { it.key }

        assertThat(first.objects).hasSize(1_000)
        assertThat(first.nextAfterKey?.value).isEqualTo("paged/object-0999.json")
        assertThat(second.objects).hasSize(1)
        assertThat(second.nextAfterKey).isNull()
        assertThat(keys).hasSize(1_001).doesNotHaveDuplicates()
        assertThat(keys).containsExactlyElementsOf(
            (0..1_000).map { index -> "paged/object-${index.toString().padStart(4, '0')}.json" },
        )
    }

    @Test
    fun `listPage rejects invalid limits and foreign cursors`() {
        val storage = newStorage()
        val prefix = maple.pipeline.artifact.identity.ArtifactPrefix.require("paged/")
        val foreignCursor = maple.pipeline.artifact.identity.ArtifactKey.require("other/object.json")

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            storage.listPage(prefix, afterKey = null, limit = 0)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            storage.listPage(prefix, afterKey = null, limit = 1_001)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            storage.listPage(prefix, afterKey = foreignCursor, limit = 10)
        }
    }

    @Test
    fun `putFileAsync completes exceptionally and removes sibling temp when directory force fails`() {
        val executor = newUploadExecutor()
        val storage = LocalFsObjectStorage(
            basePath = tempDir,
            uploadExecutor = executor,
            meterRegistry = null,
            directoryForce = { throw IOException("simulated directory force failure") },
        )
        val source = Files.createTempFile("caller-owned-", ".bin")
        Files.writeString(source, "payload")

        val upload = storage.putFileAsync("failure/value.bin", source)

        await().until(upload::isDone)
        assertThat(upload).isCompletedExceptionally
        assertThat(Files.readString(source)).isEqualTo("payload")
        assertThat(siblingTemps(tempDir)).isEmpty()
        Files.delete(source)
    }

    private fun siblingTemps(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { path -> path.fileName.toString().endsWith(".tmp") }.toList()
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
        storage.put("runs/run-1/chunk-1.gz", "12345".toByteArray()) // 5 bytes
        storage.put("runs/run-1/chunk-2.gz", "678".toByteArray()) // 3 bytes
        storage.put("runs/run-2/chunk-1.gz", "abc".toByteArray()) // 3 bytes
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
        storage.put("p/a.txt", "12345".toByteArray()) // 5
        storage.put("p/b.txt", "678".toByteArray()) // 3
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

    private class CloseTrackingInputStream(data: ByteArray) : ByteArrayInputStream(data) {
        var wasClosed: Boolean = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }
}
