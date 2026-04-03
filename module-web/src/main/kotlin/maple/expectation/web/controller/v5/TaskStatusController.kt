package maple.expectation.web.controller.v5

import maple.expectation.core.port.inbound.TaskStatus
import maple.expectation.core.port.inbound.TaskStatusPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * V5 Task 상태 조회 Controller (ADR-355)
 *
 * <p>클라이언트가 비동기 계산 완료 여부를 polling.
 * userIgn을 path에 포함하여 타 사용자 taskId 추측 공격 방어.
 */
@RestController
@RequestMapping("/api/v5/characters")
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class TaskStatusController(
    private val taskStatusPort: TaskStatusPort,
) {

    @GetMapping("/{userIgn}/task/{taskId}")
    @PreAuthorize("permitAll()")
    fun getTaskStatus(
        @PathVariable userIgn: String,
        @PathVariable taskId: String,
    ): ResponseEntity<TaskStatusResponse> {
        val status = taskStatusPort.getStatus(userIgn, taskId)
        val response = TaskStatusResponse(taskId, status.name)

        return if (status == TaskStatus.PENDING || status == TaskStatus.PROCESSING) {
            ResponseEntity.ok()
                .header("Retry-After", "5")
                .body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }
}

/**
 * Task 상태 응답 DTO
 */
data class TaskStatusResponse(
    val taskId: String,
    val status: String,
)
