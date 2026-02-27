package maple.expectation.core.application.dto

import java.time.LocalDateTime

/**
 * Base DTO class for all application data transfer objects
 */
abstract class BaseDto {
    open val createdAt: LocalDateTime? = null

    open val updatedAt: LocalDateTime? = null

    open val version: Long? = null

    open fun isNew(): Boolean = version == null

    open fun markAsUpdated() {
        // Note: immutable DTOs - create new instance for updates
    }

    open fun initTimestamps() {
        // Note: immutable DTOs - timestamps set at creation
    }
}
