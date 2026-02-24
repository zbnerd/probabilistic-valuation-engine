package maple.expectation.infrastructure.mongodb

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoDatabase
import jakarta.annotation.PostConstruct
import lombok.RequiredArgsConstructor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.IndexOperations
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories
import java.util.concurrent.TimeUnit

/**
 * V5 CQRS: MongoDB Configuration
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Enable MongoDB repositories
 *   <li>Configure MongoDB template settings
 *   <li>Set up TTL index for 24-hour automatic expiry
 *   <li>Configure health check indicators
 * </ul>
 *
 * <h3>Activation</h3>
 *
 * Only active when v5.enabled=true
 *
 * <h3>TTL Index</h3>
 *
 * <p>Creates a TTL index on {@code calculatedAt} field with 24-hour expiry. This ensures stale data
 * is automatically removed without manual invalidation.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
@EnableMongoRepositories(basePackages = ["maple.expectation.infrastructure.mongodb"])
class MongoDBConfig(
    private val mongoClient: MongoClient,
    private val mongoTemplate: MongoTemplate
) {
    private val log = LoggerFactory.getLogger(MongoDBConfig::class.java)

    companion object {
        private const val COLLECTION_NAME = "character_valuation_views"
        private const val CALCULATED_AT_FIELD = "calculatedAt"
        private val TTL_SECONDS = TimeUnit.HOURS.toSeconds(24)
    }

    /**
     * Create TTL index on startup if it doesn't exist.
     *
     * <p>This ensures documents expire after 24 hours automatically.
     */
    @PostConstruct
    @Suppress("DEPRECATION")
    fun ensureTTLIndex() {
        try {
            val indexOps: IndexOperations = mongoTemplate.indexOps(CharacterValuationView::class.java)

            // Create TTL index on calculatedAt field
            val ttlIndex = Index()
                .on(CALCULATED_AT_FIELD, Sort.Direction.ASC)
                .expire(TTL_SECONDS)
                .named("_ttl_calculatedAt_")

            indexOps.ensureIndex(ttlIndex)

            log.info(
                "[MongoDB] TTL index created on {}.{} ({} seconds)",
                COLLECTION_NAME,
                CALCULATED_AT_FIELD,
                TTL_SECONDS
            )
        } catch (e: Exception) {
            log.error("[MongoDB] Failed to create TTL index", e)
            throw IllegalStateException("Failed to create MongoDB TTL index", e)
        }
    }

    /**
     * Verify MongoDB connection health.
     *
     * @return true if connection is healthy
     */
    fun isHealthy(): Boolean {
        return try {
            val databaseName = mongoTemplate.db.name
            val database: MongoDatabase = mongoClient.getDatabase(databaseName)
            database.runCommand(org.bson.BsonDocument("ping", org.bson.BsonInt32(1)))
            true
        } catch (e: Exception) {
            log.error("[MongoDB] Health check failed", e)
            false
        }
    }
}
