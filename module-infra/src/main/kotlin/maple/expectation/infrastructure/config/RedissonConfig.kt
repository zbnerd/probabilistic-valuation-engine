package maple.expectation.infrastructure.config

import org.redisson.Redisson
import org.redisson.api.NatMapper
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.redisson.config.ReadMode
import org.redisson.misc.RedisURI
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedissonConfig(
    @Value("\${spring.data.redis.sentinel.master:}") private val masterName: String,
    @Value("\${spring.data.redis.sentinel.nodes:}") private val sentinelNodes: String,
    @Value("\${spring.data.redis.host:localhost}") private val host: String,
    @Value("\${spring.data.redis.port:6379}") private val port: Int,
    @Value("\${redis.nat-mapping:}") private val natMapping: String,
) {

    companion object {
        private const val REDISSON_HOST_PREFIX = "redis://"
    }

    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config()

        if (isSentinelMode()) {
            configureSentinel(config)
        } else {
            configureSingleServer(config)
        }

        return Redisson.create(config)
    }

    private fun isSentinelMode(): Boolean = masterName.isNotEmpty() && sentinelNodes.isNotEmpty()

    private fun configureSentinel(config: Config) {
        val nodes = sentinelNodes.split(",")
        val addresses = nodes.map { REDISSON_HOST_PREFIX + it.trim() }.toTypedArray()

        val natMap = parseNatMapping(natMapping)

        config.useSentinelServers()
            .setMasterName(masterName)
            .addSentinelAddress(*addresses)
            .setCheckSentinelsList(false)
            .setScanInterval(1000)
            .setReadMode(ReadMode.MASTER)
            .setDnsMonitoringInterval(5000)
            .setRetryAttempts(3)
            .setRetryInterval(1500)
            .setTimeout(8000)
            .setConnectTimeout(5000)
            .setMasterConnectionPoolSize(64)
            .setMasterConnectionMinimumIdleSize(24)
            .setNatMapper(createNatMapper(natMap))
    }

    private fun createNatMapper(natMap: Map<String, String>): NatMapper = NatMapper { uri ->
        val currentHost = uri.host
        val currentPort = uri.port
        val key = "$currentHost:$currentPort"

        when {
            natMap.containsKey(key) -> mapToLocalhost(uri, natMap[key]!!)
            currentHost == "redis-master" -> resolveFromMapOrFallback(uri, natMap, "redis-master:6379")
            currentHost.startsWith("172.") -> RedisURI(uri.scheme, "127.0.0.1", currentPort)
            else -> uri
        }
    }

    private fun mapToLocalhost(uri: RedisURI, mappedValue: String): RedisURI {
        val parts = mappedValue.split(":")
        return RedisURI(uri.scheme, "127.0.0.1", parts[1].toInt())
    }

    private fun resolveFromMapOrFallback(uri: RedisURI, natMap: Map<String, String>, key: String): RedisURI {
        val mapped = natMap[key]
        return if (mapped != null) {
            mapToLocalhost(uri, mapped)
        } else {
            RedisURI(uri.scheme, "127.0.0.1", uri.port)
        }
    }

    private fun configureSingleServer(config: Config) {
        config.useSingleServer()
            .setAddress("$REDISSON_HOST_PREFIX$host:$port")
            .setRetryAttempts(3)
            .setRetryInterval(1500)
            .setTimeout(8000)
            .setConnectTimeout(5000)
            .setConnectionPoolSize(64)
            .setConnectionMinimumIdleSize(24)
    }

    private fun parseNatMapping(natMappingStr: String): Map<String, String> {
        if (natMappingStr.isEmpty()) return emptyMap()

        return natMappingStr.split(",")
            .mapNotNull { mapping ->
                val parts = mapping.trim().split("=")
                if (parts.size == 2) {
                    parts[0].trim() to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()
    }
}
