package maple.externalapi.infra.storage

import java.nio.file.Path
import maple.expectation.error.exception.ArtifactNotFoundException
import maple.externalapi.domain.ExternalApiEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalExternalApiArtifactStoreAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newAdapter(basePath: String) = LocalExternalApiArtifactStoreAdapter(basePath = basePath)

    @Test
    fun `read returns ByteArray for existing artifact`() {
        val adapter = newAdapter(tempDir.toString())
        val endpoint = ExternalApiEndpoint.CHARACTER_BASIC
        val key = "user-1"
        adapter.store(endpoint, key, "hello".toByteArray())

        val result = adapter.read(endpoint, key)

        assertThat(result).isNotNull()
        assertThat(result.toString(Charsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `read throws ArtifactNotFoundException for missing file`() {
        val adapter = newAdapter(tempDir.toString())

        assertThatThrownBy { adapter.read(ExternalApiEndpoint.CHARACTER_BASIC, "missing") }
            .isInstanceOf(ArtifactNotFoundException::class.java)
    }

    @Test
    fun `ArtifactNotFoundException carries NoSuchFileException as cause`() {
        val adapter = newAdapter(tempDir.toString())

        val ex = kotlin.runCatching {
            adapter.read(ExternalApiEndpoint.CHARACTER_BASIC, "missing")
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ArtifactNotFoundException::class.java)
        assertThat(ex!!.cause).isInstanceOf(java.nio.file.NoSuchFileException::class.java)
    }

    @Test
    fun `store then read round-trip preserves payload bytes`() {
        val adapter = newAdapter(tempDir.toString())
        val endpoint = ExternalApiEndpoint.ITEM_EQUIPMENT
        val key = "user-2"
        val payload = "binary-data".toByteArray()

        adapter.store(endpoint, key, payload)
        val result = adapter.read(endpoint, key)

        assertThat(result).isEqualTo(payload)
    }
}
