package maple.expectation.application.service.donation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.AdminMemberNotFoundException;
import maple.expectation.error.exception.SenderMemberNotFoundException;
import maple.expectation.infrastructure.persistence.repository.MemberRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내부 포인트 시스템 기반 결제 전략 (Atomic Query)
 *
 * <p>Issue #896: Rich Domain Model 제거, port의 원자적 쿼리로 통일.
 *
 * <h3>원자적 쿼리 전략</h3>
 *
 * <ul>
 *   <li><b>Sender (발신자)</b>: 원자적 UPDATE (memberRepository.decreasePointByUuid()) - 잔액 부족 시 0건 반환 -
 *       WHERE point >= :amount 조건으로 Lost Update 방지
 *   <li><b>Receiver (Admin)</b>: 원자적 쿼리 (memberRepository.increasePointByUuid()) - Hot Key 문제: 모든
 *       후원이 단일 Admin으로 집중됨 - 100명 동시 요청 시 원자적 UPDATE로 Lost Update 방지
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalPointPaymentStrategy implements PaymentStrategy {

  private final MemberRepository memberRepository;

  /**
   * 포인트 이체 처리 (Atomic Query)
   *
   * @param senderUuid 발신자 UUID
   * @param receiverFingerprint 수신자(Admin) fingerprint
   * @param amount 이체 금액
   */
  @Override
  @Transactional("transactionManager")
  public void processPayment(String senderUuid, String receiverFingerprint, Long amount) {
    log.debug(
        "[Payment] Processing internal point transfer: sender={}, amount={}",
        maskUuid(senderUuid),
        amount);

    // 1. 발신자: 원자적 UPDATE (Issue #896: Rich Domain → Port로 이관)
    //    - WHERE point >= :amount 조건으로 잔액 부족 시 0건 반환
    if (memberRepository.decreasePointByUuid(senderUuid, amount) == 0) {
      throw new SenderMemberNotFoundException(maskUuid(senderUuid));
    }

    // 2. 수신자(Admin): 원자적 쿼리 (Hot Key 보호)
    if (memberRepository.increasePointByUuid(receiverFingerprint, amount) == 0) {
      throw new AdminMemberNotFoundException(receiverFingerprint);
    }

    log.info("[Payment] Internal point transfer completed: amount={}", amount);
  }

  @Override
  public String getStrategyName() {
    return "INTERNAL_POINT";
  }

  private String maskUuid(String uuid) {
    if (uuid == null || uuid.length() < 8) {
      return "****";
    }
    return uuid.substring(0, 4) + "****";
  }
}
