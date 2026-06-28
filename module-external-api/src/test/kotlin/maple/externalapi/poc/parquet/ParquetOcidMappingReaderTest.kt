package maple.externalapi.poc.parquet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat

class ParquetOcidMappingReaderTest {

    @Test
    fun `streams records one at a time`(@TempDir tempDir: Path) {
        val outputFile = tempDir.resolve("ocid-mapping.parquet").toFile()
        val expected = listOf(
            "캐넌1" to "ocid-aaa",
            "캐넌2" to "ocid-bbb",
            "캐넌3" to null,
        )

        // Write via writer (round-trip scenario)
        ParquetOcidMappingWriter(outputFile).use { w ->
            for ((ign, ocid) in expected) w.write(ign, ocid)
        }

        // When: stream read
        val read = ParquetOcidMappingReader(outputFile).use { r ->
            generateSequence { r.read() }.toList()
        }

        // Then
        assertThat(read).hasSize(expected.size)
        for (i in expected.indices) {
            assertThat(read[i].userIgn).isEqualTo(expected[i].first)
            assertThat(read[i].ocid).isEqualTo(expected[i].second)
        }
    }
}
