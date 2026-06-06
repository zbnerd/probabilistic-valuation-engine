package maple.expectation.infrastructure.monitoring.ai

/**
 * Stateless rule-based SRE analyzer. The 4 helpers here are pure functions —
 * no field dependencies, no Spring beans — so they live in a singleton object.
 *
 * Separated from [AiSreService] which owns the LLM-based path. The LLM path
 * delegates here when the model is unavailable or returns low confidence.
 */
internal object RuleBasedAnalyzer {
    fun analyzeByKeyword(errorType: String, message: String): String {
        val combined = ("$errorType $message").lowercase()

        return when {
            combined.contains("timeout") || combined.contains("timed out") -> "타임아웃 발생 - 외부 서비스 응답 지연 또는 네트워크 문제"
            combined.contains("connection") && combined.contains("refused") -> "연결 거부 - 대상 서비스 다운 또는 방화벽 차단"
            combined.contains("circuit") && combined.contains("open") -> "서킷브레이커 오픈 - 연속 장애로 보호 모드 진입"
            combined.contains("redis") || combined.contains("redisson") -> "Redis 관련 오류 - 캐시/락 서비스 문제"
            combined.contains("hikari") || combined.contains("connection pool") -> "DB 커넥션 풀 문제 - 커넥션 부족 또는 누수 의심"
            combined.contains("outofmemory") || combined.contains("heap") -> "메모리 부족 - JVM 힙 메모리 소진"
            combined.contains("thread") && combined.contains("exhaust") -> "스레드 풀 고갈 - 동시 요청 초과"
            else -> "원인 분석 필요 - 수동 점검 권장"
        }
    }

    fun determineSeverity(errorType: String, message: String): String {
        val combined = ("$errorType $message").lowercase()

        return when {
            combined.contains("outofmemory") || combined.contains("critical") -> "CRITICAL"
            combined.contains("circuit") || combined.contains("pool exhausted") -> "HIGH"
            combined.contains("timeout") || combined.contains("connection") -> "MEDIUM"
            else -> "LOW"
        }
    }

    fun inferAffectedComponents(errorType: String): String = when {
        errorType.contains("Redis") -> "Redis, TieredCache, LockStrategy"
        errorType.contains("Hikari") || errorType.contains("DataSource") -> "MySQL, Repository Layer"
        errorType.contains("Nexon") || errorType.contains("External") -> "NexonApiClient, ExternalService"
        errorType.contains("CircuitBreaker") -> "Resilience4j, Service Layer"
        else -> "Unknown"
    }

    fun suggestActions(errorType: String): String = when {
        errorType.contains("Timeout") -> "1. 대상 서비스 상태 확인\n2. 네트워크 지연 점검\n3. 타임아웃 값 검토"
        errorType.contains("Connection") -> "1. 연결 대상 서비스 확인\n2. 방화벽/보안그룹 점검\n3. DNS 확인"
        errorType.contains("Circuit") -> "1. 서킷브레이커 상태 확인\n2. 장애 원인 파악\n3. 수동 리셋 고려"
        else -> "1. 로그 상세 확인\n2. 메트릭 모니터링\n3. 개발팀 에스컬레이션"
    }
}
