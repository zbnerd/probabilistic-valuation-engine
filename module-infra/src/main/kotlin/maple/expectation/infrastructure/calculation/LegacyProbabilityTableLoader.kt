package maple.expectation.infrastructure.calculation

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import maple.expectation.core.calculation.error.ProbabilityTableInitializationException
import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.calculation.probability.ProbabilityTableVersion
import maple.expectation.core.domain.model.CubeType
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource

class LegacyProbabilityTableLoader(
    private val resource: Resource = ClassPathResource(DEFAULT_RESOURCE_PATH),
    private val logicalVersion: String = LOGICAL_VERSION,
) {
    fun load(): ProbabilityTableSnapshot = runCatching {
        val version = ProbabilityTableVersion(logicalVersion, calculateSha256())
        val builder = ProbabilityTableSnapshot.builder(version)
        var rowCount = 0

        resource.inputStream.use { input ->
            CSV_MAPPER.readerFor(LegacyCsvProbabilityRow::class.java)
                .with(CSV_SCHEMA)
                .readValues<LegacyCsvProbabilityRow>(input)
                .use { rows ->
                    while (rows.hasNextValue()) {
                        val row = rows.nextValue()
                        builder.add(
                            ProbabilityKey(
                                cubeType = row.cubeType,
                                level = row.level,
                                part = row.part,
                                grade = row.grade,
                                slot = row.slot,
                            ),
                            ProbabilityRow(row.optionName, row.rate),
                        )
                        rowCount++
                    }
                }
        }

        require(rowCount > 0) { "Probability CSV contains no data rows" }
        builder.build()
    }.getOrElse { cause ->
        throw ProbabilityTableInitializationException(
            "Failed to initialize legacy probability table from ${resource.description}",
            cause,
        )
    }

    private fun calculateSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resource.inputStream.use { source ->
            DigestInputStream(source, digest).use { hashingInput ->
                hashingInput.copyTo(OutputStream.nullOutputStream())
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class LegacyCsvProbabilityRow(
        @JsonProperty("cube_type") val cubeType: CubeType,
        @JsonProperty("option") val optionName: String,
        @JsonProperty("rate") val rate: Double,
        @JsonProperty("slot") val slot: Int,
        @JsonProperty("potential_option_grade") val grade: String,
        @JsonProperty("base_equipment_level") val level: Int,
        @JsonProperty("item_equipment_slot") val part: String,
    )

    private companion object {
        const val DEFAULT_RESOURCE_PATH = "data/cube_probability.csv"
        const val LOGICAL_VERSION = "csv-v1.0"
        val CSV_SCHEMA: CsvSchema = CsvSchema.emptySchema().withHeader()
        val CSV_MAPPER: CsvMapper = CsvMapper.builder()
            .addModule(kotlinModule())
            .build()
    }
}
