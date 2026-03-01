package maple.expectation.controller

import maple.expectation.core.port.inbound.AlertPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("!prod")
class AlertTestController(
    private val alertPort: AlertPort,
    private val executor: LogicExecutor
) {

    @PostMapping("/api/admin/test/alert")
    fun triggerTestAlert(): String {
        val context = TaskContext.of("Admin", "TestAlert")

        executor.executeVoidJava({
            // 1. 강제로 테스트용 예외 생성
            val testEx = RuntimeException("배포 후 알림 시스템 점검용 테스트 에러입니다.")

            // 2. 알림 Port 호출
            alertPort.sendCriticalAlert(
                "[TEST] 배포 점검 알림",
                "이 알림은 실제 에러가 아닙니다. 알림 시스템 작동 여부를 확인 중입니다.",
                testEx
            )
        }, context)

        return "알림 발송 요청 완료 (Discord 및 서버 로그를 확인하세요)"
    }
}
