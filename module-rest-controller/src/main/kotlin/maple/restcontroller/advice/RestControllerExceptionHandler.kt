package maple.restcontroller.advice

import maple.expectation.error.dto.ErrorResponse
import maple.expectation.error.exception.base.BaseException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class RestControllerExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BaseException::class)
    fun handleBaseException(ex: BaseException): ResponseEntity<ErrorResponse> {
        log.warn("Business exception: code={} message={}", ex.errorCode.code, ex.message)
        return ResponseEntity
            .status(ex.errorCode.statusCode)
            .body(ErrorResponse.from(ex))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected exception", ex)
        return ResponseEntity
            .status(500)
            .body(ErrorResponse.from(500, "S001", "Internal server error"))
    }
}
