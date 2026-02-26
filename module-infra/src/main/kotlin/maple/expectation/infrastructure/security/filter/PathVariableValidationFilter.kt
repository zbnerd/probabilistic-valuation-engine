package maple.expectation.infrastructure.security.filter

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.io.IOException

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class PathVariableValidationFilter : Filter {

    private companion object {
        const val ERROR_RESPONSE = "{\"status\":400,\"code\":\"BAD_REQUEST\",\"message\":\"유효하지 않은 요청 파라미터입니다.\"}"
    }

    @Throws(IOException::class, ServletException::class)
    override fun doFilter(request: ServletRequest, response: ServletResponse, filterChain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        val requestUri = httpRequest.requestURI

        if (requestUri.contains("//")) {
            respondWithBadRequest(httpResponse)
            return
        }

        if (isEmptyUserIgnInPath(requestUri)) {
            respondWithBadRequest(httpResponse)
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun isEmptyUserIgnInPath(requestUri: String): Boolean {
        if (requestUri.matches("^/api/v4/characters//expectation(/preset/\\d+)?$".toRegex())) {
            return true
        }

        if (requestUri.startsWith("/api/v3/characters//")) {
            return true
        }

        if (requestUri.startsWith("/api/v2/characters//")) {
            return true
        }

        return false
    }

    @Throws(IOException::class)
    private fun respondWithBadRequest(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_BAD_REQUEST
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(ERROR_RESPONSE)
    }
}
