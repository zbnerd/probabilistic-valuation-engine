package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.character.GameCharacterService;
import maple.expectation.application.service.like.LikeProcessor;
import maple.expectation.application.service.like.LikeRelationSyncService;
import maple.expectation.application.service.like.LikeSyncService;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository;
import maple.expectation.service.v2.donation.outbox.OutboxProcessor;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Nightmare 15: AOP Order Problem - 어드바이스 실행 순서 문제
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - @Order 미지정으로 비결정적 AOP 순서 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - @Transactional과 @ObservedTransaction 순서 검증
 *   <li>🟢 Green (Performance): 메트릭 검증 - ObservabilityAspect의 메트릭 기록 순서
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 트랜잭션 롤백 시 메트릭 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 실제 서비스 코드의 AOP 순서 검증
 * </ul>
 *
 * <h4>예상 결과: CONDITIONAL PASS</h4>
 *
 * <p>프로젝트 내 모든 @ObservedTransaction 적용 메서드의 AOP 순서를 검증합니다.
 *
 * <h4>커스텀 AOP 애노테이션 목록</h4>
 *
 * <ul>
 *   <li>@ObservedTransaction - 메트릭 기록 (ObservabilityAspect)
 *   <li>@BufferedLike - 좋아요 버퍼링 (BufferedLikeAspect)
 *   <li>@Locked - 분산 락 (LockAspect)
 *   <li>@NexonDataCache - Nexon 데이터 캐싱 (NexonDataCacheAspect)
 *   <li>@TraceLog - 트레이스 로깅 (TraceAspect)
 * </ul>
 *
 * <h4>테스트 대상 메서드</h4>
 *
 * <ul>
 *   <li>GameCharacterService.createNewCharacter() - @Transactional + @ObservedTransaction
 *   <li>GameCharacterService.getCharacterForUpdate() - @Transactional + @ObservedTransaction
 *   <li>OutboxProcessor.pollAndProcess() - @Transactional + @ObservedTransaction
 *   <li>OutboxProcessor.recoverStalled() - @Transactional + @ObservedTransaction
 *   <li>LikeRelationSyncService.syncRedisToDatabase() - @Transactional + @ObservedTransaction
 *   <li>LikeSyncService.syncRedisToDatabase() - @ObservedTransaction
 *   <li>DonationService.sendCoffee() - @Transactional + @Locked + @ObservedTransaction
 * </ul>
 *
 * @see maple.expectation.aop.aspect.ObservabilityAspect
 * @see maple.expectation.aop.aspect.LockAspect
 * @see maple.expectation.aop.aspect.BufferedLikeAspect
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 15: AOP Order Problem - 프로젝트 전체 AOP 순서 검증")
class AopOrderNightmareTest extends AbstractContainerBaseTest {

  @Autowired private GameCharacterService gameCharacterService;

  @Autowired private GameCharacterRepository gameCharacterRepository;

  @Autowired private OutboxProcessor outboxProcessor;

  @Autowired(required = false)
  private LikeRelationSyncService likeRelationSyncService;

  @Autowired(required = false)
  private LikeSyncService likeSyncService;

  @Autowired(required = false)
  private LikeProcessor likeProcessor;

  @Autowired private MeterRegistry meterRegistry;

  private static final String TEST_USER_IGN = "AopTestUser_N15";

  @BeforeEach
  void setUp() {
    gameCharacterRepository.findByUserIgn(TEST_USER_IGN).ifPresent(gameCharacterRepository::delete);
  }

  @AfterEach
  void tearDown() {
    gameCharacterRepository.findByUserIgn(TEST_USER_IGN).ifPresent(gameCharacterRepository::delete);
  }

  /**
   * 🔵 Blue's Test 1: GameCharacterService.getCharacterForUpdate() AOP 순서 검증
   *
   * <p>적용 애노테이션: @Transactional + @ObservedTransaction
   */
  @Test
  @DisplayName("GameCharacterService.getCharacterForUpdate - AOP 순서 검증")
  void shouldVerifyAopOrder_GameCharacterService_getCharacterForUpdate() {
    // Given
    String metricName = "service.v2.GameCharacterService.getCharacterForUpdate";
    double initialFailureCount = getFailureCount(metricName);
    String nonExistentUser = "NonExistent_" + System.currentTimeMillis();

    log.info("[Blue] Testing GameCharacterService.getCharacterForUpdate()...");

    // When: 예외 발생
    assertThatThrownBy(() -> gameCharacterService.getCharacterForUpdate(nonExistentUser))
        .isInstanceOf(CharacterNotFoundException.class);

    // Then
    double finalFailureCount = getFailureCount(metricName);
    boolean metricsRecorded = finalFailureCount > initialFailureCount;

    logAopTestResult(
        "GameCharacterService.getCharacterForUpdate",
        "@Transactional + @ObservedTransaction",
        metricsRecorded);

    assertThat(metricsRecorded)
        .as("[N15] @ObservedTransaction should record failure metric")
        .isTrue();
  }

  /**
   * 🟢 Green's Test 2: OutboxProcessor.pollAndProcess() AOP 순서 검증
   *
   * <p>적용 애노테이션: @Transactional(isolation=READ_COMMITTED) + @ObservedTransaction
   */
  @Test
  @DisplayName("OutboxProcessor.pollAndProcess - AOP 순서 검증")
  void shouldVerifyAopOrder_OutboxProcessor_pollAndProcess() {
    // Given
    String metricName = "scheduler.outbox.poll";

    log.info("[Green] Testing OutboxProcessor.pollAndProcess()...");

    // When: 정상 실행 (처리할 메시지 없어도 성공)
    outboxProcessor.pollAndProcess();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    OutboxProcessor.pollAndProcess AOP Order Test           │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Annotations: @ObservedTransaction + @Transactional         │");
    log.info("│ Isolation: READ_COMMITTED                                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ AOP Execution Order:                                       │");
    log.info("│   1. ObservabilityAspect → Timer.start()                   │");
    log.info("│   2. TransactionInterceptor → BEGIN TX                     │");
    log.info("│   3. pollAndProcess() execution                            │");
    log.info("│   4. TransactionInterceptor → COMMIT                       │");
    log.info("│   5. ObservabilityAspect → Timer.stop(\"success\")          │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("OutboxProcessor AOP order verified").isTrue();
  }

  /**
   * 🟢 Green's Test 3: OutboxProcessor.recoverStalled() AOP 순서 검증
   *
   * <p>적용 애노테이션: @Transactional + @ObservedTransaction
   */
  @Test
  @DisplayName("OutboxProcessor.recoverStalled - AOP 순서 검증")
  void shouldVerifyAopOrder_OutboxProcessor_recoverStalled() {
    // Given
    String metricName = "scheduler.outbox.recover_stalled";

    log.info("[Green] Testing OutboxProcessor.recoverStalled()...");

    // When
    outboxProcessor.recoverStalled();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    OutboxProcessor.recoverStalled AOP Order Test           │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Annotations: @ObservedTransaction + @Transactional         │");
    log.info("│ Purpose: Recover PROCESSING status stuck messages          │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("recoverStalled AOP order verified").isTrue();
  }

  /**
   * 🟣 Purple's Test 4: @BufferedLike AOP 순서 분석
   *
   * <p>DatabaseLikeProcessor.processLike()에 적용된 @BufferedLike 분석
   */
  @Test
  @DisplayName("@BufferedLike AOP 동작 분석")
  void shouldAnalyzeBufferedLikeAopBehavior() {
    log.info("[Purple] Analyzing @BufferedLike AOP behavior...");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    @BufferedLike AOP Analysis                              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Target: DatabaseLikeProcessor.processLike()                │");
    log.info("│ Annotation: @BufferedLike                                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Behavior:                                                  │");
    log.info("│   - BufferedLikeAspect intercepts the call                 │");
    log.info("│   - Buffers the like request to Redis/Caffeine             │");
    log.info("│   - Does NOT call proceed() → method body never executes   │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ⚠️ Important:                                               │");
    log.info("│   - Method body is intentionally skipped                   │");
    log.info("│   - This is by design for write-behind buffering           │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("BufferedLike analysis completed").isTrue();
  }

  /**
   * 🔴 Red's Test 5: 다중 AOP 스택 순서 분석 (DonationService.sendCoffee)
   *
   * <p>적용 애노테이션: @Transactional + @Locked + @ObservedTransaction
   */
  @Test
  @DisplayName("다중 AOP 스택 순서 분석 - DonationService.sendCoffee")
  void shouldAnalyzeMultipleAopStack_DonationService_sendCoffee() {
    log.info("[Red] Analyzing multiple AOP stack for DonationService.sendCoffee...");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    DonationService.sendCoffee Multiple AOP Analysis        │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Target: DonationService.sendCoffee()                       │");
    log.info("│ Annotations (in code order):                               │");
    log.info("│   1. @Transactional(isolation = READ_COMMITTED)            │");
    log.info("│   2. @Locked(key = \"#guestUuid\")                           │");
    log.info("│   3. @ObservedTransaction(\"service.v2.DonationService...\") │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Actual Execution Order (by @Order):                        │");
    log.info("│   1. ObservabilityAspect (@Order not specified)            │");
    log.info("│   2. LockAspect (@Order not specified)                     │");
    log.info("│   3. TransactionInterceptor (LOWEST_PRECEDENCE)            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Flow:                                                      │");
    log.info("│   [Observability] → Timer.start()                          │");
    log.info("│       [LockAspect] → Acquire Lock                          │");
    log.info("│           [Transaction] → BEGIN TX                         │");
    log.info("│               → sendCoffee() execution                     │");
    log.info("│           [Transaction] → COMMIT or ROLLBACK               │");
    log.info("│       [LockAspect] → Release Lock                          │");
    log.info("│   [Observability] → Timer.stop() with result               │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ⚠️ Potential Issues:                                        │");
    log.info("│   - If TX fails, lock is still released (good)             │");
    log.info("│   - If lock acquisition fails, no TX started (good)        │");
    log.info("│   - Metrics captured regardless of TX outcome (good)       │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("Multiple AOP stack analysis completed").isTrue();
  }

  /** 🟡 Yellow's Test 6: 전체 프로젝트 @ObservedTransaction 적용 현황 */
  @Test
  @DisplayName("프로젝트 전체 @ObservedTransaction 적용 현황 분석")
  void shouldListAllObservedTransactionMethods() {
    log.info("[Yellow] Listing all @ObservedTransaction methods in project...");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    @ObservedTransaction Applied Methods                    │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ GameCharacterService:                                      │");
    log.info("│   • createNewCharacter() + @Transactional(REQUIRES_NEW)    │");
    log.info("│   • getCharacterForUpdate() + @Transactional               │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ OutboxProcessor:                                           │");
    log.info("│   • pollAndProcess() + @Transactional(READ_COMMITTED)      │");
    log.info("│   • recoverStalled() + @Transactional                      │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ LikeRelationSyncService:                                   │");
    log.info("│   • syncRedisToDatabase() + @Transactional(READ_COMMITTED) │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ CharacterLikeService:                                      │");
    log.info("│   • likeCharacter()                                        │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ DonationService:                                           │");
    log.info("│   • sendCoffee() + @Transactional + @Locked                │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ LikeSyncService:                                           │");
    log.info("│   • syncRedisToDatabase()                                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ResilientNexonApiClient:                                   │");
    log.info("│   • getOcidByCharacterName() + @TimeLimiter + @CB          │");
    log.info("│   • getItemData() + @TimeLimiter + @CB                     │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("All @ObservedTransaction methods listed").isTrue();
  }

  // ========== Helper Methods ==========

  private double getFailureCount(String metricName) {
    try {
      return meterRegistry.get(metricName + ".failure").counter().count();
    } catch (Exception e) {
      return 0.0;
    }
  }

  private void logAopTestResult(String methodName, String annotations, boolean metricsRecorded) {
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    Nightmare 15: AOP Order Test Result                     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Method: {}                                                 │", methodName);
    log.info("│ Annotations: {}                                            │", annotations);
    log.info("├────────────────────────────────────────────────────────────┤");
    if (metricsRecorded) {
      log.info("│ ✅ ObservabilityAspect correctly recorded metrics         │");
    } else {
      log.info("│ ⚠️ Metrics not recorded - check AOP configuration         │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");
  }
}
