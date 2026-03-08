package maple.expectation.core.port.out

/**
 * Port for event processing from EventOutbox.
 *
 * <p>Dedicated interface for EventOutboxProcessor to avoid bean ambiguity
 * with DonationOutboxProcessor (which implements OutboxProcessorPort).
 *
 * @see maple.expectation.infrastructure.event.outbox.EventOutboxProcessor
 */
interface EventProcessorPort {
    /**
     * Poll and process pending events from EventOutbox.
     *
     * @return number of events processed
     */
    fun pollAndProcess(): Int

    /**
     * Recover stalled events that are stuck in PROCESSING status.
     */
    fun recoverStalled()
}
