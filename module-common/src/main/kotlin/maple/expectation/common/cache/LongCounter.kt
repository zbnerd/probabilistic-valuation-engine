package maple.expectation.common.cache

/**
 * Technology-neutral monotonic counter.
 *
 * <p>Adapters wrap Micrometer Counter, Dropwizard Meter, etc.
 */
interface LongCounter {
    fun increment()
    fun increment(delta: Long)
}
