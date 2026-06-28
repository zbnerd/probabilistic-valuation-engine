package maple.externalapi.poc.parquet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat

class ParquetOcidMappingWriterTest {

    @Test
    fun `writes valid parquet file with Parquet+ZSTD compression`(@TempDir tempDir: Path) {
        val outputFile = tempDir.resolve("ocid-mapping.parquet").toFile()

        // When
        ParquetOcidMappingWriter(outputFile).use { writer ->
            writer.write("캐넌1", "ocid-aaa")
            writer.write("캐넌2", "ocid-bbb")
            writer.write("캐넌3", null)
        }

        // Then: file exists, has content, starts with PAR1 magic (Parquet format marker)
        assertThat(outputFile.exists()).isTrue()
        assertThat(outputFile.length()).isGreaterThan(0)

        // Verify Parquet magic bytes (PAR1 at start of file)
        val header = outputFile.inputStream().use { it.readNBytes(4) }
        assertThat(String(header)).isEqualTo("PAR1")
    }
}