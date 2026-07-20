package maple.pipeline.artifact.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ArtifactLayoutGoldenTest {
    @Test
    fun `source layout preserves exact legacy keys`() {
        assertThat(SourceArtifactLayout.runRoot("20260719-120000-1").value)
            .isEqualTo("runs/20260719-120000-1")
        assertThat(SourceArtifactLayout.endpointRoot("r1", "item-equipment").value)
            .isEqualTo("runs/r1/item-equipment")
        assertThat(SourceArtifactLayout.chunk("r1", "item-equipment", "part-000001").value)
            .isEqualTo("runs/r1/item-equipment/chunks/part-000001.jsonl.gz")
        assertThat(SourceArtifactLayout.manifest("r1", "item-equipment").value)
            .isEqualTo("runs/r1/item-equipment/manifest.json")
        assertThat(SourceArtifactLayout.failedRecords("r1", "item-equipment").value)
            .isEqualTo("runs/r1/item-equipment/failed.jsonl")
        assertThat(SourceArtifactLayout.legacyRankingRunning("r1").value)
            .isEqualTo("runs/r1/_RUNNING")
        assertThat(SourceArtifactLayout.endpointRunning("r1", "character-basic").value)
            .isEqualTo("runs/r1/character-basic/_RUNNING")
        assertThat(SourceArtifactLayout.endpointSuccess("r1", "character-basic").value)
            .isEqualTo("runs/r1/character-basic/_SUCCESS")
    }

    @Test
    fun `calculator OCID and cleanup layouts preserve exact legacy keys`() {
        assertThat(CalculatorArtifactLayout.runRoot("r1").value)
            .isEqualTo("calculator/runs/r1")
        assertThat(CalculatorArtifactLayout.resultChunk("r1", "item-equipment", "part-000001").value)
            .isEqualTo("calculator/runs/r1/item-equipment/chunks/result-part-000001.jsonl.gz")
        assertThat(OcidMappingArtifactLayout.mapping("r1").value)
            .isEqualTo("ocid-mapping/ocid-mapping-r1.jsonl.gz")
        assertThat(OcidMappingArtifactLayout.parquetSidecar("r1").value)
            .isEqualTo("ocid-mapping-parquet/ocid-mapping-r1.parquet")
        assertThat(CleanupInboxLayout.entry("event-1").value)
            .isEqualTo("cleanup/inbox/event-1.json")
    }

    @Test
    fun `layout prefixes preserve exact legacy roots`() {
        assertThat(SourceArtifactLayout.runPrefix.value).isEqualTo("runs/")
        assertThat(CalculatorArtifactLayout.runPrefix.value).isEqualTo("calculator/runs/")
        assertThat(CleanupInboxLayout.prefix.value).isEqualTo("cleanup/inbox/")
    }

    @Test
    fun `replay event IDs preserve exact UUIDv5 fixtures`() {
        assertThat(
            ArtifactReplayEventId.forChunk(
                "SNAPSHOT_CHUNK_READY",
                "r1",
                "item-equipment",
                "part-000001",
            ).toString(),
        ).isEqualTo("89656389-43bb-5b93-b042-8cd4e66290fc")
        assertThat(
            ArtifactReplayEventId.forRun(
                "SNAPSHOT_RUN_COMPLETED",
                "r1",
                "item-equipment",
            ).toString(),
        ).isEqualTo("e1b7bcf0-1246-543c-926b-ab91ef37a635")
    }

    @Test
    fun `replay event IDs are deterministic across recovery attempts`() {
        val firstChunkAttempt = ArtifactReplayEventId.forChunk(
            "SNAPSHOT_CHUNK_READY",
            "r1",
            "item-equipment",
            "part-000001",
        )
        val secondChunkAttempt = ArtifactReplayEventId.forChunk(
            "SNAPSHOT_CHUNK_READY",
            "r1",
            "item-equipment",
            "part-000001",
        )
        val firstRunAttempt = ArtifactReplayEventId.forRun(
            "SNAPSHOT_RUN_COMPLETED",
            "r1",
            "item-equipment",
        )
        val secondRunAttempt = ArtifactReplayEventId.forRun(
            "SNAPSHOT_RUN_COMPLETED",
            "r1",
            "item-equipment",
        )

        assertThat(secondChunkAttempt).isEqualTo(firstChunkAttempt)
        assertThat(secondRunAttempt).isEqualTo(firstRunAttempt)
    }

    @Test
    fun `layouts reject slash-bearing dynamic segments`() {
        assertThatThrownBy { SourceArtifactLayout.runRoot("bad/run") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SourceArtifactLayout.endpointRoot("r1", "bad/endpoint") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SourceArtifactLayout.chunk("r1", "item-equipment", "bad/chunk") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CalculatorArtifactLayout.resultChunk("bad/run", "item-equipment", "part-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OcidMappingArtifactLayout.mapping("bad/run") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CleanupInboxLayout.entry("bad/event") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `replay event IDs reject every slash-bearing dynamic segment`() {
        assertThatThrownBy {
            ArtifactReplayEventId.forChunk("SNAPSHOT/CHUNK", "r1", "item-equipment", "part-1")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ArtifactReplayEventId.forChunk("SNAPSHOT_CHUNK_READY", "bad/run", "item-equipment", "part-1")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ArtifactReplayEventId.forChunk("SNAPSHOT_CHUNK_READY", "r1", "bad/endpoint", "part-1")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ArtifactReplayEventId.forChunk("SNAPSHOT_CHUNK_READY", "r1", "item-equipment", "bad/chunk")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ArtifactReplayEventId.forRun("SNAPSHOT/RUN", "r1", "item-equipment")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ArtifactReplayEventId.forRun("SNAPSHOT_RUN_COMPLETED", "bad/run", "item-equipment")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ArtifactReplayEventId.forRun("SNAPSHOT_RUN_COMPLETED", "r1", "bad/endpoint")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
