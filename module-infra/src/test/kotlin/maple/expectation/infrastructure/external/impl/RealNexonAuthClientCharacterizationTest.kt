package maple.expectation.infrastructure.external.impl

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import maple.expectation.infrastructure.config.NexonApiProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Static characterization of the retained app/web BYOK compatibility surface. */
class RealNexonAuthClientCharacterizationTest {
    @Test
    fun `legacy properties and bean names delegate to the shared clients`() {
        val properties = NexonApiProperties()
        assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(3))
        assertThat(properties.responseTimeout).isEqualTo(Duration.ofSeconds(5))

        val config = source("config/MaplestoryApiConfig.kt")
        val client = source("external/impl/RealNexonAuthClient.kt")
        assertThat(config).contains("NexonClientAutoConfiguration")
        assertThat(config).contains("@Bean(\"mapleWebClient\")")
        assertThat(config).contains("@Qualifier(\"nexonSystemWebClient\")")
        assertThat(client).contains("ByokNexonClient")
        assertThat(client).contains("/maplestory/v1/character/list")
        assertThat(client).contains("Mono.fromFuture")
        assertThat(client).contains("plusMillis(FACADE_COMPLETION_MARGIN_MS)")
    }

    @Test
    fun `compatibility auth no longer owns transport logs bodies or defaults transient failures`() {
        val config = source("config/MaplestoryApiConfig.kt")
        val client = source("external/impl/RealNexonAuthClient.kt")
        assertThat(config).doesNotContain(
            "https://open.api.nexon.com",
            "HttpClient",
            "DefaultUriBuilderFactory",
            "WebClient.builder",
        )
        assertThat(client).doesNotContain(
            "LogicExecutor",
            "executeOrDefault",
            "responseBodyAsString",
            "Mono.empty()",
            "WebClient",
            ".join(",
            ".get(",
        )
        assertThat(client).contains("throw cause")
    }

    private fun source(relative: String): String {
        val local = Path.of("src/main/kotlin/maple/expectation/infrastructure").resolve(relative)
        val root = Path.of("module-infra/src/main/kotlin/maple/expectation/infrastructure").resolve(relative)
        return Files.readString(if (Files.exists(local)) local else root)
    }
}
