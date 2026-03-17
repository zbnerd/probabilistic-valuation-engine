package maple.expectation.testinfra

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

/**
 * PGMQ Competitive Consumer 테스트
 *
 * <h3>목적</h3>
 * <p>SKIP LOCKED가 정상 작동하여 여러 Worker가 동시에 메시지를 소비할 때
 * 중복 처리가 발생하지 않는지 검증
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>단일 메시지 - 2개 Worker가 경쟁 시 정확히 1개만 메시지 획득
 *   <li>100개 메시지 - 4개 Worker가 모두 처리하며 중복 없음
 *   <li>VT 만료 - 메시지가 보류 중일 때 재처리되지 않음
 * </ul>
 *
 * <h3>테스트 전략</h3>
 * <ul>
 *   <li>ExecutorService로 병렬 Worker 시뮬레이션
 *   <li>Thread.sleep(1)으로 짧은 지연 (awaitility.kotlintestfailures 금지)
 *   <li>CountDownLatch로 Worker 동기화
 *   <li>Awaitility로 비동기 완료 대기
 * </ul>
 *
 * @see maple.expectation.infrastructure.pgmq.PgmqClient
 * @see maple.expectation.infrastructure.pgmq.PgmqWorker
 */
@Tag("infra-verification")
class PgmqCompetitiveConsumerTest : IntegrationTestBase() {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    lateinit var pgmqTestSupport: PgmqTestSupport

    // 테스트별 고유 큐 이름
    private val singleMessageQueue = "test_competitive_single"
    private val batchMessageQueue = "test_competitive_batch"
    private val vtExpirationQueue = "test_competitive_vt"

    data class TestPayload(val id: Long, val data: String)

    @BeforeEach
    fun setUpQueues() {
        pgmqTestSupport.setUpQueue(singleMessageQueue)
        pgmqTestSupport.setUpQueue(batchMessageQueue)
        pgmqTestSupport.setUpQueue(vtExpirationQueue)

        log.debug("[PgmqCompetitiveConsumerTest] Queues initialized")
    }

    @Test
    fun `단일 메시지 - 2개 Worker가 경쟁 시 정확히 1개만 메시지를 획득한다`() {
        // Given
        val messageId = pgmqTestSupport.sendMessage(singleMessageQueue, TestPayload(1, "test-data"))
        log.debug("메시지 발행: msgId=$messageId")

        val processedCount = AtomicInteger(0)
        val workerLatch = CountDownLatch(2) // 2개 Worker 동시 시작
        val completionLatch = CountDownLatch(2) // 2개 Worker 완료 대기

        val executor: ExecutorService = Executors.newFixedThreadPool(2)

        // When - 2개 Worker가 동시에 메시지 읽기 시도
        repeat(2) { workerIndex ->
            executor.submit {
                try {
                    workerLatch.countDown() // 준비 완료 신호
                    workerLatch.await(100, TimeUnit.MILLISECONDS) // 동시 시작 동기화

                    val messages = pgmqTestSupport.readMessages(singleMessageQueue, TestPayload::class.java, vtSec = 5)

                    if (messages.isNotEmpty()) {
                        val received = messages[0]
                        log.debug("Worker-$workerIndex 메시지 획득: msgId=${received.messageId}, payload=${received.payload}")

                        // 메시지 처리 시뮬레이션
                        Thread.sleep(1) // kotlin delay 금지

                        // 처리 완료 후 아카이브
                        pgmqTestSupport.archiveMessage(singleMessageQueue, received.messageId)
                        processedCount.incrementAndGet()
                        log.debug("Worker-$workerIndex 처리 완료 및 아카이브: msgId=${received.messageId}")
                    } else {
                        log.debug("Worker-$workerIndex 메시지 없음 (다른 Worker가 획득)")
                    }
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        // Then - 모든 Worker 완료 대기
        assertThat(completionLatch.await(5, TimeUnit.SECONDS)).isTrue()
        executor.shutdown()

        // 단 1개의 Worker만 메시지를 처리해야 함
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted {
                assertThat(processedCount.get())
                    .`as`("단 1개의 Worker만 메시지를 처리해야 함")
                    .isEqualTo(1)
            }

        // 큐가 비어있어야 함
        assertThat(pgmqTestSupport.getQueueSize(singleMessageQueue))
            .`as`("큐는 비어있어야 함")
            .isEqualTo(0)

        // 아카이브에는 1개가 있어야 함
        assertThat(pgmqTestSupport.getArchiveSize(singleMessageQueue))
            .`as`("아카이브에는 정확히 1개의 메시지가 있어야 함")
            .isEqualTo(1)
    }

    @Test
    fun `100개 메시지 - 4개 Worker가 모두 처리하며 중복 없이 처리한다`() {
        // Given
        val messageCount = 100
        val workerCount = 4

        // 100개 메시지 발행
        val messageIds = mutableListOf<Long>()
        repeat(messageCount) { i ->
            val msgId = pgmqTestSupport.sendMessage(batchMessageQueue, TestPayload(i.toLong(), "data-$i"))
            messageIds.add(msgId)
        }
        log.debug("메시지 $messageCount 개 발행 완료")

        val processedMessages = mutableSetOf<Long>() // 처리된 메시지 ID 추적
        val processedCount = AtomicInteger(0)
        val workerLatch = CountDownLatch(workerCount)
        val completionLatch = CountDownLatch(workerCount)

        val executor: ExecutorService = Executors.newFixedThreadPool(workerCount)

        // When - 4개 Worker가 동시에 메시지 읽기 및 처리
        repeat(workerCount) { workerIndex ->
            executor.submit {
                try {
                    workerLatch.countDown()
                    workerLatch.await(100, TimeUnit.MILLISECONDS) // 동시 시작 동기화

                    // Worker는 계속 메시지를 읽음
                    while (true) {
                        val messages = pgmqTestSupport.readMessages(batchMessageQueue, TestPayload::class.java, vtSec = 5)

                        if (messages.isEmpty()) {
                            // 메시지가 없으면 잠시 대기 후 재시도
                            Thread.sleep(1) // kotlin delay 금지

                            // 큐가 비어있고 처리된 메시지가 목표 수에 도달하면 종료
                            if (pgmqTestSupport.getQueueSize(batchMessageQueue) == 0L &&
                                processedCount.get() >= messageCount
                            ) {
                                break
                            }
                            continue
                        }

                        // 메시지 처리
                        messages.forEach { msg ->
                            // 중복 체크 (SKIP LOCKED가 작동하면 중복 없음)
                            synchronized(processedMessages) {
                                if (processedMessages.contains(msg.messageId)) {
                                    log.error("❌ 중복 처리 감지: msgId=${msg.messageId}, worker=$workerIndex")
                                    throw IllegalStateException("메시지 중복 처리: ${msg.messageId}")
                                }
                                processedMessages.add(msg.messageId)
                            }

                            log.debug("Worker-$workerIndex 메시지 처리: msgId=${msg.messageId}, payload=${msg.payload}")

                            // 처리 시뮬레이션
                            Thread.sleep(1) // kotlin delay 금지

                            // 아카이브
                            pgmqTestSupport.archiveMessage(batchMessageQueue, msg.messageId)
                            processedCount.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    log.error("Worker-$workerIndex 에러: ${e.message}", e)
                    throw e
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        // Then - 모든 Worker 완료 대기
        assertThat(completionLatch.await(30, TimeUnit.SECONDS))
            .`as`("모든 Worker가 30초 내에 완료해야 함")
            .isTrue()
        executor.shutdown()

        // 모든 메시지가 정확히 1번씩 처리되어야 함
        await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted {
                assertThat(processedCount.get())
                    .`as`("모든 메시지가 처리되어야 함")
                    .isEqualTo(messageCount)

                assertThat(processedMessages.size)
                    .`as`("중복 없이 처리되어야 함")
                    .isEqualTo(messageCount)
            }

        // 큐가 비어있어야 함
        assertThat(pgmqTestSupport.getQueueSize(batchMessageQueue))
            .`as`("큐는 비어있어야 함")
            .isEqualTo(0)

        // 아카이브에는 모든 메시지가 있어야 함
        assertThat(pgmqTestSupport.getArchiveSize(batchMessageQueue))
            .`as`("아카이브에는 모든 메시지가 있어야 함")
            .isEqualTo(messageCount)
    }

    @Test
    fun `VT 만료 - 메시지가 보류 중일 때 다른 Worker가 읽지 못한다`() {
        // Given
        val messageId = pgmqTestSupport.sendMessage(vtExpirationQueue, TestPayload(1, "vt-test"))
        log.debug("메시지 발행: msgId=$messageId")

        val firstWorkerGotMessage = AtomicInteger(0)
        val secondWorkerGotMessage = AtomicInteger(0)
        val firstWorkerLatch = CountDownLatch(1)
        val secondWorkerLatch = CountDownLatch(1)

        val executor: ExecutorService = Executors.newFixedThreadPool(2)

        // When - 첫 번째 Worker가 메시지를 읽고 보유
        val firstWorker = executor.submit {
            try {
                val messages = pgmqTestSupport.readMessages(vtExpirationQueue, TestPayload::class.java, vtSec = 10)

                if (messages.isNotEmpty()) {
                    val received = messages[0]
                    log.debug("첫 번째 Worker 메시지 획득: msgId=${received.messageId}")
                    firstWorkerGotMessage.set(1)

                    // 메시지를 아카이브하지 않고 보유 (VT 동안)
                    firstWorkerLatch.countDown() // 두 번째 Worker 시작 신호

                    // 3초 대기 (VT=10초 이내이므로 여전히 보류 중)
                    Thread.sleep(100) // kotlin delay 금지
                    log.debug("첫 번째 Worker 여전히 메시지 보유 중")
                }
            } finally {
                // 테스트 정리를 위해 아카이브
                try {
                    val messages = pgmqTestSupport.readMessages(vtExpirationQueue, TestPayload::class.java, vtSec = 1)
                    if (messages.isNotEmpty()) {
                        pgmqTestSupport.archiveMessage(vtExpirationQueue, messages[0].messageId)
                    }
                } catch (e: Exception) {
                    log.debug("정리 중 아카이브 실패 (무시): ${e.message}")
                }
            }
        }

        // 두 번째 Worker는 첫 번째가 메시지를 획득한 후 읽기 시도
        val secondWorker = executor.submit {
            try {
                firstWorkerLatch.await(1, TimeUnit.SECONDS) // 첫 번째 Worker가 메시지 획득 대기
                secondWorkerLatch.countDown()

                // 첫 번째 Worker가 메시지를 보유하고 있는 동안 읽기 시도
                Thread.sleep(50) // kotlin delay 금지

                val messages = pgmqTestSupport.readMessages(vtExpirationQueue, TestPayload::class.java, vtSec = 10)

                if (messages.isNotEmpty()) {
                    log.error("❌ 두 번째 Worker가 메시지를 읽음 (VT 무시): msgId=${messages[0].messageId}")
                    secondWorkerGotMessage.set(1)
                } else {
                    log.debug("✅ 두 번째 Worker가 메시지를 읽지 못함 (VT 작동)")
                }
            } finally {
                secondWorkerLatch.countDown()
            }
        }

        // Then
        firstWorker.get(5, TimeUnit.SECONDS)
        secondWorker.get(5, TimeUnit.SECONDS)
        executor.shutdown()

        // 첫 번째 Worker만 메시지를 획득해야 함
        assertThat(firstWorkerGotMessage.get())
            .`as`("첫 번째 Worker는 메시지를 획득해야 함")
            .isEqualTo(1)

        // 두 번째 Worker는 메시지를 읽지 못해야 함 (VT 작동)
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted {
                assertThat(secondWorkerGotMessage.get())
                    .`as`("두 번째 Worker는 메시지를 읽지 못해야 함 (VT 작동)")
                    .isEqualTo(0)
            }
    }
}
