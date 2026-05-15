package maple.restcontroller.config

import io.micrometer.core.instrument.MeterRegistry
import maple.restcontroller.advice.RestControllerExceptionHandler
import maple.restcontroller.controller.ExpectationV6Controller
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.read.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun expectationReadFacade(
        registry: InflightRequestRegistry,
        buffer: LocalRequestBuffer,
        metrics: V6ReadMetrics
    ): ExpectationReadFacade = ExpectationReadFacade(registry, buffer, metrics)

    @Bean
    fun batchReadScheduler(
        buffer: LocalRequestBuffer,
        registry: InflightRequestRegistry
    ): BatchReadScheduler = BatchReadScheduler(buffer, registry, properties)

    @Bean
    fun expectationV6Controller(
        facade: ExpectationReadFacade
    ): ExpectationV6Controller = ExpectationV6Controller(facade, properties)

    @Bean
    fun restControllerExceptionHandler(): RestControllerExceptionHandler =
        RestControllerExceptionHandler()
}
