package maple.pipeline.artifact.storage

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalFsPutStreamMultipartTest {

    @TempDir
    lateinit var tempDir: Path

    private val executors = mutableListOf<ExecutorService>()

    private fun newStorage(): LocalFsObjectStorage = LocalFsObjectStorage(
        basePath = tempDir.toString(),
        uploadExecutor = Executors.newVirtualThreadPerTaskExecutor().also(executors::add),
        meterRegistry = null,
    )

    @AfterEach
    fun closeExecutors() {
        executors.forEach(ExecutorService::close)
        executors.clear()
    }

    @Test
    fun `putStreamMultipart writes file and returns PutResult with size and checksum`() {
        val storage = newStorage()
        val data = "hello streaming world".toByteArray()
        val cf = storage.putStreamMultipart("test/stream.txt", ByteArrayInputStream(data))
        val result = AtomicReference<maple.expectation.common.storage.PutResult?>()
        cf.thenAccept(result::set)

        await().until(cf::isDone)

        assertThat(cf).isCompleted
        assertThat(cf).isNotCompletedExceptionally
        assertThat(result.get()?.key).isEqualTo("test/stream.txt")
        assertThat(result.get()?.size).isEqualTo(data.size.toLong())
        assertThat(result.get()?.checksum).isNotNull().hasSize(64)
    }

    @Test
    fun `putStreamMultipart cleans up temp file on success`() {
        val storage = newStorage()
        val data = "abc".toByteArray()
        val upload = storage.putStreamMultipart("test.txt", ByteArrayInputStream(data))

        await().until(upload::isDone)
        assertThat(upload).isCompleted

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
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("simulated read failure")
        }
        val upload = storage.putStreamMultipart("test.txt", badStream)

        await().until(upload::isDone)

        assertThat(upload).isCompletedExceptionally
        val tmpFiles = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `putStreamMultipart content matches input bytes`() {
        val storage = newStorage()
        val data = (0..255).map { it.toByte() }.toByteArray()
        val upload = storage.putStreamMultipart("bytes.bin", ByteArrayInputStream(data))
        await().until(upload::isDone)
        assertThat(upload).isCompleted
        val read = storage.get("bytes.bin")
        assertThat(read).isEqualTo(data)
    }
}
