package maple.expectation.infrastructure.external.snapshot

import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.core.model.snapshot.CalculationSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class SnapshotObjectStoreAdapterTest {

    private fun snapshot(objectKey: String = "snapshots/2026/06/09/${UUID.randomUUID()}.gz") =
        CalculationSnapshot(
            snapshotId = UUID.randomUUID(),
            jobId = UUID.randomUUID(),
            objectKey = objectKey,
            storageType = "LOCAL",
            characterId = "ocid-123",
            presetNo = 1,
            expiresAt = Instant.now().plusSeconds(3600),
        )

    @Test
    fun `put delegates to ObjectStorage put with the snapshot's objectKey`() {
        val objectStorage: ObjectStorage = mock()
        val adapter = SnapshotObjectStoreAdapter(objectStorage, "local")
        whenever(objectStorage.put(eq("snapshots/k.gz"), any()))
            .thenReturn(PutResult("snapshots/k.gz", 100L, "abc123"))
        val snap = snapshot("snapshots/k.gz")
        val data = "payload".toByteArray()

        val result = adapter.put(snap, data)

        assertThat(result.objectKey).isEqualTo("snapshots/k.gz")
        assertThat(result.compressedSize).isEqualTo(100L)
        assertThat(result.hash).isEqualTo("abc123")
        verify(objectStorage).put("snapshots/k.gz", data)
    }

    @Test
    fun `get delegates to ObjectStorage get`() {
        val objectStorage: ObjectStorage = mock()
        val adapter = SnapshotObjectStoreAdapter(objectStorage, "local")
        whenever(objectStorage.get("k")).thenReturn("data".toByteArray())

        val result = adapter.get("k")

        assertThat(result).isEqualTo("data".toByteArray())
    }

    @Test
    fun `delete delegates to ObjectStorage delete`() {
        val objectStorage: ObjectStorage = mock()
        val adapter = SnapshotObjectStoreAdapter(objectStorage, "local")

        adapter.delete("k")

        verify(objectStorage).delete("k")
    }
}
