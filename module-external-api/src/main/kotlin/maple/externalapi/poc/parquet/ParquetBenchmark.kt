package maple.externalapi.poc.parquet

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Side-by-side benchmark harness for Parquet+ZSTD vs gzip+JSONL on OCID mapping.
 * See issue 1423. Read-only on production paths.
 */
object ParquetBenchmark {

    data class FormatMetrics(
        val compressedBytes: Long,
        val writeMillis: Long,
        val readMillis: Long,
        val writeRecordsPerSecond: Long,
    )

    data class Comparison(val gzip: FormatMetrics, val parquet: FormatMetrics)

    fun run(
        records: List<Pair<String, String?>>,
        gzipFile: File,
        parquetFile: File,
    ): Comparison {
        // Write gzip
        val gzipWriteStart = System.currentTimeMillis()
        GZIPOutputStream(FileOutputStream(gzipFile)).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                for ((ign, ocid) in records) {
                    val ocidJson = if (ocid != null) "\"$ocid\"" else "null"
                    w.write("{\"userIgn\":\"$ign\",\"ocid\":$ocidJson}\n")
                }
            }
        }
        val gzipWriteMs = System.currentTimeMillis() - gzipWriteStart

        // Read gzip back (counts lines, decompressed)
        val gzipReadStart = System.currentTimeMillis()
        val gzipReadCount = GZIPInputStream(FileInputStream(gzipFile)).bufferedReader().useLines { it.count() }
        val gzipReadMs = System.currentTimeMillis() - gzipReadStart
        require(gzipReadCount == records.size) {
            "gzip read count mismatch: expected=${records.size} actual=$gzipReadCount"
        }

        // Write parquet
        val parquetWriteStart = System.currentTimeMillis()
        ParquetOcidMappingWriter(parquetFile).use { p ->
            for ((ign, ocid) in records) p.write(ign, ocid)
        }
        val parquetWriteMs = System.currentTimeMillis() - parquetWriteStart

        // Read parquet back
        val parquetReadStart = System.currentTimeMillis()
        val parquetReadCount = ParquetOcidMappingReader(parquetFile).use { r ->
            generateSequence { r.read() }.count()
        }
        val parquetReadMs = System.currentTimeMillis() - parquetReadStart
        require(parquetReadCount == records.size) {
            "parquet read count mismatch: expected=${records.size} actual=$parquetReadCount"
        }

        return Comparison(
            gzip = FormatMetrics(
                compressedBytes = gzipFile.length(),
                writeMillis = gzipWriteMs,
                readMillis = gzipReadMs,
                writeRecordsPerSecond = if (gzipWriteMs > 0) records.size * 1000L / gzipWriteMs else -1,
            ),
            parquet = FormatMetrics(
                compressedBytes = parquetFile.length(),
                writeMillis = parquetWriteMs,
                readMillis = parquetReadMs,
                writeRecordsPerSecond = if (parquetWriteMs > 0) records.size * 1000L / parquetWriteMs else -1,
            ),
        )
    }
}