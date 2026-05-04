package maple.externalapi.port.out

import maple.externalapi.domain.ExternalApiFetchResult

interface ExternalApiEventPublisherPort {

    fun publishFetchCompleted(result: ExternalApiFetchResult)
}
