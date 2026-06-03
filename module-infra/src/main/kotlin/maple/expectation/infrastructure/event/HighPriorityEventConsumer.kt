package maple.expectation.infrastructure.event

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class HighPriorityEventConsumer(
    logicExecutor: LogicExecutor,
    meterRegistry: MeterRegistry,
    @Value("\${event.consumer.high.max-concurrent:50}") maxConcurrent: Int,
) : IntegrationEventConsumer("high", maxConcurrent, logicExecutor, meterRegistry)
