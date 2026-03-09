package maple.expectation.core.port.out

/**
 * Alert Publisher Interface (ADR-0345)
 *
 * <h3>Role</h3>
 *
 * <p>Defines the contract for publishing alerts. Implementations can send alerts to various
 * channels (Discord, Slack, etc.) without the infrastructure layer depending on the application
 * layer.
 *
 * <h3>DIP Compliance</h3>
 *
 * <p>Infrastructure modules depend on this abstraction rather than concrete alert services in the
 * application layer.
 *
 * <h3>Implementations</h3>
 *
 * <ul>
 *   <li>maple.expectation.alert.StatelessAlertService - Stateless implementation
 * </ul>
 */
interface AlertPublisher {

    /**
     * Send a critical alert
     *
     * <p>Critical alerts should bypass all stateful dependencies (Redis/DB) and be delivered
     * immediately.
     *
     * @param title Alert title
     * @param message Alert message
     * @param error Throwable that caused the alert (may be null)
     */
    fun sendCritical(title: String, message: String, error: Throwable?)

    /**
     * Send a warning alert
     *
     * @param title Alert title
     * @param message Alert message
     * @param error Throwable that caused the alert (may be null)
     */
    fun sendWarning(title: String, message: String, error: Throwable?) {
        sendCritical(title, message, error)
    }

    /**
     * Send an info alert
     *
     * @param title Alert title
     * @param message Alert message
     */
    fun sendInfo(title: String, message: String) {
        sendCritical(title, message, null)
    }
}
