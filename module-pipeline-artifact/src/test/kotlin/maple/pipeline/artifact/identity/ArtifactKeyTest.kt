package maple.pipeline.artifact.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ArtifactKeyTest {
    @Test
    fun `parses a valid relative artifact key`() {
        val key = ArtifactKey.require("runs/r1/item-equipment/manifest.json")

        assertThat(key.value).isEqualTo("runs/r1/item-equipment/manifest.json")
        assertThat(key.toString()).isEqualTo(key.value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "/absolute",
            "a/../b",
            "a\\b",
            "a//b",
            "a/./b",
        ],
    )
    fun `rejects invalid artifact keys`(raw: String) {
        assertThat(ArtifactKey.parse(raw).isFailure).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "a/b", "a\\b", ".", ".."])
    fun `rejects invalid artifact segments`(raw: String) {
        assertThatThrownBy { ArtifactSegment.require(raw) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `converts a valid artifact key to a prefix with one trailing slash`() {
        val prefix = ArtifactKey.require("calculator/runs").asPrefix()

        assertThat(prefix.value).isEqualTo("calculator/runs/")
        assertThat(prefix.toString()).isEqualTo(prefix.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/absolute/", "runs", "runs//", "a/../"])
    fun `rejects invalid artifact prefixes`(raw: String) {
        assertThat(ArtifactPrefix.parse(raw).isFailure).isTrue()
    }
}
