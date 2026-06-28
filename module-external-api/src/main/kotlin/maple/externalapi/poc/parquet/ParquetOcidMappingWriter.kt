package maple.externalapi.poc.parquet

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File

/**
 * Side-by-side PoC writer for OCID mapping in Parquet+ZSTD format.
 * See issue 1423. Does NOT replace JSONL.gz output.
 *
 * Uses parquet-common LocalOutputFile (no hadoop-common at runtime — only test classpath).
 * ZSTD via default CompressionCodecName — level is delegated to zstd-jni's codec default.
 */
class ParquetOcidMappingWriter(
    outputFile: File,
    schema: Schema = OCID_MAPPING_SCHEMA,
) : Closeable {
    private val log = LoggerFactory.getLogger(ParquetOcidMappingWriter::class.java)
    private val writer: ParquetWriter<GenericRecord> = AvroParquetWriter
        .builder<GenericRecord>(LocalOutputFile(outputFile.toPath()))
        .withSchema(schema)
        .withCompressionCodec(CompressionCodecName.ZSTD)
        .build()

    fun write(userIgn: String, ocid: String?) {
        val record: GenericRecord = GenericData.Record(OCID_MAPPING_SCHEMA).apply {
            put("userIgn", userIgn)
            put("ocid", ocid)
            put("schema_version", 1)
        }
        writer.write(record)
    }

    override fun close() {
        runCatching { writer.close() }
            .onFailure { log.warn("Parquet writer close failed", it) }
    }

    companion object {
        val OCID_MAPPING_SCHEMA: Schema = Schema.Parser().parse(
            """
            {
              "type": "record",
              "name": "OcidMapping",
              "namespace": "maple.common.avro",
              "fields": [
                {"name": "userIgn", "type": "string"},
                {"name": "ocid", "type": ["null", "string"], "default": null},
                {"name": "schema_version", "type": "int", "default": 1}
              ]
            }
            """.trimIndent()
        )
    }
}