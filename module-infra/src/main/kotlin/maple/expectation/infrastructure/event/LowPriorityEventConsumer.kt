package maple.expectation.infrastructure.event

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LowPriorityEventConsumer(
    logicExecutor: LogicExecutor,
    meterRegistry: MeterRegistry,
    @Value("\${event.consumer.low.max-concurrent:20}") maxConcurrent: Int,
) : IntegrationEventConsumer("low", maxConcurrent, logicExecutor, meterRegistry)
