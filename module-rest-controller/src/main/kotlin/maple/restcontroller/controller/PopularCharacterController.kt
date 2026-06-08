package maple.restcontroller.controller

import maple.restcontroller.popular.PopularCharacterService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v6/characters/popular")
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class PopularCharacterController(
    private val popularCharacterService: PopularCharacterService,
) {
    @GetMapping("/top10")
    fun top10(
        @RequestParam(required = false) windowHours: Int?,
    ): ResponseEntity<*> = ResponseEntity.ok(popularCharacterService.top(windowHours))
}
