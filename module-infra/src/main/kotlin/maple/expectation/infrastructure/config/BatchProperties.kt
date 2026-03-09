package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

/**
 * Batch operation size configuration properties.
 *
 * <p>Centralizes batch sizes across the application to avoid hardcoded values and enable
 * environment-specific tuning.
 *
 * <h3>application.yml configuration example:</h3>
 *
 * <pre>
 * expectation:
 *   batch:
 *     like-relation-sync-size: 100
 *     expectation-write-size: 100
 *     acl-writer-size: 1000
 *     mysql-fallback-sync-size: 100
 * </pre>
 *
 * @property likeRelationSyncSize Batch size for LikeRelationSyncService (default: 100)
 * @property expectationWriteSize Batch size for ExpectationBatchWriteScheduler (default: 100)
 * @property aclWriterSize Batch size for BatchWriter ACL pipeline (default: 1000)
 * @property mysqlFallbackSyncSize Batch size for MySQL fallback compensation sync (default: 100)
 */
@Validated
@ConfigurationProperties(prefix = "expectation.batch")
data class BatchProperties(
    @DefaultValue("100") @Min(10) @Max(10000) val likeRelationSyncSize: Int = 100,
    @DefaultValue("100") @Min(10) @Max(10000) val expectationWriteSize: Int = 100,
    @DefaultValue("1000") @Min(100) @Max(10000) val aclWriterSize: Int = 1000,
    @DefaultValue("100") @Min(10) @Max(10000) val mysqlFallbackSyncSize: Int = 100,
) {
    companion object {
        /**
         * Factory method for default values.
         *
         * <p>Used in tests or when default configuration is needed.
         */
        fun defaults() = BatchProperties()
    }
}
