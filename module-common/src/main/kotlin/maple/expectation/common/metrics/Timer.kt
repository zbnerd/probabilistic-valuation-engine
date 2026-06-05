package maple.expectation.common.metrics

import java.time.Duration

/**
 * Technology-neutral timer.
 *
 * <p>Adapters wrap Micrometer Timer, Dropwizard Timer, etc.
 */
interface Timer {
    fun record(duration: Duration)
}
