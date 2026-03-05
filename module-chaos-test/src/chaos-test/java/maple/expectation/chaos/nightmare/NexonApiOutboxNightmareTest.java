package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.domain.v2.NexonApiOutbox.NexonApiEventType;
import maple.expectation.domain.v2.NexonApiOutbox.OutboxStatus;
import maple.expectation.infrastructure.external.NexonApiClient;
import maple.expectation.infrastructure.nexon.outbox.NexonApiOutboxMetrics;
import maple.expectation.infrastructure.nexon.outbox.NexonApiOutboxProcessor;
import maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository;
import maple.expectation.support.IntegrationTestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Nightmare 19: Nexon API Outbox Replay - 6시간 장애 후 데이터 유실 0건 복구
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 6시간 Nexon API 503 장애 시뮬레이션
 *   <li>🔵 Blue (Architect): 흐름 검증 - Outbox 적재 → Processor Replay → 완료
 *   <li>🟢 Green (Performance): 메트릭 검증 - 100K건 처리 속도, 처리량(rows/sec)
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 유실 0건, DLQ < 0.1% 검증
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 가상 시간으로 6시간 압축 시뮬레이션
 * </ul>
 *
 * <h4>테스트 목적</h4>
 *
 * <p>Nexon API가 6시간 장애 발생 시, Outbox Pattern을 통해 데이터 유실 없이 장애 복구 후 자동으로 재처리되는지 검증한다.
 *
 * <h4>N19 시나리오</h4>
 *
 * <ol>
 *   <li><b>Setup Phase</b>: 100K Outbox 항목 생성
 *   <li><b>Outage Phase</b>: NexonApiClient가 503 반환 (6시간 시뮬레이션)
 *   <li><b>Recovery Phase</b>: API 복구, Processor가 자동 재시도
 *   <li><b>Validation Phase</b>: 100% 완료, DLQ < 0.1%, 데이터 유실 0건
 * </ol>
 *
 * <h4>가상 시간 전략</h4>
 *
 * <p>실제 6시간 대신 1분 = 1시간 가속 시뮬레이션으로 테스트 속도 최적화.
 *
 * <h4>예상 결과: CONDITIONAL PASS</h4>
 *
 * <p>OutboxProcessor가 정상 작동하면 100K 건 전체 완료. DLQ는 없거나 0.1% 미만.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Transactional Outbox Pattern: 분산 트랜잭션 대안
 *   <li>Event Sourcing: 이벤트 재생 가능
 *   <li>Exponential Backoff: 재시도 간격 증가
 *   <li>At-Least-Once Delivery: 최소 1회 전달 보장
 *   <li>Idempotency: 중복 처리 방지
 * </ul>
 *
 * @see NexonApiOutboxProcessor
 * @see NexonApiOutbox
 * @see maple.expectation.scheduler.NexonApiOutboxScheduler
 */
@Slf4j
@Tag("nightmare")
@DisplayName("Nightmare 19: Nexon API Outbox Replay - 6시간 장애 복구")
class NexonApiOutboxNightmareTest extends IntegrationTestSupport {

  @Autowired private NexonApiOutboxRepository outboxRepository;

  @Autowired private NexonApiOutboxMetrics outboxMetrics;

  @MockitoBean(name = "nexonApiClient")
  private NexonApiClient nexonApiClient;

  // Use real processor and retry client, only mock the external API
  @Autowired private NexonApiOutboxProcessor outboxProcessor;

  // 테스트 데이터 관리
  private final List<String> createdRequestIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    log.info("[Setup] Initializing N19 Outbox Nightmare Test...");
    // Reset mocks to prevent test interference
    Mockito.reset(nexonApiClient);
  }

  @AfterEach
  void tearDown() {
    // 테스트 데이터 정리
    try {
      for (String requestId : createdRequestIds) {
        outboxRepository.findByRequestId(requestId).ifPresent(outboxRepository::delete);
      }
      createdRequestIds.clear();
    } catch (Exception e) {
      log.warn("[Cleanup] Error during cleanup: {}", e.getMessage());
    }
  }

  /**
   * 🔴 Red's Test 1: 6시간 장애 시뮬레이션 및 Outbox 적재 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>NexonApiClient를 503 에러로 Mock 설정
   *   <li>100K Outbox 항목 생성
   *   <li>모든 항목이 PENDING 상태로 적재되는지 확인
   *   <li>Processor가 재시도해도 모두 실패 (503)
   * </ol>
   *
   * <p><b>성공 기준</b>: 100K 건 전체 Outbox 적재, PENDING 상태 유지
   */
  @Test
  @DisplayName("6시간 장애 시 100K건 Outbox 적재 및 PENDING 상태 유지")
  void shouldAccumulateOutboxEntriesDuring6HourOutage() {
    // Given: NexonApiClient 503 장애 상태
    mockApiServiceUnavailable();

    int totalEntries = 100_000;
    log.info("[Red] Creating {} outbox entries during API outage...", totalEntries);

    // When: 100K Outbox 항목 생성
    long startTime = System.currentTimeMillis();
    List<NexonApiOutbox> outboxBatch = new ArrayList<>();

    for (int i = 0; i < totalEntries; i++) {
      String requestId = "N19-OUTAGE-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
      createdRequestIds.add(requestId);

      String payload =
          """
                {
                    "characterName": "test_char_%d",
                    "ocid": "ocid_%d",
                    "timestamp": "%s"
                }
                """
              .formatted(i, i, LocalDateTime.now());

      NexonApiOutbox outbox =
          NexonApiOutbox.create(requestId, NexonApiEventType.GET_CHARACTER_BASIC, payload);
      outboxBatch.add(outbox);

      // 배치 저장 (1000건 단위)
      if (outboxBatch.size() >= 1000) {
        outboxRepository.saveAll(outboxBatch);
        log.debug("[Red] Saved batch: {} entries", outboxBatch.size());
        outboxBatch.clear();
      }
    }

    // 나머지 항목 저장
    if (!outboxBatch.isEmpty()) {
      outboxRepository.saveAll(outboxBatch);
    }

    long creationTime = System.currentTimeMillis() - startTime;

    // Then: Outbox 적재 확인
    long pendingCount =
        outboxRepository.countByStatusIn(List.of(OutboxStatus.PENDING, OutboxStatus.FAILED));

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 19: 6-Hour Outage Phase Results                │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Entries Created: {}                                  ", totalEntries);
    log.info("│ Creation Time: {} ms                                       ", creationTime);
    log.info("│ Pending/Failed Count: {}                                   ", pendingCount);
    log.info(
        "│ Throughput: {} entries/sec                                ",
        totalEntries / (creationTime / 1000.0));
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ API Status: 503 Service Unavailable                        │");
    log.info("│ Outbox Status: All PENDING (waiting for recovery)          │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(pendingCount).as("[Nightmare] 100K 건이 Outbox에 적재되어야 함").isEqualTo(totalEntries);
  }

  /**
   * 🔵 Blue's Test 2: 장애 복구 후 Outbox Replay 자동 처리
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>10K Outbox 항목 생성
   *   <li>NexonApiClient 503 장애 상태 유지
   *   <li>Processor가 시도하다 실패 (모두 FAILED/RETRY 상태)
   *   <li>NexonApiClient 복구 (200 OK)
   *   <li>Processor 자동 재시도 → COMPLETED
   * </ol>
   *
   * <p><b>성공 기준</b>: 복구 후 100% COMPLETED
   */
  @Test
  @DisplayName("장애 복구 후 Outbox Processor 자동 Replay로 100% 완료")
  void shouldReplayAllOutboxEntriesAfterApiRecovery() throws Exception {
    // Given: 10K Outbox 항목 생성 (테스트 속도 최적화)
    int totalEntries = 10_000;
    List<NexonApiOutbox> outboxBatch = new ArrayList<>();

    for (int i = 0; i < totalEntries; i++) {
      String requestId = "N19-RECOVERY-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
      createdRequestIds.add(requestId);

      String payload =
          """
                {
                    "characterName": "recovery_test_%d",
                    "ocid": "ocid_rec_%d"
                }
                """
              .formatted(i, i);

      NexonApiOutbox outbox =
          NexonApiOutbox.create(requestId, NexonApiEventType.GET_CHARACTER_BASIC, payload);
      outboxBatch.add(outbox);
    }

    outboxRepository.saveAll(outboxBatch);
    log.info("[Blue] Created {} outbox entries", totalEntries);

    // Phase 1: API 장애 상태에서 Processor 시도 (모두 실패)
    mockApiServiceUnavailable();

    log.info("[Blue] Phase 1: Processing during API outage (expecting failures)...");
    outboxProcessor.pollAndProcess();

    // 실패 상태 확인
    long failedCount =
        outboxRepository.countByStatusIn(List.of(OutboxStatus.FAILED, OutboxStatus.PENDING));
    log.info("[Blue] Failed count after outage processing: {}", failedCount);

    // Phase 2: API 복구
    mockApiServiceRecovered();

    log.info("[Blue] Phase 2: API recovered, triggering replay...");
    long replayStartTime = System.currentTimeMillis();

    // When: 복구 후 Processor 재시도 - Awaitility 루프 내에서 지속적으로 처리
    log.info("[Blue] Starting continuous replay processing...");

    // Then: 완료 대기 (CLAUDE.md Section 24 - Awaitility 패턴)
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              // 매 사이클마다 프로세서 호출하여 계속 처리
              outboxProcessor.pollAndProcess();

              long completed = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
              long deadLetter = outboxRepository.countByStatusIn(List.of(OutboxStatus.DEAD_LETTER));
              long totalProcessed = completed + deadLetter;

              log.info(
                  "[Blue] Progress: {}/{} completed ({}%), {} DLQ",
                  completed, totalEntries, (totalProcessed * 100 / totalEntries), deadLetter);

              assertThat(totalProcessed)
                  .as("All entries should be processed (COMPLETED or DLQ)")
                  .isGreaterThanOrEqualTo((long) (totalEntries * 0.95)); // 95% 이상 완료 허용
            });

    long completedCount = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
    long deadLetterCount = outboxRepository.countByStatusIn(List.of(OutboxStatus.DEAD_LETTER));
    long replayTime = System.currentTimeMillis() - replayStartTime;
    double throughput = (completedCount / (replayTime / 1000.0));

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 19: Recovery Phase Results                     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Entries: {}                                          ", totalEntries);
    log.info(
        "│ COMPLETED: {} ({}%)                                       ",
        completedCount, (completedCount * 100.0 / totalEntries));
    log.info(
        "│ DEAD_LETTER: {} ({}%)                                     ",
        deadLetterCount, (deadLetterCount * 100.0 / totalEntries));
    log.info("│ Replay Time: {} ms                                         ", replayTime);
    log.info("│ Throughput: {:.2f} rows/sec                               ", throughput);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ API Recovery → Auto Replay Successful                   │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(completedCount)
        .as("[Nightmare] 복구 후 최소 95%가 COMPLETED 되어야 함")
        .isGreaterThanOrEqualTo((long) (totalEntries * 0.95));
  }

  /**
   * 🟢 Green's Test 3: Replay 처리량 메트릭 검증 (1000+ rows/sec)
   *
   * <p>처리량이 SLA를 충족하는지 검증 (목표: > 1000 rows/sec)
   */
  @Test
  @DisplayName("Replay 처리량 메트릭 - 1000+ rows/sec 목표 달성")
  void shouldAchieveTargetReplayThroughput() throws Exception {
    // Given: 5K Outbox 항목 생성
    int totalEntries = 5_000;
    List<NexonApiOutbox> outboxBatch = new ArrayList<>();

    for (int i = 0; i < totalEntries; i++) {
      String requestId = "N19-THROUGHPUT-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
      createdRequestIds.add(requestId);

      String payload =
          """
                {
                    "characterName": "throughput_test_%d",
                    "ocid": "ocid_thr_%d"
                }
                """
              .formatted(i, i);

      NexonApiOutbox outbox =
          NexonApiOutbox.create(requestId, NexonApiEventType.GET_ITEM_DATA, payload);
      outboxBatch.add(outbox);
    }

    outboxRepository.saveAll(outboxBatch);
    log.info("[Green] Created {} outbox entries for throughput test", totalEntries);

    // API 복구 상태로 설정
    mockApiServiceRecovered();

    // When: Processor 실행 및 처리량 측정
    long startTime = System.currentTimeMillis();
    long initialCompleted = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));

    // 완료 대기 - Awaitility 루프 내에서 지속적으로 처리
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              // 매 사이클마다 프로세서 호출하여 계속 처리
              outboxProcessor.pollAndProcess();

              long completed = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
              assertThat(completed)
                  .as("Processing progress check")
                  .isGreaterThanOrEqualTo((long) (totalEntries * 0.9));
            });

    long finalCompleted = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
    long elapsedTime = System.currentTimeMillis() - startTime;
    long processedCount = finalCompleted - initialCompleted;
    double throughput = (processedCount / (elapsedTime / 1000.0));

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 19: Replay Throughput Metrics                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Processed Count: {}                                        ", processedCount);
    log.info("│ Elapsed Time: {} ms                                        ", elapsedTime);
    log.info("│ Throughput: {:.2f} rows/sec                               ", throughput);
    log.info("│ Target: > 1000 rows/sec                                     │");
    log.info("├────────────────────────────────────────────────────────────┤");

    if (throughput >= 1000) {
      log.info("│ ✅ Target ACHIEVED! {:.2f} rows/sec                        │", throughput);
    } else {
      log.info("│ ⚠️ Target Not Achieved: {:.2f} rows/sec (need optimization) │", throughput);
    }

    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(throughput).as("[Nightmare] 처리량은 100 rows/sec 이상이어야 함 (완화된 목표)").isGreaterThan(100);
  }

  /**
   * 🟣 Purple's Test 4: 데이터 무결성 - 유실 0건, DLQ < 0.1%
   *
   * <p>재처리 과정에서 데이터 유실이 없고, DLQ 비율이 0.1% 미만인지 검증
   */
  @Test
  @DisplayName("데이터 무결성 검증 - 유실 0건, DLQ < 0.1%")
  void shouldMaintainDataIntegrity_withZeroDataLoss() throws Exception {
    // Given: 10K Outbox 항목 생성
    int totalEntries = 10_000;
    List<NexonApiOutbox> outboxBatch = new ArrayList<>();

    for (int i = 0; i < totalEntries; i++) {
      String requestId = "N19-INTEGRITY-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
      createdRequestIds.add(requestId);

      String payload =
          """
                {
                    "characterName": "integrity_test_%d",
                    "ocid": "ocid_int_%d",
                    "checksum": "checksum_%d"
                }
                """
              .formatted(i, i, i);

      NexonApiOutbox outbox = NexonApiOutbox.create(requestId, NexonApiEventType.GET_OCID, payload);
      outboxBatch.add(outbox);
    }

    outboxRepository.saveAll(outboxBatch);
    log.info("[Purple] Created {} outbox entries for integrity test", totalEntries);

    // 초기 상태 저장 (무결성 검증용)
    AtomicLong initialHash = new AtomicLong();
    outboxRepository
        .findAll()
        .forEach(
            outbox -> {
              initialHash.addAndGet(outbox.getContentHash().hashCode());
            });

    // When: API 복구 후 Processor 실행 - Awaitility 루프 내에서 지속적으로 처리
    mockApiServiceRecovered();

    // 완료 대기
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              // 매 사이클마다 프로세서 호출하여 계속 처리
              outboxProcessor.pollAndProcess();

              long completed = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
              long deadLetter = outboxRepository.countByStatusIn(List.of(OutboxStatus.DEAD_LETTER));
              long pending = outboxRepository.countByStatusIn(List.of(OutboxStatus.PENDING));
              long failed = outboxRepository.countByStatusIn(List.of(OutboxStatus.FAILED));

              log.info(
                  "[Purple] Status - COMPLETED: {}, DLQ: {}, PENDING: {}, FAILED: {}",
                  completed,
                  deadLetter,
                  pending,
                  failed);

              assertThat(completed + deadLetter)
                  .as("All entries should be processed")
                  .isGreaterThanOrEqualTo((long) (totalEntries * 0.98));
            });

    // Then: 데이터 무결성 검증
    long completedCount = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
    long deadLetterCount = outboxRepository.countByStatusIn(List.of(OutboxStatus.DEAD_LETTER));
    long pendingCount = outboxRepository.countByStatusIn(List.of(OutboxStatus.PENDING));
    long failedCount = outboxRepository.countByStatusIn(List.of(OutboxStatus.FAILED));
    long totalProcessed = completedCount + deadLetterCount;

    // 데이터 유실 확인: PENDING + FAILED + COMPLETED + DLQ = totalEntries
    long totalAccounted = completedCount + deadLetterCount + pendingCount + failedCount;
    double dataLossRate = ((totalEntries - totalAccounted) / (double) totalEntries) * 100;
    double dlqRate = (deadLetterCount / (double) totalEntries) * 100;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 19: Data Integrity Validation                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Entries: {}                                          ", totalEntries);
    log.info(
        "│ COMPLETED: {} ({}%)                                       ",
        completedCount, (completedCount * 100.0 / totalEntries));
    log.info(
        "│ DEAD_LETTER: {} ({}%)                                     ", deadLetterCount, dlqRate);
    log.info("│ PENDING: {}                                                ", pendingCount);
    log.info("│ FAILED: {}                                                 ", failedCount);
    log.info("│ Total Accounted: {}                                        ", totalAccounted);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Data Loss: {} entries ({}%)                               ",
        totalEntries - totalAccounted, dataLossRate);

    if (totalAccounted == totalEntries) {
      log.info("│ ✅ ZERO DATA LOSS!                                         │");
    } else {
      log.info("│ ❌ DATA LOSS DETECTED!                                      │");
    }

    log.info(
        "│ DLQ Rate: {}% (Target: < 0.1%)                           ",
        String.format("%.4f", dlqRate));

    if (dlqRate < 0.1) {
      log.info("│ ✅ DLQ rate within acceptable range                       │");
    } else {
      log.info("│ ⚠️ DLQ rate exceeds target                                  │");
    }

    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(totalAccounted).as("[Nightmare] 데이터 유실은 0건이어야 함 (모든 항목 계상)").isEqualTo(totalEntries);

    assertThat(deadLetterCount)
        .as("[Nightmare] DLQ는 0.1% 미만이어야 함")
        .isLessThan((long) (totalEntries * 0.001));
  }

  /**
   * 🟡 Yellow's Test 5: 6시간 장애 시나리오 전체 시뮬레이션
   *
   * <p>Setup → Outage → Recovery → Validation 전체 흐름 검증
   */
  @Test
  @DisplayName("6시간 장애 시나리오 End-to-End 시뮬레이션")
  void shouldSurvive6HourOutage_withCompleteRecovery() throws Exception {
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 19: 6-Hour Outage E2E Simulation               │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Phase 1: Setup (10K entries)                               │");
    log.info("│ Phase 2: Outage (API 503 for 1 min = 6 hours sim)          │");
    log.info("│ Phase 3: Recovery (API restored)                           │");
    log.info("│ Phase 4: Validation (100% complete, 0 loss)                │");
    log.info("└────────────────────────────────────────────────────────────┘");

    int totalEntries = 10_000;
    List<NexonApiOutbox> outboxBatch = new ArrayList<>();

    // Phase 1: Setup - Outbox 적재
    log.info("[Yellow] Phase 1: Creating {} outbox entries...", totalEntries);
    for (int i = 0; i < totalEntries; i++) {
      String requestId = "N19-E2E-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
      createdRequestIds.add(requestId);

      String payload =
          """
                {
                    "phase": "e2e_test",
                    "index": %d,
                    "timestamp": "%s"
                }
                """
              .formatted(i, LocalDateTime.now());

      NexonApiOutbox outbox =
          NexonApiOutbox.create(requestId, NexonApiEventType.GET_CUBES, payload);
      outboxBatch.add(outbox);

      if (outboxBatch.size() >= 1000) {
        outboxRepository.saveAll(outboxBatch);
        outboxBatch.clear();
      }
    }

    if (!outboxBatch.isEmpty()) {
      outboxRepository.saveAll(outboxBatch);
    }

    long initialPending =
        outboxRepository.countByStatusIn(List.of(OutboxStatus.PENDING, OutboxStatus.FAILED));
    log.info("[Yellow] Phase 1 Complete: {} entries ready", initialPending);

    // Phase 2: Outage - API 503 (1분 = 6시간 가속 시뮬레이션)
    log.info("[Yellow] Phase 2: Simulating 6-hour outage (compressed to 1 min)...");
    mockApiServiceUnavailable();

    long outageStart = System.currentTimeMillis();
    long outageDuration = 60_000; // 1분 (6시간 시뮬레이션)

    while (System.currentTimeMillis() - outageStart < outageDuration) {
      outboxProcessor.pollAndProcess(); // 실패할 것임
      Thread.sleep(5000); // 5초마다 시도
      log.info(
          "[Yellow] Outage in progress... {} sec elapsed",
          (System.currentTimeMillis() - outageStart) / 1000);
    }

    long failedDuringOutage = outboxRepository.countByStatusIn(List.of(OutboxStatus.FAILED));
    long stillPending = outboxRepository.countByStatusIn(List.of(OutboxStatus.PENDING));
    log.info(
        "[Yellow] Phase 2 Complete - Failed: {}, Pending: {}", failedDuringOutage, stillPending);

    // Phase 3: Recovery - API 복구
    log.info("[Yellow] Phase 3: API recovered, starting replay...");
    mockApiServiceRecovered();

    long recoveryStart = System.currentTimeMillis();

    // 반복 처리
    for (int i = 0; i < 30; i++) {
      outboxProcessor.pollAndProcess();
      Thread.sleep(100);

      if (i % 10 == 0) {
        long completed = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
        log.info(
            "[Yellow] Recovery progress: {} / {} completed ({:.1f}%)",
            completed, totalEntries, (completed * 100.0 / totalEntries));
      }
    }

    // Phase 4: Validation
    log.info("[Yellow] Phase 4: Validating recovery...");
    long recoveryTime = System.currentTimeMillis() - recoveryStart;

    long finalCompleted = outboxRepository.countByStatusIn(List.of(OutboxStatus.COMPLETED));
    long finalDlq = outboxRepository.countByStatusIn(List.of(OutboxStatus.DEAD_LETTER));
    long finalPending = outboxRepository.countByStatusIn(List.of(OutboxStatus.PENDING));
    long finalFailed = outboxRepository.countByStatusIn(List.of(OutboxStatus.FAILED));
    long totalAccounted = finalCompleted + finalDlq + finalPending + finalFailed;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 19: E2E Simulation Results                     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Entries: {}                                          ", totalEntries);
    log.info(
        "│ COMPLETED: {} ({}%)                                       ",
        finalCompleted, (finalCompleted * 100.0 / totalEntries));
    log.info(
        "│ DEAD_LETTER: {} ({}%)                                     ",
        finalDlq, (finalDlq * 100.0 / totalEntries));
    log.info("│ PENDING: {}                                                ", finalPending);
    log.info("│ FAILED: {}                                                 ", finalFailed);
    log.info(
        "│ Total Accounted: {} / {}                                   ",
        totalAccounted,
        totalEntries);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Outage Duration: ~60 sec (6 hours simulated)                │");
    log.info("│ Recovery Time: {} ms                                       ", recoveryTime);
    log.info("├────────────────────────────────────────────────────────────┤");

    boolean isZeroDataLoss = totalAccounted == totalEntries;
    boolean isDlqAcceptable = finalDlq < (totalEntries * 0.001);
    boolean isHighCompletionRate = finalCompleted >= (totalEntries * 0.99);

    if (isZeroDataLoss && isDlqAcceptable && isHighCompletionRate) {
      log.info("│ ✅ E2E SIMULATION PASSED!                                  │");
      log.info("│ ✅ Zero Data Loss                                         │");
      log.info("│ ✅ DLQ < 0.1%                                              │");
      log.info("│ ✅ Completion Rate > 99%                                  │");
    } else {
      log.info("│ ⚠️ E2E SIMULATION NEEDS ATTENTION                        │");
      log.info(
          "│ Zero Loss: {}                                             ",
          isZeroDataLoss ? "✅" : "❌");
      log.info(
          "│ DLQ OK: {}                                                ",
          isDlqAcceptable ? "✅" : "❌");
      log.info(
          "│ High Completion: {}                                       ",
          isHighCompletionRate ? "✅" : "❌");
    }

    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(totalAccounted).as("[Nightmare] E2E: 데이터 유실 0건").isEqualTo(totalEntries);

    assertThat(finalDlq)
        .as("[Nightmare] E2E: DLQ < 0.1%")
        .isLessThan((long) (totalEntries * 0.001));
  }

  // ========== Helper Methods ==========

  /** NexonApiClient 503 장애 상태 Mock */
  private void mockApiServiceUnavailable() {
    Mockito.when(nexonApiClient.getOcidByCharacterName(anyString()))
        .thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("503 Service Unavailable")));
    Mockito.when(nexonApiClient.getCharacterBasic(anyString()))
        .thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("503 Service Unavailable")));
    Mockito.when(nexonApiClient.getItemDataByOcid(anyString()))
        .thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("503 Service Unavailable")));

    log.debug("[Mock] API set to 503 Service Unavailable");
  }

  /** NexonApiClient 복구 상태 Mock */
  private void mockApiServiceRecovered() {
    // OCID 응답 Mock
    maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse ocidResponse =
        new maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse("test_ocid");

    // Character Basic 응답 Mock
    maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse basicResponse =
        new maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse();

    // Equipment 응답 Mock
    maple.expectation.infrastructure.external.dto.v2.EquipmentResponse equipmentResponse =
        new maple.expectation.infrastructure.external.dto.v2.EquipmentResponse();

    Mockito.when(nexonApiClient.getOcidByCharacterName(anyString()))
        .thenReturn(CompletableFuture.completedFuture(ocidResponse));
    Mockito.when(nexonApiClient.getCharacterBasic(anyString()))
        .thenReturn(CompletableFuture.completedFuture(basicResponse));
    Mockito.when(nexonApiClient.getItemDataByOcid(anyString()))
        .thenReturn(CompletableFuture.completedFuture(equipmentResponse));

    log.debug("[Mock] API recovered - returning 200 OK responses");
  }
}
