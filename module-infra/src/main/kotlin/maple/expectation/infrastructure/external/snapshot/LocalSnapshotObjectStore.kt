package maple.expectation.infrastructure.external.snapshot

import io.micrometer.core.instrument.MeterRegistry
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maple.expectation.util.GzipUtils.compress
import maple.expectation.util.GzipUtils.decompressBytes
import maple.expectation.util.HashUtils.sha256Hex
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.core.port.out.SnapshotObjectStoreResult
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LocalSnapshotObjectStore(
    @Value("\${snapshot.store.local.base-path:/data/snapshots}")
    private val basePath: String,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) : SnapshotObjectStore {

    private val writePermits = Semaphore(10)

    override fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
        val context = TaskContext.of("SnapshotStore", "Put", snapshot.objectKey)
        return executor.executeWithFinally({
            val permitStart = System.nanoTime()
            writePermits.acquire()
            meterRegistry.timer("snapshot.store.permit.wait").record(System.nanoTime() - permitStart, TimeUnit.NANOSECONDS)
            doPut(snapshot, data)
        }, {
            writePermits.release()
        }, context)
    }

    private fun doPut(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
        val gzipStart = System.nanoTime()
        val compressed = compress(data)
        meterRegistry.timer("snapshot.store.gzip").record(System.nanoTime() - gzipStart, TimeUnit.NANOSECONDS)

        val hashStart = System.nanoTime()
        val hash = sha256Hex(compressed)
        meterRegistry.timer("snapshot.store.hash").record(System.nanoTime() - hashStart, TimeUnit.NANOSECONDS)

        val fullPath = resolveFullPath(snapshot.objectKey)
        fullPath.parent.toFile().mkdirs()

        val writeStart = System.nanoTime()
        val tempFile = fullPath.resolveSibling(fullPath.fileName.toString() + ".tmp")
        FileOutputStream(tempFile.toFile()).use { fos ->
            fos.write(compressed)
        }
        java.nio.file.Files.move(tempFile, fullPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        meterRegistry.timer("snapshot.store.file.write").record(System.nanoTime() - writeStart, TimeUnit.NANOSECONDS)

        return SnapshotObjectStoreResult(
            objectKey = snapshot.objectKey,
            compressedSize = compressed.size.toLong(),
            hash = hash,
        )
    }

    override fun get(objectKey: String): ByteArray {
        val fullPath = resolveFullPath(objectKey)
        val compressed = Files.readAllBytes(fullPath)
        return decompressBytes(compressed)
    }

    override fun delete(objectKey: String) {
        val fullPath = resolveFullPath(objectKey)
        Files.deleteIfExists(fullPath)
    }

    private fun resolveFullPath(objectKey: String): Path {
        val logicalKey = objectKey.removePrefix("/")
        return Paths.get(basePath, logicalKey)
    }
}
