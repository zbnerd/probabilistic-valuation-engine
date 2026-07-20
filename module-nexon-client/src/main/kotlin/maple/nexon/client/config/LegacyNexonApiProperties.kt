package maple.nexon.client.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "nexon.api")
data class LegacyNexonApiProperties(
    val connectTimeout: Duration? = null,
    val responseTimeout: Duration? = null,
)
