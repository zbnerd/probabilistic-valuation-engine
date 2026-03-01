package maple.expectation.infrastructure.monitoring.throttle

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RAtomicLong
import org.redisson.api.RMap
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 알림 스로틀러 (Issue #251) - Redis 기반 분산 상태 관리
 */
@Component
@ConditionalOnProperty(name = ["ai.sre.enabled"], havingValue = "true")
class AlertThrottler(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor
) {

  private val log = LoggerFactory.getLogger(AlertThrottler::class.java)

  @Value("\${ai.sre.daily-limit:100}")
  var dailyLimit: Int = 100

  @Value("\${ai.sre.throttle-seconds:60}")
  var throttleSeconds: Int = 60

  private fun buildDailyCountKey(): String {
    return "{ai-throttle}:daily-count:" + LocalDate.now()
  }

  private fun buildPatternTimesKey(): String {
    return "{ai-throttle}:pattern-times"
  }

  private fun getDailyCounter(): RAtomicLong {
    val counter = redissonClient.getAtomicLong(buildDailyCountKey())
    executor.executeVoidJava(
        { setCounterExpiry(counter) },
        TaskContext.of("AlertThrottler", "SetDailyTTL")
    )
    return counter
  }

  private fun setCounterExpiry(counter: RAtomicLong) {
    if (!counter.isExists || counter.remainTimeToLive() < 0) {
      counter.expire(25, TimeUnit.HOURS)
    }
  }

  private fun getPatternTimesMap(): RMap<String, Long> {
    val map = redissonClient.getMap<String, Long>(buildPatternTimesKey())
    executor.executeVoidJava(
        { setMapExpiry(map) },
        TaskContext.of("AlertThrottler", "SetPatternMapTTL")
    )
    return map
  }

  private fun setMapExpiry(map: RMap<String, Long>) {
    if (!map.isExists || map.remainTimeToLive() < 0) {
      map.expire((throttleSeconds * 10).toLong(), TimeUnit.SECONDS)
    }
  }

  @Scheduled(cron = "0 0 0 * * *")
  fun resetDailyCount() {
    log.info("[AlertThrottler] 일일 AI 호출 카운터 리셋 (Redis TTL 기반 자동 만료)")
  }

  fun canSendAiAnalysis(): Boolean {
    return executor.executeOrDefault(
        { checkAndIncrementDailyCount() },
        false,
        TaskContext.of("AlertThrottler", "CanSendAI")
    )
  }

  private fun checkAndIncrementDailyCount(): Boolean {
    val counter = getDailyCounter()
    val current = counter.incrementAndGet()
    
    if (current > dailyLimit) {
      log.warn("[AlertThrottler] 일일 AI 호출 한도 초과: {}/{}", current, dailyLimit)
      counter.decrementAndGet()
      return false
    }
    return true
  }

  fun shouldSendAlert(errorPattern: String): Boolean {
    return executor.executeOrDefault(
        { checkPatternThrottle(errorPattern) },
        false,
        TaskContext.of("AlertThrottler", "ShouldSend", errorPattern)
    )
  }

  private fun checkPatternThrottle(errorPattern: String): Boolean {
    val nowEpochSeconds = Instant.now().epochSecond
    val patternTimes = getPatternTimesMap()
    
    val lastTime = patternTimes[errorPattern]
    return if (lastTime != null) {
      evaluateThrottle(errorPattern, lastTime, nowEpochSeconds, patternTimes)
    } else {
      allowAndRecord(errorPattern, nowEpochSeconds, patternTimes)
    }
  }

  private fun evaluateThrottle(
      pattern: String,
      lastTime: Long,
      now: Long,
      map: RMap<String, Long>
  ): Boolean {
    val elapsed = now - lastTime
    if (elapsed < throttleSeconds) {
      log.debug("[AlertThrottler] 스로틀링: {} ({}초 후 재전송 가능)", pattern, throttleSeconds - elapsed)
      return false
    }
    return allowAndRecord(pattern, now, map)
  }

  private fun allowAndRecord(pattern: String, now: Long, map: RMap<String, Long>): Boolean {
    map[pattern] = now
    return true
  }

  fun canSendAiAnalysisWithThrottle(errorPattern: String): Boolean {
    return shouldSendAlert(errorPattern) && canSendAiAnalysis()
  }

  fun getDailyUsage(): Int {
    return executor.executeOrDefault(
        { fetchDailyUsage() },
        0,
        TaskContext.of("AlertThrottler", "GetUsage")
    )
  }

  private fun fetchDailyUsage(): Int {
    return getDailyCounter().get().toInt()
  }

  fun getRemainingCalls(): Int {
    return maxOf(0, dailyLimit - getDailyUsage())
  }

  @Scheduled(fixedRate = 3600000)
  fun cleanupThrottleCache() {
    log.debug("[AlertThrottler] 스로틀 캐시는 Redis TTL로 자동 관리됨 ({}초)", throttleSeconds * 10)
  }
}
