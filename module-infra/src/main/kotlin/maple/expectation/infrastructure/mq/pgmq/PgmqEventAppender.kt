package maple.expectation.infrastructure.mq.pgmq

import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.core.port.out.mq.MQTopicGroup
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PgmqEventAppender : DomainEventAppender {

    @Transactional
    override fun append(topic: MQTopicGroup, message: IntegrationEvent<*>) {
        topic.publish(message)
    }
}
