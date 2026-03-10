package maple.expectation.core.domain

/**
 * 실행 컨텍스트
 * Core 모듈 외부에서 전달되는 실행 환경 정보
 */
data class ExecutionContext(
    val correlationId: String,
    val userId: String?,
    val timestamp: Long,
    val metadata: Map<String, Any> = emptyMap(),
)
