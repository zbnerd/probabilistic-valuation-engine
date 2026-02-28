package maple.expectation.core.domain.model

/**
 * Cube type enum representing different cube categories in MapleStory.
 *
 * Pure domain model - no external dependencies.
 */
enum class CubeType(val description: String) {
    /** Black Cube - resets potential options */
    BLACK("블랙큐브"),

    /** Red Cube - resets potential options with higher chances for legendary */
    RED("레드큐브"),

    /** Additional Cube - resets additional potential options */
    ADDITIONAL("에디셔널큐브")
}
