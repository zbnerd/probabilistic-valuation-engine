package maple.expectation.infrastructure.aop.collector

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component

@Component
class PerformanceStatisticsCollector(
    private val registry: MeterRegistry, // ✅ 스프링 표준 메트릭 저장소
) {

    /** ✅ JVM 내부 필드를 삭제하고 Micrometer Timer로 대체 Timer는 내부적으로 count, sum, max를 모두 관리합니다. */
    fun addTime(testName: String, time: Long) {
        Timer.builder("nexon.api.performance") // 메트릭 이름
            .tag("service", testName) // 태그를 통해 인스턴스별/API별 구분 가능
            .description("Nexon API 호출 성능 통계")
            .register(registry)
            .record(time, TimeUnit.MILLISECONDS)
    }

    /** ✅ Micrometer에서 집계된 데이터를 가져와 출력용 데이터로 변환 */
    fun calculateStatistics(testName: String): Array<String> {
        val timer = registry.find("nexon.api.performance")
            .tag("service", testName)
            .timer()

        if (timer == null) {
            return arrayOf("🏆 [$testName] 수집된 통계 데이터가 없습니다.")
        }

        val count = timer.count()
        val totalTime = timer.totalTime(TimeUnit.MILLISECONDS)
        val maxTime = timer.max(TimeUnit.MILLISECONDS)
        val average = timer.mean(TimeUnit.MILLISECONDS)

        return arrayOf(
            "🏆 [$testName] 전역 성능 통계 (Micrometer):",
            "- 총 호출 수: ${count}회",
            "- 총 소요 시간: ${String.format("%.0f", totalTime)}ms",
            "- 평균 응답 시간: ${String.format("%.2f", average)}ms",
            "- 최대 Latency: ${String.format("%.0f", maxTime)}ms",
        )
    }
}
