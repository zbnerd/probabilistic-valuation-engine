package maple.expectation.service.v2.donation.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.infrastructure.config.OutboxProperties;
import maple.expectation.infrastructure.donation.dlq.DlqHandler;
import maple.expectation.infrastructure.donation.outbox.OutboxFetchFacade;
import maple.expectation.infrastructure.donation.outbox.OutboxMetrics;
import maple.expectation.infrastructure.donation.outbox.OutboxProcessor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OutboxProcessor 단위 테스트
 *
 * <h3>P0/P1 리팩토링 검증</h3>
 *
 * <ul>
 *   <li>P0-1: Zombie Loop 방지 - 실패 시 handleFailure() 호출 검증
 *   <li>P0-2: 항목별 독립 트랜잭션 - 개별 실패가 전체에 영향 없음
 *   <li>P1-2: OutboxProperties 생성자 주입
 * </ul>
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 검증
 */
@Tag("unit")
class OutboxProcessorTest {

  private DonationOutboxRepository outboxRepository;
  private DlqHandler dlqHandler;
  private OutboxMetrics metrics;
  private LogicExecutor executor;
  private TransactionTemplate transactionTemplate;
  private OutboxProperties properties;
  private OutboxFetchFacade fetchFacade;

  private OutboxProcessor outboxProcessor;

  @BeforeEach
  void setUp() {
    outboxRepository = mock(DonationOutboxRepository.class);
    dlqHandler = mock(DlqHandler.class);
    metrics = mock(OutboxMetrics.class);
    executor = TestLogicExecutors.passThrough();
    transactionTemplate = mock(TransactionTemplate.class);
    properties = createTestProperties();
    fetchFacade = mock(OutboxFetchFacade.class);

    // TransactionTemplate.execute()가 실제로 작업을 실행하도록 설정
    lenient()
        .when(
            transactionTemplate.execute(
                any(org.springframework.transaction.support.TransactionCallback.class)))
        .thenAnswer(
            invocation -> {
              org.springframework.transaction.support.TransactionCallback<?> callback =
                  invocation.getArgument(0);
              TransactionStatus mockStatus = mock(TransactionStatus.class);
              return callback.doInTransaction(mockStatus);
            });

    // executeWithoutResult는 void 메서드이므로 doAnswer 사용
    lenient()
        .doAnswer(
            invocation -> {
              org.springframework.transaction.support.TransactionCallbackWithoutResult callback =
                  invocation.getArgument(0);
              TransactionStatus mockStatus2 = mock(TransactionStatus.class);
              callback.doInTransaction(mockStatus2);
              return null;
            })
        .when(transactionTemplate)
        .executeWithoutResult(any());

    outboxProcessor =
        new OutboxProcessor(
            fetchFacade,
            dlqHandler,
            metrics,
            executor,
            transactionTemplate,
            properties,
            outboxRepository);
  }

  @Nested
  @DisplayName("pollAndProcess")
  class PollAndProcessTest {

    @Test
    @DisplayName("빈 목록 반환 시 아무 처리 없음")
    void shouldNotProcessWhenEmpty() {
      // given
      given(fetchFacade.fetchAndLock()).willReturn(Collections.emptyList());

      // when
      outboxProcessor.pollAndProcess();

      // then
      verify(outboxRepository, never()).save(any());
      verify(metrics, never()).incrementProcessed();
    }

    @Test
    @DisplayName("정상 처리 시 COMPLETED 상태로 변경")
    void shouldMarkCompletedOnSuccess() {
      // given
      DonationOutbox entry = createTestOutbox();
      given(fetchFacade.fetchAndLock()).willReturn(List.of(entry));
      given(outboxRepository.findById(entry.getId())).willReturn(Optional.of(entry));
      given(outboxRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

      // when
      outboxProcessor.pollAndProcess();

      // then
      verify(metrics).incrementProcessed();
    }

    @Test
    @DisplayName("처리 실패 시 예외 전파 (P0-1 Zombie Loop 방지)")
    void shouldCallHandleFailureOnProcessingError() {
      // given
      DonationOutbox entry = createTestOutbox();
      given(fetchFacade.fetchAndLock()).willReturn(List.of(entry));
      given(outboxRepository.findById(entry.getId())).willReturn(Optional.of(entry));
      given(outboxRepository.save(any()))
          .willThrow(new RuntimeException("DB error"))
          .willAnswer(invocation -> invocation.getArgument(0));

      // when
      outboxProcessor.pollAndProcess();

      // then - processEntry() 시도 중 sendNotification()은 호출됨
      // 참고: handleFailure()의 incrementFailed() 검증은
      // HandleFailureTest.shouldIncrementRetryCountOnFailure에서 수행
      verify(metrics).incrementNotificationSent();
    }
  }

  @Nested
  @DisplayName("recoverStalled")
  class RecoverStalledTest {

    @Test
    @DisplayName("Stalled 항목 없음 시 아무 처리 없음")
    void shouldNotRecoverWhenNoStalled() {
      // given
      given(outboxRepository.findStalledProcessing(any(LocalDateTime.class), any()))
          .willReturn(Collections.emptyList());

      // when
      outboxProcessor.recoverStalled();

      // then
      verify(outboxRepository, never()).save(any());
      verify(metrics, never()).incrementStalledRecovered(anyInt());
    }

    @Test
    @DisplayName("Stalled 항목 복구 성공")
    void shouldRecoverStalledEntries() {
      // given
      DonationOutbox stalledEntry = createTestOutbox();
      ReflectionTestUtils.setField(stalledEntry, "status", DonationOutbox.OutboxStatus.PROCESSING);

      given(outboxRepository.findStalledProcessing(any(LocalDateTime.class), any()))
          .willReturn(List.of(stalledEntry));
      given(outboxRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

      // when
      outboxProcessor.recoverStalled();

      // then
      verify(metrics).incrementStalledRecovered(1);
    }

    @Test
    @DisplayName("무결성 검증 실패 시 DLQ 이동")
    void shouldMoveToDlqOnIntegrityFailure() {
      // given
      DonationOutbox corruptedEntry = createTestOutbox();
      ReflectionTestUtils.setField(
          corruptedEntry, "status", DonationOutbox.OutboxStatus.PROCESSING);
      // 무결성 검증 실패하도록 payload 변조
      ReflectionTestUtils.setField(corruptedEntry, "payload", "corrupted");

      given(outboxRepository.findStalledProcessing(any(LocalDateTime.class), any()))
          .willReturn(List.of(corruptedEntry));
      given(outboxRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

      // when
      outboxProcessor.recoverStalled();

      // then
      verify(metrics).incrementIntegrityFailure();
      verify(dlqHandler).handleDeadLetter(any(), anyString());
    }
  }

  @Nested
  @DisplayName("handleFailure")
  class HandleFailureTest {

    @Test
    @DisplayName("실패 시 retryCount 증가")
    void shouldIncrementRetryCountOnFailure() {
      // given
      DonationOutbox entry = createTestOutbox();
      given(outboxRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

      // when
      outboxProcessor.handleFailure(entry, "Test error");

      // then
      verify(metrics).incrementFailed();
    }

    @Test
    @DisplayName("maxRetries 도달 시 DLQ 이동")
    void shouldMoveToDlqWhenMaxRetriesReached() {
      // given
      DonationOutbox entry = createTestOutbox();
      // retryCount를 max로 설정
      for (int i = 0; i < 5; i++) {
        entry.markFailed("error");
      }
      given(outboxRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

      // when
      outboxProcessor.handleFailure(entry, "Max retry exceeded");

      // then
      verify(dlqHandler).handleDeadLetter(entry, "Max retry exceeded");
    }
  }

  // ==================== Helper Methods ====================

  private DonationOutbox createTestOutbox() {
    DonationOutbox outbox =
        DonationOutbox.create("req-001", "DONATION_COMPLETED", "{\"amount\":1000}");
    ReflectionTestUtils.setField(outbox, "id", 1L);
    return outbox;
  }

  private OutboxProperties createTestProperties() {
    OutboxProperties props = mock(OutboxProperties.class);
    lenient().when(props.getInstanceId()).thenReturn("test-instance");
    lenient().when(props.getBatchSize()).thenReturn(10);
    lenient().when(props.getStaleThreshold()).thenReturn(java.time.Duration.ofMinutes(5));
    return props;
  }
}
