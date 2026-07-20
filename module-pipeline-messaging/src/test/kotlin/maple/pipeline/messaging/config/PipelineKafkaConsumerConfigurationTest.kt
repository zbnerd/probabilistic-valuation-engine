package maple.pipeline.messaging.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ContainerProperties

class PipelineKafkaConsumerConfigurationTest {
    @Test
    fun `migrated factory uses manual immediate acknowledgments without async acks`() {
        val factory = PipelineKafkaConsumerConfiguration().pipelineKafkaListenerContainerFactory(
            mock<ConsumerFactory<String, String>>(),
        )

        assertThat(factory.containerProperties.ackMode).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE)
        assertThat(factory.containerProperties.isAsyncAcks).isFalse()
    }
}
