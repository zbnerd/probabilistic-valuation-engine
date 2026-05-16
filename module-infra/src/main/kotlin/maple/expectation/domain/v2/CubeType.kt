package maple.expectation.domain.v2

/**
 * 큐브 종류 Enum
 */
enum class CubeType(val description: String) {
    BLACK("블랙큐브"),
    RED("레드큐브"),
    ADDITIONAL("에디셔널큐브"),
    ;

    /** Convert to core CubeType */
    fun toCore(): maple.expectation.core.domain.model.CubeType = when (this) {
        BLACK -> maple.expectation.core.domain.model.CubeType.BLACK
        RED -> maple.expectation.core.domain.model.CubeType.RED
        ADDITIONAL -> maple.expectation.core.domain.model.CubeType.ADDITIONAL
    }
}
