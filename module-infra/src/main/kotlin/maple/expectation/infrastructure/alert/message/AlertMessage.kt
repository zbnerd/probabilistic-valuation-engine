package maple.expectation.infrastructure.alert.message

/**
 * Alert Message DTO
 *
 * <p>Immutable data transfer object for alert messages
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
data class AlertMessage(
    private val title: String,
    private val message: String,
    private val error: Throwable?,
    private val webhookUrl: String
) {
    fun getTitle(): String = title

    fun getMessage(): String = message

    fun getError(): Throwable? = error

    fun getWebhookUrl(): String = webhookUrl

    fun getFormattedMessage(): String {
        return if (error != null) {
            String.format("**%s**\n```\n%s", message, error.toString())
        } else {
            String.format("**%s**", message)
        }
    }
}
