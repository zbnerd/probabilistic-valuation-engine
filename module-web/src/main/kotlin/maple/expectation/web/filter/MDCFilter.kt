package maple.expectation.web.filter

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import maple.expectation.common.executor.TaskContext
import maple.expectation.core.port.inbound.ExecutorPort
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * 로그 추적용 MDC 필터 (ExecutorPort 사용 - Issue #639 DIP 위반 해결)
 *
 * ## MDC 키
 *
 * - [REQUEST_ID_KEY]: 요청 추적용 Correlation ID
 *
 * ## 비동기 전파
 *
 * `ExecutorConfig.contextPropagatingDecorator()`가 이 MDC 값을 비동기 워커 스레드로 전파합니다.
 */
@Component
class MDCFilter(
    private val executorPort: ExecutorPort,
) : Filter {
    private val logger = LoggerFactory.getLogger(MDCFilter::class.java)

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        // 1. Correlation ID 확보 및 설정 (비즈니스 로직 최상단 노출)
        val correlationId = resolveCorrelationId(httpRequest)
        setupMdcContext(correlationId, httpResponse)

        val context = TaskContext.of("Filter", "MDC", correlationId)

        // ExecutorPort의 executeWithFinally 패턴 사용
        try {
            executorPort.executeVoid(
                {
                    // Spring RequestContextHolder 설정 (비동기 컨텍스트 전파)
                    RequestContextHolder.setRequestAttributes(ServletRequestAttributes(httpRequest))
                    chain.doFilter(request, response)
                },
                context,
            )
        } finally {
            MDC.clear()
            RequestContextHolder.resetRequestAttributes()
        }
    }

    /** 외부 헤더 확인 후 없으면 생성 (로직 분리) */
    private fun resolveCorrelationId(request: HttpServletRequest): String {
        val id = request.getHeader(CORRELATION_ID_HEADER)
        return if (id.isNullOrBlank()) UUID.randomUUID().toString() else id
    }

    /** MDC 주입 및 응답 헤더 설정 */
    private fun setupMdcContext(correlationId: String, response: HttpServletResponse) {
        MDC.put(REQUEST_ID_KEY, correlationId)
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
    }

    private companion object {
        /** HTTP 헤더 이름: X-Correlation-ID */
        const val CORRELATION_ID_HEADER = "X-Correlation-ID"

        /** MDC 키: requestId (비동기 전파 대상) */
        const val REQUEST_ID_KEY = "requestId"
    }
}
