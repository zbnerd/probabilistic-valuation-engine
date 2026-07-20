package maple.synchronizer.ranking

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import maple.synchronizer.preparer.PreppedDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisZSetCommands
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate

class EquipmentRankingRedisWriterTest {
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: EquipmentRankingMetrics
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var properties: EquipmentRankingProperties
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = EquipmentRankingMetrics(registry)
        redisTemplate = mock()
        properties = EquipmentRankingProperties().apply {
            enabled = true
            keyPrefix = "ranking:equipment:total-cost"
            batchSize = 100
            topSize = 10
        }
        logger = requireNotNull(
            LoggerFactory.getLogger(EquipmentRankingRedisWriter::class.java) as? Logger,
        )
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    @Test
    fun `filter failure is visible and skips grouping and Redis`() {
        val documents = mock<List<PreppedDocument>>()
        whenever(documents.isEmpty()).thenReturn(false)
        whenever(documents.iterator()).thenThrow(IllegalStateException("filter failed"))

        assertThatCode { writer().update(documents) }.doesNotThrowAnyException()

        assertFailure("filter")
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `group failure is visible and skips Redis`() {
        val document = mock<PreppedDocument>()
        whenever(document.userIgn).thenReturn("Hero")
        whenever(document.presetNo).thenThrow(IllegalStateException("group failed"))

        assertThatCode { writer().update(listOf(document)) }.doesNotThrowAnyException()

        assertFailure("group")
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `Redis failure is visible and prevents the unsafe trim stage`() {
        whenever(redisTemplate.executePipelined(any<RedisCallback<*>>()))
            .thenThrow(IllegalStateException("redis failed"))

        assertThatCode { writer().update(listOf(document("Hero", 1, "100"))) }
            .doesNotThrowAnyException()

        assertFailure("redis")
        verify(redisTemplate, times(1)).executePipelined(any<RedisCallback<*>>())
    }

    @Test
    fun `successful grouping preserves Redis key bytes members scores and top-N trim`() {
        val connection = mock<RedisConnection>()
        val zSetCommands = mock<RedisZSetCommands>()
        whenever(connection.zSetCommands()).thenReturn(zSetCommands)
        whenever(redisTemplate.executePipelined(any<RedisCallback<*>>())).thenAnswer { invocation ->
            invocation.getArgument<RedisCallback<*>>(0).doInRedis(connection)
            emptyList<Any>()
        }
        val expectedKey = "ranking:equipment:total-cost:preset:1".toByteArray(StandardCharsets.UTF_8)

        writer().update(
            listOf(
                document("Hero", 1, "100"),
                document("Mage", 1, "250"),
            ),
        )

        verify(zSetCommands).zAdd(
            argThat { contentEquals(expectedKey) },
            eq(100.0),
            argThat { contentEquals("Hero".toByteArray(StandardCharsets.UTF_8)) },
        )
        verify(zSetCommands).zAdd(
            argThat { contentEquals(expectedKey) },
            eq(250.0),
            argThat { contentEquals("Mage".toByteArray(StandardCharsets.UTF_8)) },
        )
        verify(zSetCommands).zRemRange(argThat { contentEquals(expectedKey) }, eq(0L), eq(-11L))
        verify(redisTemplate, times(2)).executePipelined(any<RedisCallback<*>>())
        assertThat(failureCount("filter") + failureCount("group") + failureCount("redis"))
            .isEqualTo(0.0)
    }

    private fun writer(): EquipmentRankingRedisWriter = EquipmentRankingRedisWriter(
        redisTemplate = redisTemplate,
        properties = properties,
        metrics = metrics,
    )

    private fun document(
        userIgn: String,
        presetNo: Short,
        totalCost: String,
    ): PreppedDocument = PreppedDocument(
        readKey = "ocid-$userIgn:$presetNo",
        ocid = "ocid-$userIgn",
        presetNo = presetNo,
        userIgn = userIgn,
        compressed = byteArrayOf(1),
        documentHash = "hash-$userIgn",
        totalCost = BigDecimal(totalCost),
        equipmentCount = 1,
        calculatedAt = Timestamp.from(Instant.EPOCH),
    )

    private fun assertFailure(stage: String) {
        assertThat(failureCount(stage)).isEqualTo(1.0)
        assertThat(appender.list.any { event ->
            event.level == Level.WARN && event.formattedMessage.contains("stage=$stage")
        }).isTrue()
    }

    private fun failureCount(stage: String): Double =
        registry.find("equipment_ranking_write_failures_total")
            .tag("stage", stage)
            .counter()
            ?.count()
            ?: 0.0
}
