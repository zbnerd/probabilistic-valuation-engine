package maple.pipeline.artifact.storage

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.storage.ObjectInfo
import maple.pipeline.artifact.identity.ArtifactPrefix
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test

abstract class ObjectStorageContract {
    protected abstract fun contractStorage(): ConditionalObjectStorage

    protected abstract fun contractKey(relative: String): String

    @Test
    fun `contract put and get preserve bytes`() {
        val storage = contractStorage()
        val data = "contract-payload".toByteArray()

        storage.put(contractKey("put/value.bin"), data)

        assertThat(storage.get(contractKey("put/value.bin"))).isEqualTo(data)
    }

    @Test
    fun `contract putFileAsync leaves caller file untouched`() {
        val storage = contractStorage()
        val source = Files.createTempFile("caller-owned-contract-", ".bin")
        Files.writeString(source, "caller-owned")
        source.toFile().deleteOnExit()

        val upload = storage.putFileAsync(contractKey("file/value.bin"), source)

        await().atMost(ASYNC_TIMEOUT).until(upload::isDone)
        assertThat(upload).isCompleted
        assertThat(upload).isNotCompletedExceptionally
        assertThat(Files.readString(source)).isEqualTo("caller-owned")
        assertThat(storage.get(contractKey("file/value.bin"))).isEqualTo("caller-owned".toByteArray())
        Files.delete(source)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `contract putStream leaves caller stream open`() {
        val storage = contractStorage()
        val input = ContractCloseTrackingInputStream("stream".toByteArray())

        storage.putStream(contractKey("stream/sync.bin"), input)

        assertThat(input.wasClosed).isFalse
        assertThat(storage.get(contractKey("stream/sync.bin"))).isEqualTo("stream".toByteArray())
        input.close()
    }

    @Test
    fun `contract putStreamMultipart leaves caller stream open`() {
        val storage = contractStorage()
        val input = ContractCloseTrackingInputStream("multipart".toByteArray())
        val upload = storage.putStreamMultipart(contractKey("stream/async.bin"), input)

        await().atMost(ASYNC_TIMEOUT).until(upload::isDone)
        assertThat(upload).isCompleted
        assertThat(upload).isNotCompletedExceptionally
        assertThat(input.wasClosed).isFalse
        assertThat(storage.get(contractKey("stream/async.bin"))).isEqualTo("multipart".toByteArray())
        input.close()
    }

    @Test
    fun `contract conditional replay returns the original bytes`() {
        val storage = contractStorage()
        val created = storage.putIfAbsent(contractKey("conditional/value.json"), "original".toByteArray())
            .toCompletableFuture()
        await().atMost(ASYNC_TIMEOUT).until(created::isDone)
        val replayResult = AtomicReference<PutIfAbsentResult?>()
        val replay = storage.putIfAbsent(contractKey("conditional/value.json"), "different".toByteArray())
            .toCompletableFuture()
            .thenAccept(replayResult::set)

        await().atMost(ASYNC_TIMEOUT).until(replay::isDone)

        assertThat(created).isCompleted
        assertThat(replay).isCompleted
        val existing = replayResult.get()
        assertThat(existing).isInstanceOf(PutIfAbsentResult.Existing::class.java)
        if (existing is PutIfAbsentResult.Existing) {
            assertThat(existing.bytes).isEqualTo("original".toByteArray())
        }
    }

    @Test
    fun `contract pagination crosses the S3 page boundary without gaps`() {
        val storage = contractStorage()
        val prefixValue = contractKey("page/")
        val prefix = ArtifactPrefix.require(prefixValue)
        (0..1_000).chunked(PUT_BATCH_SIZE).forEach { batch ->
            val writes = batch.map { index ->
                storage.putIfAbsent(
                    "$prefixValue${index.toString().padStart(4, '0')}.json",
                    index.toString().toByteArray(),
                ).toCompletableFuture()
            }
            await().atMost(ASYNC_TIMEOUT).until { writes.all { it.isDone } }
            writes.forEach { write ->
                assertThat(write).isCompleted
                assertThat(write).isNotCompletedExceptionally
            }
        }

        val first = storage.listPage(prefix, afterKey = null, limit = 1_000)
        val second = storage.listPage(prefix, afterKey = first.nextAfterKey, limit = 1_000)
        val keys = (first.objects + second.objects).map(ObjectInfo::key)

        assertThat(first.objects).hasSize(1_000)
        assertThat(first.nextAfterKey?.value).isEqualTo("${prefixValue}0999.json")
        assertThat(second.objects).hasSize(1)
        assertThat(second.nextAfterKey).isNull()
        assertThat(keys).hasSize(1_001).doesNotHaveDuplicates()
        assertThat(keys).containsExactlyElementsOf(
            (0..1_000).map { index -> "$prefixValue${index.toString().padStart(4, '0')}.json" },
        )
    }

    @Test
    fun `contract page snapshot stays immutable for equality hash and callers`() {
        val objectInfo = ObjectInfo("page/value", 1L, Instant.EPOCH)
        val mutable = mutableListOf(objectInfo)

        val page = StorageObjectPage(mutable, null)
        val equivalent = StorageObjectPage(listOf(objectInfo), null)
        val initialHash = page.hashCode()
        mutable.clear()

        assertThat(page.objects.map(ObjectInfo::key)).containsExactly("page/value")
        assertThat(page).isEqualTo(equivalent)
        assertThat(page.hashCode()).isEqualTo(initialHash)
        assertThatThrownBy {
            (page.objects as MutableList<ObjectInfo>).add(
                ObjectInfo("page/other", 2L, Instant.EPOCH),
            )
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `contract eager listing returns nested objects`() {
        val storage = contractStorage()
        val prefix = contractKey("listing/")
        storage.put("${prefix}a.bin", "a".toByteArray())
        storage.put("${prefix}nested/b.bin", "b".toByteArray())

        assertThat(storage.listByPrefix(prefix).map(ObjectInfo::key))
            .containsExactlyInAnyOrder("${prefix}a.bin", "${prefix}nested/b.bin")
    }

    @Test
    fun `contract deleteByPrefix removes only matching objects`() {
        val storage = contractStorage()
        val prefix = contractKey("delete/")
        storage.put("${prefix}a.bin", "12345".toByteArray())
        storage.put("${prefix}b.bin", "678".toByteArray())
        storage.put(contractKey("keep/value.bin"), "keep".toByteArray())

        val deletedBytes = storage.deleteByPrefix(prefix)

        assertThat(deletedBytes).isEqualTo(8L)
        assertThat(storage.exists("${prefix}a.bin")).isFalse
        assertThat(storage.exists(contractKey("keep/value.bin"))).isTrue
    }

    @Test
    fun `contract calculatePrefixSize sums matching bytes`() {
        val storage = contractStorage()
        val prefix = contractKey("size/")
        storage.put("${prefix}a.bin", "12345".toByteArray())
        storage.put("${prefix}b.bin", "678".toByteArray())

        assertThat(storage.calculatePrefixSize(prefix)).isEqualTo(8L)
    }

    @Test
    fun `contract lastModified distinguishes present and missing keys`() {
        val storage = contractStorage()
        val present = contractKey("modified/present.bin")
        storage.put(present, "present".toByteArray())

        assertThat(storage.getLastModified(present)).isNotNull
        assertThat(storage.getLastModified(contractKey("modified/missing.bin"))).isNull()
    }

    private class ContractCloseTrackingInputStream(data: ByteArray) : ByteArrayInputStream(data) {
        var wasClosed: Boolean = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    private companion object {
        val ASYNC_TIMEOUT: Duration = Duration.ofMinutes(2)
        const val PUT_BATCH_SIZE = 32
    }
}
