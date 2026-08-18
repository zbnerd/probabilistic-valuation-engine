package maple.pipeline.artifact.write

import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.Deflater
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.storage.ConditionalObjectStorage

interface ArtifactWriter {
    fun openGzip(key: ArtifactKey): GzipArtifactSession
}

class DefaultArtifactWriter(
    private val objectStorage: ConditionalObjectStorage,
    private val uploadExecutor: Executor,
    private val compressionLevel: Int = Deflater.BEST_SPEED,
) : ArtifactWriter {
    init {
        require(compressionLevel in Deflater.NO_COMPRESSION..Deflater.BEST_COMPRESSION) {
            "gzip compression level must be between 0 and 9"
        }
    }

    override fun openGzip(key: ArtifactKey): GzipArtifactSession {
        val tempFile = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX)
        return runCatching {
            DefaultGzipArtifactSession(
                key = key,
                tempFile = tempFile,
                objectStorage = objectStorage,
                uploadExecutor = uploadExecutor,
                compressionLevel = compressionLevel,
            )
        }.getOrElse { failure ->
            runCatching { Files.deleteIfExists(tempFile) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    private companion object {
        const val TEMP_FILE_PREFIX: String = "artifact-gzip-"
        const val TEMP_FILE_SUFFIX: String = ".jsonl.gz.tmp"
    }
}
