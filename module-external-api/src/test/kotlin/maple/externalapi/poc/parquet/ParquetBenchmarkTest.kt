package maple.externalapi.poc.parquet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat

class ParquetBenchmarkTest {

    @Test
    fun `produces numeric benchmark output for both formats`(@TempDir tempDir: Path) {
        val records = (1..10_000).map { i ->
            "ign-$i" to if (i % 100 == 0) null else "ocid-$i"
        }
        val gzipFile = tempDir.resolve("gzip.jsonl.gz").toFile()
        val parquetFile = tempDir.resolve("parquet.parquet").toFile()

        val result = ParquetBenchmark.run(records, gzipFile, parquetFile)

        // Sanity: numbers present + both files have content
        assertThat(result.gzip.compressedBytes).isGreaterThan(0)
        assertThat(result.parquet.compressedBytes).isGreaterThan(0)
        assertThat(result.gzip.writeRecordsPerSecond).isGreaterThan(0)
        assertThat(result.parquet.writeRecordsPerSecond).isGreaterThan(0)
        assertThat(result.gzip.writeMillis).isGreaterThanOrEqualTo(0)
        assertThat(result.parquet.writeMillis).isGreaterThanOrEqualTo(0)

        // Print metrics to test log so a single `--info` run captures them
        println("PARQUET_BENCHMARK_RESULT: $result")
    }
}