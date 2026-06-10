package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.externalapi.domain.KeyType
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Migration Task 9: `createForXxx(runKey)` must propagate the runKey down to
 * the `ChunkFileManager` so chunk keys live under the supplied object-storage prefix.
 */
class EndpointSinkFactoryTest {

    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `createForCharacterBasic produces a sink whose chunk keys live under the supplied runKey`() {
        val storage = mock<ObjectStorage>()
        val keyCaptor = argumentCaptor<String>()
        whenever(storage.put(keyCaptor.capture(), any<ByteArray>()))
            .thenAnswer { invocation ->
                val key: String = invocation.getArgument(0)
                PutResult(key, 0L, null)
            }

        val characterBasicPublisher = mock<SnapshotChunkEventPublisher>()
        val rankingPublisher = mock<SnapshotChunkEventPublisher>()
        val chunkingProperties = SnapshotChunkingProperties()
        val volumeMetrics = mock<SnapshotVolumeMetrics>()

        val factory = EndpointSinkFactory(
            objectMapper = objectMapper,
            chunkingProperties = chunkingProperties,
            volumeMetrics = volumeMetrics,
            characterBasicPublisher = characterBasicPublisher,
            rankingPublisher = rankingPublisher,
            objectStorage = storage,
            clock = Clock.systemUTC(),
        )

        val runKey = "runs/test-run/character-basic"
        val sink = factory.createForCharacterBasic(runKey)

        try {
            sink.submit(
                SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(mapOf("k" to "v0")),
                    key = "k0",
                    endpoint = "character-basic",
                    keyType = KeyType.OCID.name,
                    httpStatus = 200,
                    fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
                ),
            )
            sink.close()
        } catch (ex: Exception) {
            // close() may throw a RuntimeException if no publisher is wired;
            // we only care that the chunk key was put under runKey.
        }

        val capturedKeys = keyCaptor.allValues
        assertThat(capturedKeys).isNotEmpty
        assertThat(capturedKeys).allMatch { it.startsWith("$runKey/") }
    }
}
