package maple.expectation.infrastructure.resilience

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import maple.expectation.error.exception.MapleDataProcessingException
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RStream
import org.redisson.api.RedissonClient
import org.redisson.api.StreamMessageId
import org.redisson.api.stream.StreamAddArgs
import org.redisson.api.stream.StreamCreateGroupArgs
import org.redisson.api.stream.StreamReadGroupArgs
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    prefix = "resilience.mysql-fallback",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class CompensationLogService(
    private val redissonClient: RedissonClient,
    private val properties: MySQLFallbackProperties,
    private val objectMapper: ObjectMapper,
    private val checkedExecutor: CheckedLogicExecutor,
) {
    private val logger = LoggerFactory.getLogger(CompensationLogService::class.java)

    private val instanceId: String = generateInstanceId()

    companion object {
        private const val FIELD_TYPE = "type"
        private const val FIELD_KEY = "key"
        private const val FIELD_DATA = "data"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_RETRY_COUNT = "retryCount"

        private fun generateInstanceId(): String = Optional.ofNullable(System.getenv("HOSTNAME"))
            .or { Optional.ofNullable(System.getenv("COMPUTERNAME")) }
            .orElse("unknown") + "-" + UUID.randomUUID().toString().substring(0, 8)
    }

    @PostConstruct
    fun initConsumerGroup() {
        logger.info("[CompensationLog] Instance consumer ID: {}", instanceId)

        checkedExecutor.executeUncheckedVoid(
            {
                val stream = redissonClient.getStream<Any, Any>(properties.compensationStream)

                if (!stream.isExists) {
                    stream.createGroup(StreamCreateGroupArgs.name(properties.syncConsumerGroup).makeStream())
                    logger.info(
                        "[CompensationLog] Stream 및 Consumer Group 생성: {}",
                        properties.syncConsumerGroup,
                    )
                    return@executeUncheckedVoid
                }

                try {
                    stream.readGroup(
                        properties.syncConsumerGroup,
                        instanceId,
                        StreamReadGroupArgs.neverDelivered().count(1),
                    )
                } catch (e: Exception) {
                    if (e.message?.contains("NOGROUP") == true) {
                        stream.createGroup(StreamCreateGroupArgs.name(properties.syncConsumerGroup))
                        logger.info(
                            "[CompensationLog] Consumer Group 재생성 (NOGROUP 복구): {}",
                            properties.syncConsumerGroup,
                        )
                    } else {
                        throw e
                    }
                }
            },
            TaskContext.of("Compensation", "InitConsumerGroup", properties.syncConsumerGroup),
        ) { e: Throwable -> MapleDataProcessingException("Consumer Group 초기화 실패", e) }
    }

    fun writeLog(type: String, key: String, data: Any): StreamMessageId = checkedExecutor.executeUnchecked(
        {
            val jsonData = serializeDataSafely(data)

            val stream: RStream<Any, Any> = redissonClient.getStream(properties.compensationStream)

            val messageId = stream.add(
                StreamAddArgs.entries<Any, Any>(
                    mapOf(
                        FIELD_TYPE to type,
                        FIELD_KEY to key,
                        FIELD_DATA to jsonData,
                        FIELD_TIMESTAMP to Instant.now().toString(),
                        FIELD_RETRY_COUNT to "0",
                    ),
                )
                    .trimNonStrict()
                    .maxLen(properties.streamMaxLen)
                    .noLimit(),
            )

            logger.info(
                "[CompensationLog] 로그 기록 완료: type={}, key={}, messageId={}",
                type,
                key,
                messageId,
            )
            messageId
        },
        TaskContext.of("Compensation", "WriteLog", key),
    ) { e: Throwable -> MapleDataProcessingException("Compensation Log 기록 실패: $key", e) }

    fun readLogs(consumerId: String, count: Int): Map<StreamMessageId, Map<String, String>> {
        return checkedExecutor.executeUnchecked(
            {
                val stream: RStream<Any, Any> = redissonClient.getStream(properties.compensationStream)

                val autoClaimResult = stream.autoClaim(
                    properties.syncConsumerGroup,
                    instanceId,
                    600_000L,
                    TimeUnit.MILLISECONDS,
                    StreamMessageId.MIN,
                    count,
                )

                val claimedMessages = autoClaimResult.messages
                if (claimedMessages.isNotEmpty()) {
                    logger.info("[CompensationLog] Pending 메시지 재처리: {} 건", claimedMessages.size)
                    @Suppress("UNCHECKED_CAST")
                    return@executeUnchecked convertToStringMap(claimedMessages)
                }

                @Suppress("UNCHECKED_CAST")
                val rawMessages = stream.readGroup(
                    properties.syncConsumerGroup,
                    instanceId,
                    StreamReadGroupArgs.neverDelivered().count(count),
                ) as Map<StreamMessageId, Map<Any, Any>>
                convertToStringMap(rawMessages)
            },
            TaskContext.of("Compensation", "ReadLogs", instanceId),
        ) { e: Throwable -> MapleDataProcessingException("Compensation Log 읽기 실패", e) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertToStringMap(messages: Map<StreamMessageId, Map<Any, Any>>): Map<StreamMessageId, Map<String, String>> = messages.mapValues { entry ->
        entry.value.mapKeys { it.key as String }
            .mapValues { it.value as String }
    }

    fun ackLogs(messageIds: List<StreamMessageId>) {
        if (messageIds.isEmpty()) {
            return
        }

        checkedExecutor.executeUncheckedVoid(
            {
                val stream = redissonClient.getStream<Any, Any>(properties.compensationStream)
                stream.ack(properties.syncConsumerGroup, *messageIds.toTypedArray())
                logger.debug("[CompensationLog] ACK 완료: {} 건", messageIds.size)
            },
            TaskContext.of("Compensation", "AckLogs", messageIds.size.toString()),
        ) { e: Throwable -> MapleDataProcessingException("Compensation Log ACK 실패", e) }
    }

    fun moveToDlq(originalMessageId: StreamMessageId, entry: Map<String, String>, errorMessage: String) {
        checkedExecutor.executeUncheckedVoid(
            {
                val dlqStream: RStream<Any, Any> = redissonClient.getStream(properties.compensationDlq)

                dlqStream.add(
                    StreamAddArgs.entries<Any, Any>(
                        mapOf(
                            FIELD_TYPE to entry.getOrDefault(FIELD_TYPE, "unknown"),
                            FIELD_KEY to entry.getOrDefault(FIELD_KEY, "unknown"),
                            FIELD_DATA to entry.getOrDefault(FIELD_DATA, "{}"),
                            FIELD_TIMESTAMP to entry.getOrDefault(FIELD_TIMESTAMP, Instant.now().toString()),
                            FIELD_RETRY_COUNT to entry.getOrDefault(FIELD_RETRY_COUNT, "0"),
                            "originalMessageId" to originalMessageId.toString(),
                            "errorMessage" to errorMessage,
                            "dlqTimestamp" to Instant.now().toString(),
                        ),
                    )
                        .trimNonStrict()
                        .maxLen(properties.streamMaxLen)
                        .noLimit(),
                )

                ackLogs(listOf(originalMessageId))

                logger.warn(
                    "[CompensationLog] DLQ 이동: messageId={}, error={}",
                    originalMessageId,
                    errorMessage,
                )
            },
            TaskContext.of("Compensation", "MoveToDlq", originalMessageId.toString()),
        ) { e: Throwable -> MapleDataProcessingException("DLQ 이동 실패: $originalMessageId", e) }
    }

    fun getPendingCount(): Long = checkedExecutor.executeUnchecked(
        {
            val stream = redissonClient.getStream<Any, Any>(properties.compensationStream)
            stream.getPendingInfo(properties.syncConsumerGroup).total
        },
        TaskContext.of("Compensation", "GetPendingCount", properties.compensationStream),
    ) { e: Throwable -> MapleDataProcessingException("Pending 메시지 수 조회 실패", e) }

    fun getDlqCount(): Long = checkedExecutor.executeUnchecked(
        {
            val dlqStream = redissonClient.getStream<Any, Any>(properties.compensationDlq)
            if (dlqStream.isExists) dlqStream.size() else 0L
        },
        TaskContext.of("Compensation", "GetDlqCount", properties.compensationDlq),
    ) { e: Throwable -> MapleDataProcessingException("DLQ 메시지 수 조회 실패", e) }

    private fun serializeDataSafely(data: Any): String = try {
        objectMapper.writeValueAsString(data)
    } catch (e: JsonProcessingException) {
        throw MapleDataProcessingException("JSON 직렬화 실패: ${data.javaClass.simpleName}", e)
    }

    fun <T> deserializeData(json: String, type: Class<T>): T = checkedExecutor.executeUnchecked(
        {
            try {
                objectMapper.readValue(json, type)
            } catch (e: JsonProcessingException) {
                throw MapleDataProcessingException("JSON 역직렬화 실패: ${type.simpleName}", e)
            }
        },
        TaskContext.of("Compensation", "DeserializeData", type.simpleName),
    ) { e: Throwable -> MapleDataProcessingException("JSON 역직렬화 실패: ${type.simpleName}", e) }
}
