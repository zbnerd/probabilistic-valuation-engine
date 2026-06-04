package maple.externalapi.cleanup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConsumedChunkCleanupSchedulerDeleteFileTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newScheduler(): ConsumedChunkCleanupScheduler =
        ConsumedChunkCleanupScheduler(
            objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
            basePath = tempDir.toString(),
            maxPending = 100,
        )

    @Test
    fun `deleteFile returns true when file exists`() {
        val scheduler = newScheduler()
        val file = tempDir.resolve("chunk.json.gz")
        Files.write(file, "payload".toByteArray())

        assertThat(scheduler.deleteFile("chunk.json.gz")).isTrue()
    }

    @Test
    fun `deleteFile returns false when file is already gone (no exception)`() {
        val scheduler = newScheduler()

        assertThat(scheduler.deleteFile("not-there.json.gz")).isFalse()
    }

    @Test
    fun `deleteFile throws IOException when target is a directory`() {
        val scheduler = newScheduler()
        Files.createDirectory(tempDir.resolve("subdir.json.gz"))

        assertThatThrownBy { scheduler.deleteFile("subdir.json.gz") }
            .isInstanceOf(java.io.IOException::class.java)
    }
}
