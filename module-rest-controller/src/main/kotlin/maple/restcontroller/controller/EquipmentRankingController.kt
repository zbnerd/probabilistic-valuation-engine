package maple.restcontroller.controller

import maple.restcontroller.ranking.EquipmentRankingService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v6/rankings/equipment")
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class EquipmentRankingController(
    private val rankingService: EquipmentRankingService,
) {
    @GetMapping("/total-cost/top10")
    fun topTotalCost(
        @RequestParam(defaultValue = "1") presetNo: Int,
    ): ResponseEntity<*> = ResponseEntity.ok(rankingService.topByTotalCost(presetNo))
}
