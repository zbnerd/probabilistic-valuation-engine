package maple.externalapi.scheduler.phase

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class SchedulerClockConfig {
    @Bean
    fun systemClock(): Clock = Clock.systemDefaultZone()
}
