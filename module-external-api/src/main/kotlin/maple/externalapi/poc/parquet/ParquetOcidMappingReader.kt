package maple.externalapi.poc.parquet

import org.apache.avro.generic.GenericRecord
import org.apache.parquet.avro.AvroParquetReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.io.LocalInputFile
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File

/**
 * Streaming reader for Parquet OCID mapping. See issue 1423.
 *
 * Uses parquet-common LocalInputFile (no hadoop-common at runtime — only test classpath).
 */
class ParquetOcidMappingReader(
    inputFile: File,
) : Closeable {
    private val log = LoggerFactory.getLogger(ParquetOcidMappingReader::class.java)
    private val reader: ParquetReader<GenericRecord> = AvroParquetReader
        .builder<GenericRecord>(LocalInputFile(inputFile.toPath()))
        .build()

    data class OcidRecord(val userIgn: String, val ocid: String?)

    fun read(): OcidRecord? {
        val record = reader.read() ?: return null
        return OcidRecord(
            userIgn = record.get("userIgn").toString(),
            ocid = record.get("ocid")?.toString(),
        )
    }

    override fun close() {
        runCatching { reader.close() }
            .onFailure { log.warn("Parquet reader close failed", it) }
    }
}
