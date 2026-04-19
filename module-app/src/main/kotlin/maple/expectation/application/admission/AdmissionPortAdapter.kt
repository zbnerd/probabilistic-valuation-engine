package maple.expectation.application.admission

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.inbound.AdmissionPort
import maple.expectation.infrastructure.admission.GlobalAdmissionControl
import org.springframework.stereotype.Component

/**
 * AdmissionPort 구현체 (ADR-005, Issue #639)
 *
 * <p>책임: GlobalAdmissionControl에 위임하여 web layer의 infrastructure 의존성 제거
 *
 * <p>DIP 위반 해결:
 * <ul>
 *   <li>module-web → AdmissionPort (module-core)</li>
 *   <li>module-app → AdmissionPortAdapter → GlobalAdmissionControl (module-infra)</li>
 * </ul>
 */
@Component
class AdmissionPortAdapter(
    private val globalAdmissionControl: GlobalAdmissionControl,
) : AdmissionPort {

    override fun <T> submitOrWait(key: String, task: Callable<T>): CompletableFuture<T> = globalAdmissionControl.submitOrWait(key, task)
}
