package maple.nexon.client.config

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class NexonClientPropertiesTest {
    @Test
    fun `system and BYOK defaults preserve isolated policy`() {
        assertThat(SystemNexonClientProperties()).isEqualTo(
            SystemNexonClientProperties(
                poolName = "nexon-pool",
                maxConnections = 250,
                pendingAcquireMaxCount = 1_000,
                pendingAcquireTimeoutMs = 5_000,
                connectTimeoutMs = 3_000,
                responseTimeoutSeconds = 5,
                callTimeoutSeconds = 10,
                maxInMemorySizeBytes = 2 * 1024 * 1024,
                metricsEnabled = true,
            ),
        )
        assertThat(ByokNexonClientProperties()).isEqualTo(
            ByokNexonClientProperties(
                poolName = "nexon-byok-pool",
                maxConnections = 32,
                pendingAcquireMaxCount = 128,
                pendingAcquireTimeoutMs = 2_000,
                connectTimeoutMs = 3_000,
                responseTimeoutSeconds = 5,
                callTimeoutSeconds = 10,
                maxInMemorySizeBytes = 256 * 1024,
                metricsEnabled = true,
            ),
        )
    }

    @Test
    fun `profiles reject invalid bounds and shared pool names`() {
        assertThatThrownBy { SystemNexonClientProperties(maxConnections = 0).validated() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ByokNexonClientProperties(maxInMemorySizeBytes = 0).validated() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            NexonClientProfile.validateDistinctPoolNames(
                SystemNexonClientProperties(poolName = "shared"),
                ByokNexonClientProperties(poolName = "shared"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `legacy timeouts fill missing profile keys while new keys win`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    mapOf(
                        "nexon.http-client.connect-timeout-ms" to "9000",
                        "nexon.byok-http-client.response-timeout-seconds" to "11",
                    ),
                ),
            )
        }
        val legacy = LegacyNexonApiProperties(
            connectTimeout = Duration.ofSeconds(7),
            responseTimeout = Duration.ofSeconds(8),
        )

        val system = NexonClientAutoConfiguration.resolveSystemProperties(
            SystemNexonClientProperties(connectTimeoutMs = 9_000),
            legacy,
            environment,
        )
        val byok = NexonClientAutoConfiguration.resolveByokProperties(
            ByokNexonClientProperties(responseTimeoutSeconds = 11),
            legacy,
            environment,
        )

        assertThat(system.connectTimeoutMs).isEqualTo(9_000)
        assertThat(system.responseTimeoutSeconds).isEqualTo(8)
        assertThat(byok.connectTimeoutMs).isEqualTo(7_000)
        assertThat(byok.responseTimeoutSeconds).isEqualTo(11)
    }
}
