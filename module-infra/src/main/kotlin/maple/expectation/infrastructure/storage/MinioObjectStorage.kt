package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.springframework.beans.factory.annotation.Autowired
import software.amazon.awssdk.services.s3.S3Client
import java.io.InputStream
import java.time.Instant

/**
 * Stub for [ObjectStorage] backed by MinIO/S3. Full implementation lives in Task 5
 * (MinioObjectStorage TDD pair). This stub exists only so that [StorageConfig] can
 * reference the class without compile errors. Every method throws.
 */
class MinioObjectStorage(
    private val props: MinioProperties,
    private val s3: S3Client,
    @Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {
    private fun stub(): Nothing = error("MinioObjectStorage not yet implemented (Task 5)")
    override fun put(key: String, data: ByteArray): PutResult = stub()
    override fun putStream(key: String, input: InputStream): PutResult = stub()
    override fun get(key: String): ByteArray = stub()
    override fun getStream(key: String): InputStream = stub()
    override fun delete(key: String): Unit = stub()
    override fun exists(key: String): Boolean = stub()
    override fun listByPrefix(prefix: String): List<ObjectInfo> = stub()
    override fun deleteByPrefix(prefix: String): Long = stub()
    override fun calculatePrefixSize(prefix: String): Long = stub()
    override fun getLastModified(key: String): Instant? = stub()
}
