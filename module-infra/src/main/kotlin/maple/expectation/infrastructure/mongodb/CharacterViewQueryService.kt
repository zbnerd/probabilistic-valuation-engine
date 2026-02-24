package maple.expectation.infrastructure.mongodb

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * V5 CQRS Query Side Service - MongoDB Read Operations
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Fast read from CharacterValuationView collection
 *   <li>LogicExecutor pattern for exception handling
 *   <li>Micrometer metrics for monitoring
 *   <li>Graceful degradation on MongoDB failure
 * </ul>
 */
@Service
class CharacterViewQueryService(
    private val repository: CharacterValuationRepository,
    private val mongoTemplate: MongoTemplate,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(CharacterViewQueryService::class.java)

    /** Find character valuation view by user IGN (O(1) indexed lookup) */
    fun findByUserIgn(userIgn: String): CharacterValuationView? {
        val context = TaskContext.of("MongoQuery", "FindByUserIgn", userIgn)

        return executor.executeOrDefault(
            {
                val result = repository.findByUserIgn(userIgn)
                if (result != null) {
                    meterRegistry
                        .timer("mongodb.query.latency", "operation", "hit")
                        .record(Duration.ofMillis(1))
                    result
                } else {
                    meterRegistry
                        .timer("mongodb.query.latency", "operation", "miss")
                        .record(Duration.ofMillis(1))
                    null
                }
            },
            null,
            context
        )
    }

    /** Upsert character valuation view (insert or update) with idempotency */
    fun upsert(view: CharacterValuationView) {
        val context = TaskContext.of("MongoQuery", "Upsert", view.userIgn)

        executor.executeVoid(
            {
                // Idempotent upsert using messageId as unique key
                val query = Query(Criteria.where("messageId").`is`(view.messageId))
                val update = Update()
                    .set("userIgn", view.userIgn)
                    .set("characterOcid", view.characterOcid)
                    .set("characterClass", view.characterClass)
                    .set("characterLevel", view.characterLevel)
                    .set("totalExpectedCost", view.totalExpectedCost)
                    .set("maxPresetNo", view.maxPresetNo)
                    .set("calculatedAt", view.calculatedAt)
                    .set("lastApiSyncAt", view.lastApiSyncAt)
                    .set("version", view.version)
                    .set("fromCache", view.fromCache)
                    .set("presets", view.presets)

                mongoTemplate.upsert(query, update, CharacterValuationView::class.java)
            },
            context
        )
    }

    /** Delete by user IGN (for invalidation) */
    fun deleteByUserIgn(userIgn: String) {
        val context = TaskContext.of("MongoQuery", "Delete", userIgn)

        executor.executeVoid(
            {
                repository.deleteByUserIgn(userIgn)
            },
            context
        )
    }

    /** Delete all documents (for testing) */
    fun deleteAll() {
        val context = TaskContext.of("MongoQuery", "DeleteAll", "all")

        executor.executeVoid(
            {
                repository.deleteAll()
            },
            context
        )
    }

    /** Count all documents for a specific user IGN */
    fun countByUserIgn(userIgn: String): Long {
        val context = TaskContext.of("MongoQuery", "Count", userIgn)

        return executor.executeOrDefault(
            { if (repository.findByUserIgn(userIgn) != null) 1L else 0L },
            0L,
            context
        )
    }
}
