package maple.calculator.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Properties for polling external-api's /api/internal/run-status to discover
 * the currently-active runId. Calculator uses this to drop chunk-ready events
 * whose runId does not match (i.e. stale messages from a prior failed run).
 */
@ConfigurationProperties(prefix = "calculator.external-api")
class ExternalApiRunStatusProperties(
    var baseUrl: String = "http://localhost:8081",
)
