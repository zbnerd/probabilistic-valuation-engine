package maple.restcontroller.urgent

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.read.ReadModelCacheService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["expectation.v6.urgent.enabled"], havingValue = "true")
class UrgentCharacterNotFoundConsumer(
    private val cacheService: ReadModelCacheService,
    private val objectMapper: ObjectMapper,
    @Value("\${expectation.v6.urgent.negative-cache-ttl-seconds}") private val negativeCacheTtlSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${expectation.v6.urgent.not-found-topic}"],
        groupId = "\${spring.kafka.consumer.group-id}"
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val userIgn = objectMapper.readTree(message).get("userIgn")?.asText()
        if (userIgn == null) {
            log.warn("Missing userIgn in not-found message, acknowledging to skip")
            acknowledgment.acknowledge()
            return
        }
        log.info("Received character-not-found event: userIgn={}", maskIgn(userIgn))
        cacheService.setNegativeCache(userIgn, negativeCacheTtlSeconds)
        acknowledgment.acknowledge()
    }
}
