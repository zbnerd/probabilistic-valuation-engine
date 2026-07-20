package maple.expectation.infrastructure.external.impl

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import maple.expectation.infrastructure.config.NexonApiProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Static golden characterization of the pre-consolidation BYOK HTTP contract and defects. */
class RealNexonAuthClientCharacterizationTest {
    @Test
    fun `current BYOK endpoint headers encoding and timeouts are frozen`() {
        val properties = NexonApiProperties()
        assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(3))
        assertThat(properties.responseTimeout).isEqualTo(Duration.ofSeconds(5))

        val config = source("config/MaplestoryApiConfig.kt")
        val client = source("external/impl/RealNexonAuthClient.kt")
        assertThat(config).contains("EncodingMode.VALUES_ONLY")
        assertThat(config).contains("HttpClient.create()")
        assertThat(config).contains("defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)")
        assertThat(client).contains("/maplestory/v1/character/list")
        assertThat(client.split(".header(\"x-nxopen-api-key\", apiKey)")).hasSize(2)
        assertThat(client).contains(".block(java.time.Duration.ofSeconds(5))")
    }

    @Test
    fun `current transient collapse raw-body logging and empty-list collapse are migration targets`() {
        val client = source("external/impl/RealNexonAuthClient.kt")
        assertThat(client).contains("executeOrDefault")
        assertThat(client).contains("ex.responseBodyAsString")
        assertThat(client).contains("ex.statusCode.is4xxClientError")
        assertThat(client).contains("Mono.empty()")
        assertThat(client).contains("r.accountList != null")
        assertThat(client).contains("requireNotNull(r.accountList).isNotEmpty()")
    }

    private fun source(relative: String): String {
        val local = Path.of("src/main/kotlin/maple/expectation/infrastructure").resolve(relative)
        val root = Path.of("module-infra/src/main/kotlin/maple/expectation/infrastructure").resolve(relative)
        return Files.readString(if (Files.exists(local)) local else root)
    }
}
