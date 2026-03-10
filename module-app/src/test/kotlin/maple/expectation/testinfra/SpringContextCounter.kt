package maple.expectation.testinfra

import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Profile
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Component

/**
 * Context 생성 횟수 추적용 리스너
 *
 * <p>ApplicationContext가 몇 번 생성되었는지 추적한다.
 * 2번 이상 생성되면 @MockBean 또는 설정 불일치 문제.
 */
@Component
@Profile("test")
class SpringContextCounter : ApplicationListener<ContextRefreshedEvent> {

    companion object {
        var count = 0
        var lastRefreshTime = 0L
    }

    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        count++
        lastRefreshTime = System.currentTimeMillis()
        println("[ContextCounter] Context refreshed. Total count: $count")
    }
}
