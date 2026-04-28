package maple.expectation.core.port.out.mq

import maple.expectation.core.domain.event.IntegrationEvent

interface MQTopicGroup {
    val name: String

    fun publish(message: IntegrationEvent<*>): MessageHandle

    fun subscribe(handler: (IntegrationEvent<*>, MessageHandle) -> ConsumeResult)
}
