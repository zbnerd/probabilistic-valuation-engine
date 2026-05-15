package maple.restcontroller.controller

import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.read.ExpectationReadFacade
import maple.restcontroller.validation.ValidUserIgn
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import maple.expectation.util.StringMaskingUtils.maskIgn

@RestController
@RequestMapping("/api/v6/characters")
@Validated
class ExpectationV6Controller(
    private val facade: ExpectationReadFacade,
    private val properties: V6ReadProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{userIgn}/expectation")
    fun getExpectation(
        @PathVariable @ValidUserIgn userIgn: String
    ): DeferredResult<ResponseEntity<*>> {
        log.debug("V6 read request userIgn={}", maskIgn(userIgn))

        val deferred = DeferredResult<ResponseEntity<*>>(
            properties.requestTimeoutMs
        )

        facade.enqueue(userIgn, deferred)

        return deferred
    }
}
