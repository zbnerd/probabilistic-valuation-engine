package maple.expectation.infrastructure.concurrency

class BackpressureRejectedException(val component: String, timeoutMs: Long) :
    RuntimeException("Backpressure timeout after ${timeoutMs}ms in $component")
