package maple.nexon.client.metrics

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import maple.nexon.client.config.NexonClientProfile
import maple.nexon.client.failure.NexonFailure
import maple.nexon.client.model.NexonRequest

class NexonClientMetrics(
    private val meterRegistry: MeterRegistry?,
) {
    fun recordSuccess(
        profile: NexonClientProfile,
        request: NexonRequest,
        duration: Duration,
        bodyBytes: Int,
    ) {
        val registry = meterRegistry ?: return
        registry.counter(
            "nexon.client.requests",
            "profile",
            profile.name,
            "endpoint",
            request.endpointTemplate,
            "outcome",
            "success",
        ).increment()
        registry.timer(
            "nexon.client.duration",
            "profile",
            profile.name,
            "endpoint",
            request.endpointTemplate,
        ).record(duration)
        registry.summary(
            "nexon.client.response.bytes",
            "profile",
            profile.name,
            "endpoint",
            request.endpointTemplate,
        ).record(bodyBytes.toDouble())
    }

    fun recordFailure(
        profile: NexonClientProfile,
        request: NexonRequest,
        duration: Duration,
        failure: NexonFailure,
    ) {
        val registry = meterRegistry ?: return
        registry.counter(
            "nexon.client.requests",
            "profile",
            profile.name,
            "endpoint",
            request.endpointTemplate,
            "outcome",
            failure.javaClass.simpleName,
        ).increment()
        registry.timer(
            "nexon.client.duration",
            "profile",
            profile.name,
            "endpoint",
            request.endpointTemplate,
        ).record(duration)
    }

    fun recordResourceDisposalFailure() {
        meterRegistry?.counter("nexon.client.provider.disposal.failures")?.increment()
    }
}
