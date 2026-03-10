package maple.expectation.infrastructure.filter

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * 로그 추적용 MDC 필터 (LogicExecutor 평탄화 완료)
 *
 * ## MDC 키
 *
 * - [REQUEST_ID_KEY]: 요청 추적용 Correlation ID
 *
 * ## 비동기 전파
 *
 * `ExecutorConfig.contextPropagatingDecorator()`가 이 MDC 값을 비동기 워커 스레드로 전파합니다.
 *
 * @see maple.expectation.config.ExecutorConfig.contextPropagatingDecorator()
 */
@Component
class MDCFilter(
    private val executor: LogicExecutor,
) : Filter {
    private val logger = LoggerFactory.getLogger(MDCFilter::class.java)

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        // 1. Correlation ID 확보 및 설정 (비즈니스 로직 최상단 노출)
        val correlationId = resolveCorrelationId(httpRequest)
        setupMdcContext(correlationId, httpResponse)

        val context = TaskContext.of("Filter", "MDC", correlationId)

        // ✅ try-finally를 executeWithFinally로 대체하여 평탄화
        executor.executeWithFinally(
            {
                chain.doFilter(request, response)
                null
            },
            { MDC.clear() },
            // 요청 종료 시 반드시 비워줌 (메모리 누수 방지)
            context,
        )
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
