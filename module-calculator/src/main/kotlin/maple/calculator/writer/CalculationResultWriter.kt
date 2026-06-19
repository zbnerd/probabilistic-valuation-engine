package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import maple.calculator.model.CalculationResult
import maple.expectation.common.storage.ObjectStorage
import org.springframework.stereotype.Component

@Component
class CalculationResultWriter(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
) {

    data class WriteResult(
        val objectKey: String,
        val resultCount: Int,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
    )

    @Suppress("RedundantSuspendModifier")
    suspend fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): WriteResult {
        // Task 9 will rewrite this as a CF chain (Flow → gzip → pipe → putStreamMultipart).
        // Stub for now to keep the file compilable after the nested CountingOutputStream
        // class is removed in this task.
        throw UnsupportedOperationException(
            "CalculationResultWriter.write rewritten in Task 9 of issue #1312"
        )
    }
}
