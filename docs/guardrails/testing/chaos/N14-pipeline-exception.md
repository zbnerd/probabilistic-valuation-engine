---
id: GR-CHAOS-N14
category: testing/chaos
severity: high
keywords: [Nightmare, chaos, N14, Pipeline Exception, LogicExecutor, Silent Failure, Exception Swallowing]
languages: [java, kotlin]
---

# [N14] Pipeline Blackhole (Exception Swallowing)

## DON'T (장애 원인)

LogicExecutor.executeOrDefault 패턴이 **예외를 삼켜서 디버깅이 불가능**해지는 "Silent Failure" 문제를 일으킵니다.

### 위험 코드 패턴

```java
// 위험: 결제 로직에 executeOrDefault 사용
Boolean paymentSuccess = executor.executeOrDefault(
    () -> paymentGateway.process(order),  // 예외 발생!
    false,  // 기본값 반환
    context
);
// 문제: false가 반환되지만...
// - 의도적인 결제 거절인가?
// - 시스템 장애인가?
// 구분 불가능! ❌
```

### 문제점
- 예외 발생 시 로그에 기록되지 않을 수 있음
- 비즈니스 크리티컬 작업에서 실패를 숨김
- 디버깅 시 원인 파악 불가
- 사용자 경험: 거짓 성공 응답

### 장애 수치
- **Exception Logging Coverage**: 0% (예외가 삼켜짐)
- **Silent Failure Rate**: 100% (executeOrDefault 사용 시)
- **Debuggability**: 불가 (원인 추적 불가)

---

## DO (재발 방지)

### 1. LogicExecutor 패턴별 사용 가이드 준수

| 패턴 | 메서드 | 용도 | 예시 |
|------|--------|------|------|
| 예외 전파 | `execute()` | 비즈니스 로직 | 결제, 주문, 데이터 변경 |
| 기본값 반환 | `executeOrDefault()` | 조회 로직 (null OK) | 캐시 조회, 선택적 데이터 |
| 커스텀 복구 | `executeOrCatch()` | 복구 로직 필요 시 | 폴백 로직 |
| finally 보장 | `executeWithFinally()` | 자원 해제 | 파일, DB 연결 |
| 예외 변환 | `executeWithTranslation()` | 도메인 예외로 변환 | IOException → 도메인 예외 |

### 2. 비즈니스 로직에는 execute 사용

```java
// ✅ 올바른 사용: 예외 전파
void processPayment(Order order) {
    executor.execute(  // 예외 전파
        () -> paymentGateway.process(order),
        context
    );
}
```

### 3. 조회 로직에만 executeOrDefault 사용

```java
// ✅ 올바른 사용: 조회 로직
User user = executor.executeOrDefault(
    () -> userRepository.findById(id),
    null,  // 조회 실패 시 null OK
    context
);
```

### 4. 코드베이스 검증 규칙

```java
// ArchUnit 테스트: mutation 로직에서 executeOrDefault 금지
@ArchTest
static final ArchRule no_executeOrDefault_in_mutation =
    methods().that(nameContaining("save")
        .or(nameContaining("delete"))
        .or(nameContaining("update")
        .or(nameContaining("process")))
        .should(not(containWord("executeOrDefault")));
```

### 5. 코드 리뷰 체크리스트

- [ ] 비즈니스 크리티컬 작업에 `execute()` 사용
- [ ] 조회 로직에만 `executeOrDefault()` 사용
- [ ] 예외 처리 로직에 `executeOrCatch()` 사용
- [ ] 모든 예외가 로그에 기록됨

### 개선 수치 (테스트 결과 기준)
- **Exception Propagation**: 100% (execute 패턴 사용)
- **Logging Coverage**: 100% (모든 예외 기록)
- **Pattern Compliance**: 100% (코드베이스 검증 통과)
- **Debuggability**: 완전 (root cause 메시지로 원인 파악 가능)

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N14-pipeline-exception.md`
- `docs/05_Reports/04_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`
