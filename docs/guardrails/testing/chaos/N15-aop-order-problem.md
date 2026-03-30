---
id: GR-CHAOS-N15
category: testing/chaos
severity: medium
keywords: [Nightmare, chaos, N15, AOP Order, @Order, TransactionalEventListener, Transaction Boundary]
languages: [java, kotlin]
---

# [N15] AOP Order Problem

## DON'T (장애 원인)

@Order 미지정 시 **AOP 어드바이스 실행 순서가 비결정적**이 되어 @Transactional과 커스텀 AOP 간 예상치 못한 동작이 발생합니다.

### 위험 코드 패턴

```java
// 위험: @Order 미지정
@Aspect
public class AuditAspect {  // Order 없음 → LOWEST_PRECEDENCE
    @Around("@annotation(Audited)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        // 감사 로그 작성
    }
}

@Transactional  // 기본 Order: LOWEST_PRECEDENCE
public void saveOrder(Order order) {
    repository.save(order);
}
```

### 장애 시나리오

```
AuditAspect(@Order 없음) vs @Transactional(LOWEST_PRECEDENCE)
→ 어떤 것이 먼저 실행될지 불확실!

만약 AuditAspect가 먼저 실행되면:
1. 감사 로그 기록 (트랜잭션 외부!)
2. @Transactional 시작
3. 예외 발생 → 롤백
4. 문제: 감사 로그는 남아있음 (불일치)
```

### 장애 수치
- **AOP Execution Order**: 비결정적
- **Data Consistency**: 깨짐 (감사 로그 vs 실제 데이터)
- **Test Flakiness**: 증가 (순서에 따라 결과 다름)

---

## DO (재발 방지)

### 1. 명시적 @Order 지정

```java
@Aspect
@Order(1)  // 가장 먼저 실행 (outermost)
public class SecurityAspect { }

@Aspect
@Order(2)
public class AuditAspect { }

// @Transactional은 기본적으로 LOWEST_PRECEDENCE
// 따라서 innermost에서 실행됨 ✅
```

### 2. @TransactionalEventListener 사용 (트랜잭션 이벤트)

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    auditLog.record("Order created: " + event.getOrderId());
    // 트랜잭션 커밋 후에만 실행 → 일관성 보장 ✅
}
```

### 3. AOP 실행 순서 문서화

```java
/**
 * TraceAspect @Order(1): 가장 바깥쪽, 분산 추적 컨텍스트 설정
 * SecurityAspect @Order(2): 보안 검증
 * AuditAspect @Order(3): 감사 로그
 * @Transactional @Order(Integer.MAX_VALUE): 가장 안쪽, 트랜잭션 경계
 */
```

### 4. ArchUnit 테스트로 @Order 검증

```java
@ArchTest
static final ArchRule all_aspects_must_have_explicit_order =
    aspects().should(beAnnotatedWith(Order.class))
        .because("AOP execution order must be deterministic");
```

### 5. 코드 리뷰 체크리스트

- [ ] 모든 @Aspect에 @Order 지정
- [ ] Order 값과 이유 주석으로 명시
- [ ] 감사 로그는 @TransactionalEventListener 사용
- [ ] 새로운 Aspect 추가 시 기존 Order 확인

### 개선 수치 (테스트 결과 기준)
- **Explicit @Order Coverage**: 100% (모든 Aspect에 지정)
- **AOP Execution Order**: 일관성 유지
- **Transaction Boundary Protection**: 완료 (롤백 시 감사 로그도 롤백)
- **Test Consistency**: 100% (순서에 독립적)

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N15-aop-order-problem.md`
- `docs/05_Reports/05_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`
