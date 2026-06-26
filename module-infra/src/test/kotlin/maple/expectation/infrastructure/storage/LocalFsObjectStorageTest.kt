package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class LocalFsObjectStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStorage(basePath: String = tempDir.toString()): LocalFsObjectStorage =
        LocalFsObjectStorage(basePath, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), meterRegistry = null)

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
}
