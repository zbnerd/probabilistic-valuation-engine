package maple.restcontroller.controller

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.read.ExpectationReadFacade
import maple.restcontroller.read.ReadModelCacheService
import maple.restcontroller.read.ReadModelQueryService
import maple.restcontroller.read.UrgentReadState
import maple.restcontroller.validation.ValidUserIgn
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult
import java.time.Duration

@RestController
@RequestMapping("/api/v6/characters")
@Validated
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class ExpectationV6Controller(
    private val facade: ExpectationReadFacade,
    private val properties: V6ReadProperties,
    private val cacheService: ReadModelCacheService,
    private val queryService: ReadModelQueryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{userIgn}/expectation")
    fun getExpectation(
        @PathVariable @ValidUserIgn userIgn: String,
        @RequestParam(defaultValue = "1") presetNo: Int,
    ): DeferredResult<ResponseEntity<*>> {
        log.debug("V6 read request userIgn={} presetNo={}", maskIgn(userIgn), presetNo)
        val deferred = DeferredResult<ResponseEntity<*>>(properties.requestTimeoutMs)
        facade.enqueue(userIgn, presetNo, deferred)
        return deferred
    }

    @GetMapping("/{userIgn}/status")
    fun getStatus(
        @PathVariable @ValidUserIgn userIgn: String,
        @RequestParam(defaultValue = "1") presetNo: Int,
    ): ResponseEntity<*> {
        val current = cacheService.status(userIgn, presetNo)
        val status = if (current.state.shouldTryDb()) {
            val dbResult = queryService.batchQuery(
                mapOf(userIgn to presetNo),
                Duration.ofSeconds(properties.readModelFreshnessSeconds),
            )
            if (dbResult.isNotEmpty()) {
                cacheService.multiPut(dbResult)
                cacheService.status(userIgn, presetNo)
            } else {
                current
            }
        } else {
            current
        }
        return ResponseEntity.ok()
            .header("Retry-After", status.retryAfterSeconds.toString())
            .body(status)
    }
}
