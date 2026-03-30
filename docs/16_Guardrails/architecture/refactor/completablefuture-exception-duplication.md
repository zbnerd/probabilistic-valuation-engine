---
id: GR-REFACTOR-009
category: architecture/refactor
severity: warning
keywords: [completablefuture, exception, duplication, timeout, unwrap]
languages: [java, kotlin]
---

# CompletableFuture 예외 처리 중복

## DON'T (위반 사항/장애 원인)

### 중복 코드
```java
// 14개 파일에서 동일한 패턴
.orTimeout(LEADER_DEADLINE_SECONDS, TimeUnit.SECONDS)
.exceptionally(e -> handleAsyncException(e, userIgn))

// 예외 핸들러 로직도 중복
private TotalExpectationResponse handleAsyncException(Throwable e, String userIgn) {
    Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;

    if (cause instanceof TimeoutException) {
        throw new ExpectationCalculationUnavailableException(userIgn, cause);
    }
    if (cause instanceof RuntimeException re) {
        throw re;
    }
    throw new EquipmentDataProcessingException(
        String.format("Async expectation calculation failed for: %s", userIgn), cause);
}
```

### 위험 요소
- **CompletionException unwrap 패턴 반복**: 14개 파일
- **Timeout → 503 변환 로직 중복**: 3개 Service
- **예외 변환 정책 불일치 위험**: 일부 파일에서 누락 가능

### 수치
- 중복 파일: 14개
- 중복 코드: 42라인

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 1. CompletableFuture 확장 유틸리티
public class AsyncUtils {

    public static <T> CompletableFuture<T> withTimeout(
        Supplier<CompletableFuture<T>> futureSupplier,
        long timeout,
        TimeUnit unit,
        String operationName,
        String identifier
    ) {
        return futureSupplier.get()
            .orTimeout(timeout, unit)
            .exceptionally(e -> wrapException(e, operationName, identifier));
    }

    private static <T> T wrapException(Throwable e, String operation, String identifier) {
        Throwable cause = e instanceof CompletionException ? e.getCause() : e;

        if (cause instanceof TimeoutException) {
            throw new ApiTimeoutException(operation, identifier, cause);
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        throw new AsyncOperationException(operation, identifier, cause);
    }
}

// 2. Service에서 적용
public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationAsync(String userIgn) {
    return AsyncUtils.withTimeout(
        () -> doCalculation(userIgn),
        LEADER_DEADLINE_SECONDS,
        TimeUnit.SECONDS,
        "ExpectationCalculation",
        userIgn
    );
}
```

### 개선 수치 (After)
- 예외 처리 코드: 42라인 → 5라인 (88% 감소)
- 일관된 예외 처리 정책 보장
- 테스트 가능성 향상

### 핵심 원칙
1. **공통 유틸리티**: 예외 래핑 로직을 AsyncUtils로 중앙화
2. **CompletionException unwrap**: 표준 패턴으로 unwrap
3. **일관된 예외 타입**: ApiTimeoutException, AsyncOperationException 사용

## 출처
- 문서: [docs/05_Reports/05_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/05_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P0 (심각한 중복)
