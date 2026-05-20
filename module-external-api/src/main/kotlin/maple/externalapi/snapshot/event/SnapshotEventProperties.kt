package maple.externalapi.snapshot.event

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "external-api.snapshot.events")
data class SnapshotEventProperties(
    val enabled: Boolean = true,
    val kafka: KafkaConfig = KafkaConfig(),
) {
    data class KafkaConfig(
        val enabled: Boolean = false,
        val chunkReadyTopic: String = "external-api.snapshot.chunk-ready",
        val runCompletedTopic: String = "external-api.snapshot.run-completed",
        val runFailedTopic: String = "external-api.snapshot.run-failed",
        val ocidLookupTopic: String = "external-api.ocid.lookup-ready",
    )
}
