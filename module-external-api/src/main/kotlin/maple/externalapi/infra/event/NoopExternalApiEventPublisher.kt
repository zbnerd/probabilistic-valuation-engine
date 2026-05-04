package maple.externalapi.infra.event

import maple.externalapi.domain.ExternalApiFetchResult
import maple.externalapi.port.out.ExternalApiEventPublisherPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NoopExternalApiEventPublisher : ExternalApiEventPublisherPort {

    private val log = LoggerFactory.getLogger(NoopExternalApiEventPublisher::class.java)

    override fun publishFetchCompleted(result: ExternalApiFetchResult) {
        log.debug("[EventPublisher:NOOP] fetch completed: endpoint={}, key={}, success={}", result.endpoint, result.requestKey, result.success)
    }
}
