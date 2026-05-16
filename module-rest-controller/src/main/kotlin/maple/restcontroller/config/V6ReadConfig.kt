package maple.restcontroller.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import maple.restcontroller.advice.RestControllerExceptionHandler
import maple.restcontroller.controller.ExpectationV6Controller
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.read.*
import maple.restcontroller.urgent.UrgentTriggerPublisher
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(V6ReadProperties::class)
class V6ReadConfig(
    private val properties: V6ReadProperties,
    private val meterRegistry: MeterRegistry
) {

    @Bean
    fun localRequestBuffer(): LocalRequestBuffer =
        LocalRequestBuffer(properties.queueCapacity)

    @Bean
    fun inflightRequestRegistry(): InflightRequestRegistry =
        InflightRequestRegistry()

    @Bean
    fun v6ReadMetrics(
        buffer: LocalRequestBuffer,
        registry: InflightRequestRegistry
    ): V6ReadMetrics = V6ReadMetrics(meterRegistry, buffer, registry)

    @Bean
    fun readModelQueryService(
        jdbc: NamedParameterJdbcTemplate,
        objectMapper: ObjectMapper
    ): ReadModelQueryService = ReadModelQueryService(jdbc, objectMapper)

    @Bean
    fun readModelCacheService(
        redisTemplate: StringRedisTemplate,
        objectMapper: ObjectMapper
    ): ReadModelCacheService = ReadModelCacheService(redisTemplate, objectMapper, properties)

    @Bean
    fun expectationReadFacade(
        registry: InflightRequestRegistry,
        buffer: LocalRequestBuffer,
        metrics: V6ReadMetrics
    ): ExpectationReadFacade = ExpectationReadFacade(registry, buffer, metrics)

    @Bean
    fun batchReadScheduler(
        buffer: LocalRequestBuffer,
        registry: InflightRequestRegistry,
        queryService: ReadModelQueryService,
        cacheService: ReadModelCacheService,
        v6ReadMetrics: V6ReadMetrics
    ): BatchReadScheduler = BatchReadScheduler(buffer, registry, queryService, cacheService, v6ReadMetrics, properties)

    @Bean
    fun expectationV6Controller(
        facade: ExpectationReadFacade
    ): ExpectationV6Controller = ExpectationV6Controller(facade, properties)

    @Bean
    fun restControllerExceptionHandler(): RestControllerExceptionHandler =
        RestControllerExceptionHandler()

    @Bean
    @ConditionalOnProperty(name = ["expectation.v6.urgent.enabled"], havingValue = "true")
    fun urgentTriggerPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        objectMapper: ObjectMapper,
        @Value("\${expectation.v6.urgent.request-topic}") topic: String
    ): UrgentTriggerPublisher = UrgentTriggerPublisher(kafkaTemplate, objectMapper, topic)
}
