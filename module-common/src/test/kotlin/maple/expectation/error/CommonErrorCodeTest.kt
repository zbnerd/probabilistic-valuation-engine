package maple.expectation.error

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

class CommonErrorCodeTest {

    @Test
    fun `should have EVENT_HANDLER_ERROR`() {
        // This constant exists in CommonErrorCode enum
        assertNotNull(CommonErrorCode.EVENT_HANDLER_ERROR)
        assertEquals("E001", CommonErrorCode.EVENT_HANDLER_ERROR.code)
    }

    @Test
    fun `should have EVENT_CONSUMER_ERROR`() {
        // This constant exists in CommonErrorCode enum
        assertNotNull(CommonErrorCode.EVENT_CONSUMER_ERROR)
        assertEquals("E002", CommonErrorCode.EVENT_CONSUMER_ERROR.code)
    }
}
