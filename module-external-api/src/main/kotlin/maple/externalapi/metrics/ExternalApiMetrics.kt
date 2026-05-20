package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class ExternalApiMetrics(registry: MeterRegistry) {

    private val usersFetched = registry.counter("external_api_users_fetched_total")
    private val usersFailed = registry.counter("external_api_users_failed_total")
    private val characterBasicFetched = registry.counter("external_api_character_basic_fetched_total")
    private val characterBasicFailed = registry.counter("external_api_character_basic_failed_total")
    private val itemEquipmentFetched = registry.counter("external_api_item_equipment_fetched_total")
    private val itemEquipmentFailed = registry.counter("external_api_item_equipment_failed_total")
    private val chunksCreated = registry.counter("external_api_chunks_created_total")

    private val rankingFetched = registry.counter("external_api_ranking_fetched_total")
    private val rankingFailed = registry.counter("external_api_ranking_failed_total")

    private val lookupTimer = Timer.builder("external_api_lookup_duration_seconds")
        .description("Time for a full endpoint lookup run")
        .register(registry)

    private val characterBasicTimer = Timer.builder("external_api_character_basic_duration_seconds")
        .description("Time for CHARACTER_BASIC lookup run")
        .register(registry)

    private val itemEquipmentTimer = Timer.builder("external_api_item_equipment_duration_seconds")
        .description("Time for ITEM_EQUIPMENT lookup run")
        .register(registry)

    fun recordFetched(count: Int = 1) = usersFetched.increment(count.toDouble())
    fun recordFailed(count: Int = 1) = usersFailed.increment(count.toDouble())

    fun recordCharacterBasicFetched(count: Int = 1) {
        characterBasicFetched.increment(count.toDouble())
        usersFetched.increment(count.toDouble())
    }

    fun recordCharacterBasicFailed(count: Int = 1) {
        characterBasicFailed.increment(count.toDouble())
        usersFailed.increment(count.toDouble())
    }

    fun recordItemEquipmentFetched(count: Int = 1) {
        itemEquipmentFetched.increment(count.toDouble())
        usersFetched.increment(count.toDouble())
    }

    fun recordItemEquipmentFailed(count: Int = 1) {
        itemEquipmentFailed.increment(count.toDouble())
        usersFailed.increment(count.toDouble())
    }

    fun recordChunkCreated(count: Int = 1) = chunksCreated.increment(count.toDouble())

    fun lookupTimer(): Timer = lookupTimer
    fun characterBasicTimer(): Timer = characterBasicTimer
    fun itemEquipmentTimer(): Timer = itemEquipmentTimer

    fun recordRankingFetched(count: Int = 1) {
        rankingFetched.increment(count.toDouble())
    }

    fun recordRankingFailed(count: Int = 1) {
        rankingFailed.increment(count.toDouble())
    }
}
