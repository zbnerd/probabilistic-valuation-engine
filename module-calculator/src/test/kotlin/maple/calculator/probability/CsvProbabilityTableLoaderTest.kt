package maple.calculator.probability

import java.io.InputStream
import maple.expectation.core.calculation.error.ProbabilityTableInitializationException
import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.domain.model.CubeType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource

class CsvProbabilityTableLoaderTest {

    @Test
    fun `maps exact rows and records logical version checksum and observation`() {
        val loader = loader(SAMPLE_CSV)

        val snapshot = loader.load()

        val key = ProbabilityKey(CubeType.BLACK, 200, "모자", "레전드리", 1)
        assertThat(snapshot.rows(key)).containsExactly(
            ProbabilityRow("STR +12%", 0.25),
            ProbabilityRow("DEX +12%", 0.75),
        )
        assertThat(snapshot.version.logical).isEqualTo("csv-v1.0")
        assertThat(snapshot.version.contentSha256).isEqualTo(SAMPLE_SHA)
        assertThat(snapshot.rowCount).isEqualTo(2)
        assertThat(loader.lastObservation).isNotNull
        assertThat(loader.lastObservation?.rowCount).isEqualTo(2)
        assertThat(loader.lastObservation?.versionLabel).isEqualTo("csv-v1.0")
        assertThat(loader.lastObservation?.durationNanos).isPositive()
    }

    @Test
    fun `loads the production calculator resource with the frozen identity`() {
        val loader = CsvProbabilityTableLoader()

        val snapshot = loader.load()

        assertThat(snapshot.rowCount).isEqualTo(413_802)
        assertThat(snapshot.version.logical).isEqualTo("csv-v1.0")
        assertThat(snapshot.version.contentSha256).isEqualTo(BASELINE_SHA)
    }

    @Test
    fun `missing resource fails with typed initialization error`() {
        assertThatThrownBy {
            CsvProbabilityTableLoader(ClassPathResource("data/missing-cube-probability.csv")).load()
        }.isInstanceOf(ProbabilityTableInitializationException::class.java)
            .hasCauseInstanceOf(java.io.FileNotFoundException::class.java)
    }

    @Test
    fun `empty resource fails with typed initialization error`() {
        assertThatThrownBy { loader(HEADER).load() }
            .isInstanceOf(ProbabilityTableInitializationException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `malformed row fails with its parsing cause attached`() {
        val csv = HEADER + "STR +12%,not-a-rate,1,레전드리,200,모자,BLACK\n"

        assertThatThrownBy { loader(csv).load() }
            .isInstanceOf(ProbabilityTableInitializationException::class.java)
            .hasCauseInstanceOf(com.fasterxml.jackson.databind.JsonMappingException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["-0.1", "NaN", "Infinity"])
    fun `invalid finite-range rates fail during snapshot construction`(rate: String) {
        val csv = HEADER + "STR +12%,$rate,1,레전드리,200,모자,BLACK\n"

        assertThatThrownBy { loader(csv).load() }
            .isInstanceOf(ProbabilityTableInitializationException::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `conflicting duplicate identity fails during snapshot construction`() {
        val csv = HEADER +
            "STR +12%,0.25,1,레전드리,200,모자,BLACK\n" +
            "STR +12%,0.75,1,레전드리,200,모자,BLACK\n"

        assertThatThrownBy { loader(csv).load() }
            .isInstanceOf(ProbabilityTableInitializationException::class.java)
            .hasCauseInstanceOf(ProbabilityTableInitializationException::class.java)
    }

    @Test
    fun `original resource failure is preserved as the direct cause`() {
        val original = IllegalStateException("source unavailable")
        val resource = object : AbstractResource() {
            override fun getDescription(): String = "failing test resource"

            override fun getInputStream(): InputStream = throw original
        }

        val failure = catchThrowable { CsvProbabilityTableLoader(resource).load() }

        assertThat(failure).isInstanceOf(ProbabilityTableInitializationException::class.java)
        assertThat(failure.cause).isSameAs(original)
    }

    private fun loader(csv: String): CsvProbabilityTableLoader = CsvProbabilityTableLoader(
        ByteArrayResource(csv.toByteArray(Charsets.UTF_8)),
    )

    private companion object {
        const val HEADER =
            "option,rate,slot,potential_option_grade,base_equipment_level,item_equipment_slot,cube_type\n"
        const val SAMPLE_CSV = HEADER +
            "STR +12%,0.25,1,레전드리,200,모자,BLACK\n" +
            "DEX +12%,0.75,1,레전드리,200,모자,BLACK\n"
        const val SAMPLE_SHA = "7582471ef53919b87cb273a538a7b022cd79686b2c83048175f6cc3fcd32b1f5"
        const val BASELINE_SHA = "9a329fe4b861c9f21b69d766e1847e59982c2a02ea4f30c6e5b332a7f2e955c0"
    }
}
