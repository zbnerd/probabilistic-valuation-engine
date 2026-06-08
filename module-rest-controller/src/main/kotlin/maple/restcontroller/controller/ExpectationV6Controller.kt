package maple.restcontroller.controller

import java.time.Duration
import java.util.concurrent.CompletableFuture
import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.read.EnqueueResponseMapper
import maple.restcontroller.read.EnqueueResult
import maple.restcontroller.read.ExpectationReadFacade
import maple.restcontroller.read.NegativeCacheService
import maple.restcontroller.read.ReadModelCacheService
import maple.restcontroller.read.ReadModelQueryService
import maple.restcontroller.read.UrgentDedupService
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

@RestController
@RequestMapping("/api/v6/characters")
@Validated
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class ExpectationV6Controller(
    private val facade: ExpectationReadFacade,
    private val properties: V6ReadProperties,
    private val readModelCacheService: ReadModelCacheService,
    private val negativeCacheService: NegativeCacheService,
    private val urgentDedupService: UrgentDedupService,
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
        when (val result = facade.enqueue(userIgn, presetNo, deferred)) {
            is EnqueueResult.ServiceUnavailable ->
                deferred.setErrorResult(EnqueueResponseMapper.toServiceUnavailableResponse(result))
            is EnqueueResult.Queued,
            is EnqueueResult.AlreadyInFlight,
            -> {
                // Deferred stays open — facade wired onTimeout (202 via mapper)
                // and onCompletion (registry cleanup) callbacks already.
            }
        }
        return deferred
    }

    @GetMapping("/{userIgn}/status")
    // Issue #1130: CompletableFuture 반환. queryService.batchQuery() 의 CPU (gzip+JSON) 가
    // Dispatchers.Default 에서 실행. Tomcat thread block 시간 감소.
    fun getStatus(
        @PathVariable @ValidUserIgn userIgn: String,
        @RequestParam(defaultValue = "1") presetNo: Int,
    ): CompletableFuture<ResponseEntity<*>> {
        val current = projectStatus(userIgn, presetNo)
        if (!current.state.shouldTryDb()) {
            return CompletableFuture.completedFuture(buildStatusResponse(current))
        }
        return queryService.batchQuery(
            mapOf(userIgn to presetNo),
            Duration.ofSeconds(properties.readModelFreshnessSeconds),
        ).thenApply { dbResult ->
            if (dbResult.isNotEmpty()) {
                readModelCacheService.multiPut(dbResult)
                projectStatus(userIgn, presetNo)
            } else {
                current
            }
        }.thenApply { status ->
            ResponseEntity.ok()
                .header("Retry-After", status.retryAfterSeconds.toString())
                .body(status)
        }
    }

    private fun buildStatusResponse(status: UrgentReadStatus): ResponseEntity<*> =
        ResponseEntity.ok()
            .header("Retry-After", status.retryAfterSeconds.toString())
            .body(status)

    private fun projectStatus(userIgn: String, presetNo: Int) = urgentDedupService.status(
        userIgn = userIgn,
        presetNo = presetNo,
        hasReadyCache = readModelCacheService.hasReadyCache(userIgn, presetNo),
        hasNegativeCache = negativeCacheService.getNegativeCache(userIgn),
    )
}
