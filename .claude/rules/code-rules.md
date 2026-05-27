# 코드 규칙 (Code Rules)

## 1. LogicExecutor 우선 정책

**module-infra, module-app, module-web** 등 LogicExecutor에 접근 가능한 모듈에서는 `try-catch` / `try-finally` 대신 **LogicExecutor** 위임을 권장.

**module-rest-controller** 등 LogicExecutor 의존이 없는 경량 모듈에서는 Spring 예외 전파(`@RestControllerAdvice` 등) 허용.

| 패턴 | 메서드 | 용도 |
|------|--------|------|
| 1 | `execute(task, context)` | 일반 실행. 예외 발생 시 로그 기록 후 상위 전파 |
| 2 | `executeVoid(task, context)` | 반환값 없는 작업 (Kotlin), `executeVoidJava` (Java-friendly) |
| 3 | `executeOrDefault(task, default, context)` | 예외 발생 시 기본값 반환 |
| 4 | `executeOrCatch(task, recovery, context)` | 예외 발생 시 복구 로직 실행 (번역된 예외 전달) |
| 5 | `executeWithFallback(task, fallback, context)` | 예외 발생 시 폴백 실행 (원본 예외 전달) |
| 6 | `executeWithFinally(task, finalizer, context)` | 자원 해제 등 finally 필요 시 |
| 7 | `executeWithTranslation(task, translator, context)` | 기술적 예외를 도메인 예외로 변환 |

**LogicExecutor 없는 모듈 예외 처리:** Spring `@RestControllerAdvice` + 예외 자연 전파. `runCatching` 허용. raw `try-catch`는 마지막 수단으로 제한.

## 2. Anti-Pattern: Lambda Hell

람다 내부 로직이 **3줄**을 초과하거나 분기문이 포함되면 즉시 **Private Method**로 추출.

```java
// Bad
return executor.execute(() -> {
    User user = repo.findById(id).orElseThrow(...);
    if (user.isActive()) { return process(user); }
    return List.of();
}, context);

// Good
return executor.execute(() -> this.processActiveUser(id), context);
```

## 3. Anti-Pattern: BigDecimal(Double) 금지

```java
// Bad - 부동소수점 오차
new BigDecimal(0.1)  // → 0.10000000000000000555...

// Good
new BigDecimal("0.1")
BigDecimal.valueOf(0.1)
```

## 4. Optional Chaining Best Practice

null 체크는 **Optional 체이닝**으로 대체.

```java
// Bad
ValueWrapper wrapper = l1.get(key);
if (wrapper != null) { return wrapper; }

// Good
return Optional.ofNullable(l1.get(key))
        .map(w -> tap(w, "L1"))
        .orElse(null);
```

**Checked Exception 구조적 분리:** Optional.orElseGet() 안에서 try-catch 금지. 예외 발생 가능한 작업은 Optional 밖에서 직접 호출.

## 5. Anti-Pattern: Error Handling & Logging

- **Catch and Ignore:** 예외를 잡고 무시 금지
- **Hardcoded Messages:** 에러 메시지는 `ErrorCode` Enum에서 관리
- **Standard Output:** `System.out.println()`, `e.printStackTrace()` 금지, `@Slf4j` 사용
- **Test Output:** 테스트 코드에서 `System.out` 사용 금지. 느려짐. Assertion 메시지로 충분.
- **Raw Thread:** `new Thread()`, `Future` 직접 사용 금지
