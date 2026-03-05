package maple.expectation.infrastructure.executor.classifier

import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.error.exception.ExternalServiceException
import maple.expectation.error.exception.base.ClientBaseException
import maple.expectation.error.exception.base.ServerBaseException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExceptionClassifier].
 *
 * <p><strong>Test Coverage (ADR-008):</strong>
 *
 * <ul>
 *   <li>ClientBaseException classification → IGNORE
 *   <li>ServerBaseException classification → RECORD
 *   <li>Unknown exception classification → DEFAULT
 *   <li>Concrete exception subclasses classification
 * </ul>
 *
 * @see ExceptionClassifier
 * @see DefaultExceptionClassifier
 * @see CircuitBreakerClassification
 */
@DisplayName("ExceptionClassifier Tests")
class ExceptionClassifierTest {

    private val classifier: ExceptionClassifier = DefaultExceptionClassifier()

    @Nested
    @DisplayName("ClientBaseException Classification")
    inner class ClientBaseExceptionClassification {

        @Test
        @DisplayName("ClientBaseException subclass should be classified as IGNORE")
        fun `CharacterNotFoundException should be classified as IGNORE`() {
            // Given
            val exception = CharacterNotFoundException("testOcid")

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.IGNORE)
        }

        @Test
        @DisplayName("Direct ClientBaseException should be classified as IGNORE")
        fun `ClientBaseException should be classified as IGNORE`() {
            // Given
            val exception = object : ClientBaseException(
                maple.expectation.error.CommonErrorCode.INVALID_INPUT_VALUE
            ) {}

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.IGNORE)
        }
    }

    @Nested
    @DisplayName("ServerBaseException Classification")
    inner class ServerBaseExceptionClassification {

        @Test
        @DisplayName("ServerBaseException subclass should be classified as RECORD")
        fun `ExternalServiceException should be classified as RECORD`() {
            // Given
            val exception = ExternalServiceException("TestService", RuntimeException("Connection failed"))

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.RECORD)
        }

        @Test
        @DisplayName("Direct ServerBaseException should be classified as RECORD")
        fun `ServerBaseException should be classified as RECORD`() {
            // Given
            val exception = object : ServerBaseException(
                maple.expectation.error.CommonErrorCode.INTERNAL_SERVER_ERROR, "test error",
                RuntimeException("Test error")
            ) {}

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.RECORD)
        }
    }

    @Nested
    @DisplayName("Unknown Exception Classification")
    inner class UnknownExceptionClassification {

        @Test
        @DisplayName("RuntimeException should be classified as DEFAULT")
        fun `RuntimeException should be classified as DEFAULT`() {
            // Given
            val exception = RuntimeException("Unknown error")

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.DEFAULT)
        }

        @Test
        @DisplayName("IllegalArgumentException should be classified as DEFAULT")
        fun `IllegalArgumentException should be classified as DEFAULT`() {
            // Given
            val exception = IllegalArgumentException("Invalid argument")

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.DEFAULT)
        }

        @Test
        @DisplayName("NullPointerException should be classified as DEFAULT")
        fun `NullPointerException should be classified as DEFAULT`() {
            // Given
            val exception = NullPointerException("Null reference")

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.DEFAULT)
        }

        @Test
        @DisplayName("IOException wrapped in RuntimeException should be classified as DEFAULT")
        fun `wrapped checked exception should be classified as DEFAULT`() {
            // Given
            val exception = RuntimeException("IO Error", java.io.IOException("Connection reset"))

            // When
            val classification = classifier.classify(exception)

            // Then
            assertThat(classification).isEqualTo(CircuitBreakerClassification.DEFAULT)
        }
    }

    @Nested
    @DisplayName("Classification Consistency")
    inner class ClassificationConsistency {

        @Test
        @DisplayName("Multiple classifications of same exception should return consistent result")
        fun `multiple classifications should be consistent`() {
            // Given
            val exception = CharacterNotFoundException("testOcid")

            // When
            val classification1 = classifier.classify(exception)
            val classification2 = classifier.classify(exception)
            val classification3 = classifier.classify(exception)

            // Then
            assertThat(classification1).isEqualTo(classification2)
            assertThat(classification2).isEqualTo(classification3)
            assertThat(classification1).isEqualTo(CircuitBreakerClassification.IGNORE)
        }
    }
}
