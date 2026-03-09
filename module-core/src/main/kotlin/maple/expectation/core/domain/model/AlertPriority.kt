package maple.expectation.core.domain.model

/**
 * Alert priority enum.
 *
 * Represents the priority level of an alert.
 *
 * Pure domain model - no external dependencies.
 */
enum class AlertPriority {
    /** High priority - critical issues requiring immediate attention */
    HIGH,

    /** Medium priority - important issues that should be addressed soon */
    MEDIUM,

    /** Low priority - informational messages */
    LOW,
}
