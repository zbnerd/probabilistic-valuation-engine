package maple.externalapi.infra.nexon

import java.nio.file.Files
import java.nio.file.Path
import maple.externalapi.config.NexonHttpClientProperties
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.KeyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Static golden characterization of the pre-consolidation system-key HTTP contract. */
class NexonExternalApiClientCharacterizationTest {
    @Test
    fun `current endpoint and query contract is frozen`() {
        assertThat(ExternalApiEndpoint.entries.map { Triple(it.name, it.path, it.keyType) }).containsExactly(
            Triple("OCID_LOOKUP", "/maplestory/v1/id", KeyType.USER_IGN),
            Triple("CHARACTER_BASIC", "/maplestory/v1/character/basic", KeyType.OCID),
            Triple("ITEM_EQUIPMENT", "/maplestory/v1/character/item-equipment", KeyType.OCID),
            Triple("RANKING_OVERALL", "/maplestory/v1/ranking/overall", KeyType.DATE_PAGE),
        )

        val source = productionSource("infra/nexon/NexonExternalApiClientAdapter.kt")
        assertThat(source).contains("EncodingMode.VALUES_ONLY")
        assertThat(source).contains("queryParam(\"character_name\", requestKey)")
        assertThat(source).contains("queryParam(\"ocid\", requestKey)")
        assertThat(source).contains("queryParam(\"date\", parts[0])")
        assertThat(source).contains("queryParam(\"page\", parts.getOrElse(1) { \"1\" })")
    }

    @Test
    fun `current headers limits pool and raw-byte behavior are frozen`() {
        val properties = NexonHttpClientProperties()
        assertThat(properties.poolName).isEqualTo("nexon-pool")
        assertThat(properties.maxConnections).isEqualTo(250)
        assertThat(properties.pendingAcquireMaxCount).isEqualTo(1_000)
        assertThat(properties.pendingAcquireTimeoutMs).isEqualTo(5_000)
        assertThat(properties.connectTimeoutMs).isEqualTo(3_000)
        assertThat(properties.responseTimeoutSeconds).isEqualTo(5)
        assertThat(properties.maxInMemorySizeBytes).isEqualTo(2 * 1024 * 1024)

        val source = productionSource("infra/nexon/NexonExternalApiClientAdapter.kt")
        assertThat(source.split(".header(\"x-nxopen-api-key\", apiKey)")).hasSize(2)
        assertThat(source).contains("defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)")
        assertThat(source).contains("bodyToMono(ByteArray::class.java)")
        assertThat(source).contains(".timeout(HTTP_CALL_TIMEOUT)")
    }

    @Test
    fun `unsafe legacy observability and status behavior remain named migration targets`() {
        val source = productionSource("infra/nexon/NexonExternalApiClientAdapter.kt")
        assertThat(source).contains(".metrics(properties.metricsEnabled) { uri -> uri }")
        assertThat(source).contains("ex.responseBodyAsString")
        assertThat(source).contains("throw ex")
    }

    private fun productionSource(relative: String): String {
        val local = Path.of("src/main/kotlin/maple/externalapi").resolve(relative)
        val root = Path.of("module-external-api/src/main/kotlin/maple/externalapi").resolve(relative)
        return Files.readString(if (Files.exists(local)) local else root)
    }
}
