---
id: GR-REFACTOR-005
category: architecture/refactor
severity: warning
keywords: [controller, duplication, completablefuture, response-entity, async]
languages: [java, kotlin]
---

# Controller 비동기 응답 패턴 중복

## DON'T (위반 사항/장애 원인)

### 중복 코드
```java
// V2, V3, V4 모두 동일한 패턴 반복 (5회)
public CompletableFuture<ResponseEntity<TotalExpectationResponse>> calculateTotalCost(
    @PathVariable String userIgn) {
  return equipmentService.calculateTotalExpectationAsync(userIgn)
      .thenApply(ResponseEntity::ok);
}
```

### 위험 요소
- **유지보수 비용 증가**: 응답 형식 변경 시 5개 메서드 수정 필요
- **일관성 위험**: 일부 Controller만 예외 처리 추가 시 불일치
- **LogicExecutor 누락**: Service에서 예외 처리하지만 Controller에서도 래핑

### 수치
- 중복 횟수: 5회 (V2: 2회, V3: 1회, V4: 2회)

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 1. 공통 유틸리티 클래스 생성
public class AsyncResponseUtils {

    public static <T> CompletableFuture<ResponseEntity<T>> ok(
        CompletableFuture<T> future) {
        return future.thenApply(ResponseEntity::ok);
    }

    public static <T> CompletableFuture<ResponseEntity<T>> okWithGzip(
        CompletableFuture<T> future,
        boolean acceptsGzip,
        Function<T, byte[]> gzipConverter
    ) {
        if (acceptsGzip) {
            return future.thenApply(data -> buildGzipResponse(gzipConverter.apply(data)));
        }
        return ok(future);
    }
}

// 2. Controller에서 적용
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<TotalExpectationResponse>> calculateTotalCost(
    @PathVariable String userIgn) {
    return AsyncResponseUtils.ok(
        equipmentService.calculateTotalExpectationAsync(userIgn));
}
```

### 개선 수치 (After)
- 코드 라인 수: 15 → 5 (66% 감소)
- 유지보수 포인트: 5개 → 1개

### 핵심 원칙
1. **공통 유틸리티 추출**: 반복되는 응답 변환 로직을 유틸리티로 분리
2. **메서드 참조 사용**: `thenApply(ResponseEntity::ok)`로 간결하게 표현
3. **GZIP 처리 통합**: V4의 GZIP 헤더 확인 로직도 유틸리티에 포함

## 출처
- 문서: [docs/05_Reports/05_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/05_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P0 (심각한 중복)
