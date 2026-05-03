package maple.expectation.core.port.out.mq

import maple.expectation.core.domain.event.IntegrationEvent

interface DomainEventAppender {
    fun append(topic: MQTopicGroup, message: IntegrationEvent<*>)
}
