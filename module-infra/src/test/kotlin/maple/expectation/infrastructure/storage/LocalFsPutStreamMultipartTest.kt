package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class LocalFsPutStreamMultipartTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStorage(): LocalFsObjectStorage =
        LocalFsObjectStorage(
            basePath = tempDir.toString(),
            uploadExecutor = Executors.newVirtualThreadPerTaskExecutor(),
            meterRegistry = null,
        )

    @Test
    fun `putStreamMultipart writes file and returns PutResult with size and checksum`() {
        val storage = newStorage()
        val data = "hello streaming world".toByteArray()
        val cf = storage.putStreamMultipart("test/stream.txt", ByteArrayInputStream(data))
        val result = cf.get()  // .get() OK in test only
        assertThat(result.key).isEqualTo("test/stream.txt")
        assertThat(result.size).isEqualTo(data.size.toLong())
        assertThat(result.checksum).isNotNull().hasSize(64)  // SHA-256 hex
    }

    @Test
    fun `putStreamMultipart cleans up temp file on success`() {
        val storage = newStorage()
        val data = "abc".toByteArray()
        storage.putStreamMultipart("test.txt", ByteArrayInputStream(data)).get()

        val tmpFiles = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `putStreamMultipart cleans up temp file on failure`() {
        val storage = newStorage()
        val badStream = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("simulated read failure")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw java.io.IOException("simulated read failure")
        }
        org.junit.jupiter.api.assertThrows<java.util.concurrent.ExecutionException> {
            storage.putStreamMultipart("test.txt", badStream).get()
        }
        val tmpFiles = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `putStreamMultipart content matches input bytes`() {
        val storage = newStorage()
        val data = (0..255).map { it.toByte() }.toByteArray()
        storage.putStreamMultipart("bytes.bin", ByteArrayInputStream(data)).get()
        val read = storage.get("bytes.bin")
        assertThat(read).isEqualTo(data)
    }
}
