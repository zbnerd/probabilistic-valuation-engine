package maple.expectation.core.domain.model

/**
 * Alert message domain model.
 *
 * Represents an alert notification message.
 *
 * Pure domain model - no external dependencies.
 *
 * @property title the alert title
 * @property message the alert message content
 * @property error the associated error (optional)
 */
data class AlertMessage(
    val title: String,
    val message: String,
    val error: Throwable? = null
) {
    init {
        require(title.isNotBlank()) { "title cannot be null or blank" }
        require(message.isNotBlank()) { "message cannot be null or blank" }
    }

    /**
     * Get formatted message with error details if present.
     *
     * @return the formatted message
     */
    fun getFormattedMessage(): String {
        return if (error != null) {
            String.format("**%s**\n```\n%s", message, error.toString())
        } else {
            String.format("**%s**", message)
        }
    }

    companion object {
        /**
         * Create an alert message without error.
         */
        @JvmStatic
        fun of(title: String, message: String): AlertMessage {
            return AlertMessage(title, message, null)
        }

        /**
         * Create an alert message with error.
         */
        @JvmStatic
        fun withError(title: String, message: String, error: Throwable): AlertMessage {
            return AlertMessage(title, message, error)
        }
    }
}
