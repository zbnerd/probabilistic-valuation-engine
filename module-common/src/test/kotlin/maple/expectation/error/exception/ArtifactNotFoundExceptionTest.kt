package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArtifactNotFoundExceptionTest {

    @Test
    fun `constructs with error code, cause, and varargs`() {
        val cause = RuntimeException("disk gone")
        val ex = ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, cause, "CHARACTER_BASIC", "abc123")

        assertThat(ex.errorCode).isEqualTo(CommonErrorCode.ARTIFACT_NOT_FOUND)
        assertThat(ex.cause).isSameAs(cause)
        assertThat(ex.message).contains("CHARACTER_BASIC").contains("abc123")
    }

    @Test
    fun `inherits from ServerBaseException`() {
        val ex = ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ITEM_EQUIPMENT", "xyz")
        assertThat(ex).isInstanceOf(maple.expectation.error.exception.base.ServerBaseException::class.java)
    }
}
