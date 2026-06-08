package maple.expectation.infrastructure.monitoring.copilot.pipeline

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.stats.CacheStats
import jakarta.annotation.PostConstruct
import java.nio.file.Path
import java.time.Duration
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.copilot.ingestor.GrafanaJsonIngestor
import maple.expectation.infrastructure.monitoring.copilot.model.SignalDefinition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["monitoring.copilot.enabled"], havingValue = "true")
class SignalDefinitionLoader(
    private val ingestor: GrafanaJsonIngestor,
    private val executor: LogicExecutor,
) {
    companion object {
        private const val CACHE_KEY = "signalCatalog"
        private val log = LoggerFactory.getLogger(SignalDefinitionLoader::class.java)
    }

    @Value("\${monitoring.copilot.grafana.dashboard-dir:./dashboards}")
    private var dashboardDir: String = "./dashboards"

    @Value("\${monitoring.copilot.cache-ttl-ms:300000}")
    private var cacheTtlMs: Long = 300000

    private lateinit var signalCatalogCache: Cache<String, List<SignalDefinition>>

    @PostConstruct
    fun initCache() {
        signalCatalogCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(cacheTtlMs))
            .recordStats()
            .build()
    }

    fun loadSignalDefinitions(): List<SignalDefinition> = signalCatalogCache.get(CACHE_KEY) { loadSignalDefinitionsFromDisk() }

    fun forceReload(): List<SignalDefinition> {
        signalCatalogCache.invalidate(CACHE_KEY)
        return loadSignalDefinitions()
    }

    fun getCacheSize(): Int {
        val cached = signalCatalogCache.getIfPresent(CACHE_KEY)
        return cached?.size ?: 0
    }

    fun isCached(): Boolean = signalCatalogCache.getIfPresent(CACHE_KEY) != null

    fun getCacheStats(): CacheStats = signalCatalogCache.stats()

    private fun loadSignalDefinitionsFromDisk(): List<SignalDefinition> = executor.executeOrDefault(
        {
            val dashboardPath = Path.of(dashboardDir)
            val signals = ingestor.ingestDashboards(dashboardPath)

            log.info("[SignalDefinitionLoader] Signal catalog refreshed: {} signals from {}", signals.size, dashboardPath)

            signals
        },
        emptyList(),
        TaskContext.of("SignalDefinitionLoader", "ReloadCatalog"),
    )
}
