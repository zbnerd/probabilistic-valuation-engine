package maple.externalapi.scheduler.phase

import java.time.Clock
import java.time.format.DateTimeFormatter
import org.springframework.stereotype.Component

@Component
class RunIdGenerator(private val clock: Clock) {
    fun newRunId(): String {
        val now = clock.instant()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(clock.zone)
        return "${formatter.format(now)}-${now.nano}"
    }
}
