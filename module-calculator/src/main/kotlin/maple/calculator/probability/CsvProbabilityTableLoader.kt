package maple.calculator.probability

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
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource

class CsvProbabilityTableLoader(
    private val resource: Resource = ClassPathResource(DEFAULT_RESOURCE_PATH),
    private val logicalVersion: String = LOGICAL_VERSION,
) {
    @Volatile
    var lastObservation: LoadObservation? = null
        private set

    fun load(): ProbabilityTableSnapshot {
        val startedAt = System.nanoTime()
        return runCatching { loadSnapshot() }
            .onSuccess { snapshot ->
                lastObservation = LoadObservation(
                    durationNanos = (System.nanoTime() - startedAt).coerceAtLeast(1L),
                    rowCount = snapshot.rowCount,
                    versionLabel = snapshot.version.logical,
                )
            }
            .getOrElse { cause ->
                throw ProbabilityTableInitializationException(
                    "Failed to initialize probability table from ${resource.description}",
                    cause,
                )
            }
    }

    private fun loadSnapshot(): ProbabilityTableSnapshot {
        val version = ProbabilityTableVersion(logicalVersion, calculateSha256())
        val builder = ProbabilityTableSnapshot.builder(version)
        var rowCount = 0

        resource.inputStream.use { input ->
            csvMapper.readerFor(CsvProbabilityRow::class.java)
                .with(CSV_SCHEMA)
                .readValues<CsvProbabilityRow>(input)
                .use { rows ->
                    while (rows.hasNextValue()) {
                        val csvRow = rows.nextValue()
                        builder.add(
                            ProbabilityKey(
                                cubeType = csvRow.cubeType,
                                level = csvRow.level,
                                part = csvRow.part,
                                grade = csvRow.grade,
                                slot = csvRow.slot,
                            ),
                            ProbabilityRow(csvRow.optionName, csvRow.rate),
                        )
                        rowCount++
                    }
                }
        }

        require(rowCount > 0) { "Probability CSV contains no data rows" }
        return builder.build()
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

    data class LoadObservation(
        val durationNanos: Long,
        val rowCount: Int,
        val versionLabel: String,
    )

    private companion object {
        const val DEFAULT_RESOURCE_PATH = "data/cube_probability.csv"
        const val LOGICAL_VERSION = "csv-v1.0"
        val CSV_SCHEMA: CsvSchema = CsvSchema.emptySchema().withHeader()
        val csvMapper: CsvMapper = CsvMapper.builder()
            .addModule(kotlinModule())
            .build()
    }
}
