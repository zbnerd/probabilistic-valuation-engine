package maple.pipeline.messaging.config

import java.nio.charset.StandardCharsets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer

class AsyncAcksScopeCharacterizationTest {
    @Test
    fun `Spring Kafka 3_3_8 async acks tracks poll-wide pending offsets and pauses the child consumer`() {
        val implementationVersion = KafkaMessageListenerContainer::class.java.`package`.implementationVersion
        val listenerConsumerClass = KafkaMessageListenerContainer::class.java.declaredClasses
            .single { nested -> nested.simpleName == "ListenerConsumer" }
        val classResource = "/${listenerConsumerClass.name.replace('.', '/')}.class"
        val bytecode = listenerConsumerClass
            .getResourceAsStream(classResource)
            ?.readAllBytes()
            ?.toString(StandardCharsets.ISO_8859_1)
            .orEmpty()

        assertThat(implementationVersion).isEqualTo("3.3.8")
        assertThat(listenerConsumerClass.declaredFields.map { field -> field.name })
            .contains("pausedForAsyncAcks", "offsetsInThisBatch", "deferredOffsets")
        assertThat(listenerConsumerClass.declaredMethods.map { method -> method.name })
            .contains("doPauseConsumerIfNecessary", "ackInOrder")
        assertThat(bytecode).contains("doPauseConsumerIfNecessary", "pausedForAsyncAcks")

        val properties = ContainerProperties("topic")
        properties.isAsyncAcks = true
        assertThat(properties.isAsyncAcks).isTrue()
    }
}
