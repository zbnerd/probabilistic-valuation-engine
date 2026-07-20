package maple.calculator.probability

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CubeProbabilityResourceParityTest {

    @Test
    fun `calculator and compatibility resources remain byte-identical at the frozen SHA`() {
        val root = repositoryRoot()
        val calculator = root.resolve("module-calculator/src/main/resources/data/cube_probability.csv")
        val compatibility = root.resolve("module-infra/src/main/resources/data/cube_probability.csv")

        assertThat(calculator).exists()
        assertThat(compatibility).exists()
        assertThat(Files.mismatch(calculator, compatibility)).isEqualTo(-1L)
        assertThat(sha256(calculator)).isEqualTo(BASELINE_SHA)
        assertThat(sha256(compatibility)).isEqualTo(BASELINE_SHA)
        assertThat(Files.lines(calculator).use { lines -> lines.count() - 1 }).isEqualTo(413_802L)
    }

    private fun repositoryRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        var cursor: Path? = start
        repeat(5) {
            val candidate = cursor
            if (candidate != null && Files.isDirectory(candidate.resolve("module-calculator"))) {
                return candidate
            }
            cursor = candidate?.parent
        }
        throw IllegalStateException("Could not locate repository root from $start")
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BASELINE_SHA = "9a329fe4b861c9f21b69d766e1847e59982c2a02ea4f30c6e5b332a7f2e955c0"
    }
}
