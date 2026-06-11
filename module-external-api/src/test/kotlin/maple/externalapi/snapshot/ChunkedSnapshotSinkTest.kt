package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ChunkedSnapshotSinkTest {

    private val objectMapper = ObjectMapper().registerModule(kotlinModule())

    @Test
    fun `submit throws after writer thread dies from Error, not Exception`() {
        // Reproduces the production symptom: writer thread dies from
        // an Error (e.g. OOMError under heap pressure) and a subsequent
        // submit() call observes writerFuture.isDone = true before the
        // runWriterLoop catch can set writerError. Before the fix the
        // submit throws a vague "writer thread is not alive" message
        // and the original OOMError is lost. After the fix the writer
        // catch clause widens to Throwable so the original error is
        // propagated as "sink closed due to writer error: ...".

        val appendInvoked = CountDownLatch(1)
        val capturedBodyBytes = AtomicReference<ByteArray>()

        val fileManager = mock<ChunkFileManager>()
        whenever(fileManager.appendSuccess(any())).thenAnswer { invocation ->
            val record = invocation.getArgument<SnapshotChunkRecord.Success>(0)
            capturedBodyBytes.set(record.bodyBytes)
            appendInvoked.countDown()
            // Throw an Error (NOT Exception) — simulates heap pressure
            // or similar unrecoverable condition. The old catch (ex: Exception)
            // would NOT catch this and the thread would die silently.
            throw OutOfMemoryError("simulated writer OOM")
        }

        val eventPublisher = mock<SnapshotSinkEventPublisher>()

        val sink = ChunkedSnapshotSink(
            endpoint = "item-equipment",
            queueCapacity = 100,
            fileManager = fileManager,
            eventPublisher = eventPublisher,
        )

        // First submit succeeds (queue.offer, writer picks it up).
        val body = objectMapper.writeValueAsBytes(
            mapOf("userIgn" to "user-1", "ocid" to "ocid-1")
        )
        sink.submit(
            SnapshotChunkRecord.Success(
                key = "user-1",
                endpoint = "item-equipment",
                keyType = "OCID",
                httpStatus = 200,
                fetchedAt = Instant.parse("2026-06-11T00:00:00Z"),
                bodyBytes = body,
            )
        )

        // Wait for writer to start processing the first record (it will then
        // throw OutOfMemoryError, which under the bug kills the thread
        // silently without setting writerError or accepting=false).
        assertTrue(
            appendInvoked.await(2, TimeUnit.SECONDS),
            "writer thread should have invoked fileManager.appendSuccess",
        )

        // Wait for the writer Future to be done (thread exited).
        val writerField = ChunkedSnapshotSink::class.java.getDeclaredField("writerFuture")
            .apply { isAccessible = true }
        val writerFuture = writerField.get(sink) as java.util.concurrent.Future<*>
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && !writerFuture.isDone) Thread.sleep(20)
        assertTrue(writerFuture.isDone, "writer thread should have terminated")

        // Second submit: under the BUG this throws
        //   IllegalStateException("sink writer thread is not alive")
        // because writerError stayed null (the catch never saw the OOM)
        // and accepting stayed true. After the FIX it should throw
        //   IllegalStateException("sink closed due to writer error: simulated writer OOM")
        // because the writer's Throwable catch records the error and flips
        // accepting to false.
        val ex = assertThrows(IllegalStateException::class.java) {
            sink.submit(
                SnapshotChunkRecord.Success(
                    key = "user-2",
                    endpoint = "item-equipment",
                    keyType = "OCID",
                    httpStatus = 200,
                    fetchedAt = Instant.parse("2026-06-11T00:00:00Z"),
                    bodyBytes = objectMapper.writeValueAsBytes(
                        mapOf("userIgn" to "user-2", "ocid" to "ocid-2")
                    ),
                )
            )
        }
        val message = ex.message ?: ""
        // The original OOM must surface in the message — not just a vague
        // "writer thread is not alive". This is what the fix enables.
        assertTrue(
            message.contains("writer thread is not alive").not() &&
                (message.contains("simulated writer OOM") || message.contains("sink closed due to writer error")),
            "expected submit() to surface the underlying OOMError, got: $message",
        )
    }
}
