package maple.externalapi.scheduler.phase

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SchedulerClockConfig {
    @Bean
    fun systemClock(): Clock = Clock.systemDefaultZone()
}
