---
id: GR-CHAOS-N18
category: testing/chaos
severity: medium
keywords: [Nightmare, chaos, N18, Deep Paging, OFFSET, Cursor Pagination, Keyset Pagination]
languages: [java, kotlin]
---

# [N18] Deep Paging Abyss

## DON'T (장애 원인)

OFFSET 기반 페이징에서 **깊은 페이지(OFFSET 100,000+)로 갈수록 성능이 급격히 저하**됩니다.

### 위험 코드 패턴

```java
// 위험: OFFSET 기반 페이징
@GetMapping("/items")
public Page<Item> getItems(@RequestParam int page) {
    // page=10000 → OFFSET 100000 → 1,000,010개 행 스캔 ❌
    return itemRepository.findAll(PageRequest.of(page, 10));
}
```

### 장애 시나리오

```sql
SELECT * FROM items
ORDER BY id
LIMIT 10 OFFSET 1000000;

-- MySQL 동작:
-- 1. 1,000,010개 행 스캔
-- 2. 처음 1,000,000개 버림
-- 3. 10개 반환
-- → 대부분의 작업이 낭비!
```

### 성능 저하 그래프
```
응답 시간
    │
    │                              ╱
100ms │                         ╱
    │                      ╱
 10ms │               ╱
    │          ╱
  1ms │     ─
    │─────────────────────────────
        1     100    1000   10000  (페이지)
```

### 장애 수치
- **Page 1 Response**: 1ms
- **Page 1000 Response**: 50ms
- **Page 10000 Response**: 500ms
- **Page 100000 Response**: 5000ms+ (타임아웃 위험)

---

## DO (재발 방지)

### 1. Cursor-based Pagination (Keyset)

```sql
-- 첫 페이지
SELECT * FROM items ORDER BY id LIMIT 10;

-- 다음 페이지 (마지막 id = 123)
SELECT * FROM items
WHERE id > 123  -- 인덱스 사용!
ORDER BY id
LIMIT 10;
```

### 2. Spring Data 구현

```java
// Offset Pagination (기존) - 깊은 페이지에서 느림
Page<Item> findAll(Pageable pageable);

// Cursor Pagination - 일관된 성능
@Query("SELECT i FROM Item i WHERE i.id > :lastId ORDER BY i.id")
List<Item> findByIdGreaterThan(@Param("lastId") Long lastId, Pageable pageable);
```

### 3. API에 최대 페이지 제한 추가

```java
@GetMapping("/items")
public Page<Item> getItems(@RequestParam(defaultValue = "0") int page) {
    if (page >= 100) {
        throw new IllegalArgumentException("Page limit exceeded (max: 100)");
    }
    return itemRepository.findAll(PageRequest.of(page, 10));
}
```

### 4. 무한 스크롤 UI에 Cursor Pagination 적용

```java
@RestController
public class ItemController {
    @GetMapping("/items/cursor")
public CursorResponse<Item> getItemsCursor(@RequestParam(required = false) Long lastId) {
        List<Item> items = lastId == null
            ? itemRepository.findFirst10ByOrderByIdAsc()
            : itemRepository.findByIdGreaterThanOrderByIdAsc(lastId, Pageable.ofSize(10));

        Long nextLastId = items.isEmpty() ? null : items.get(items.size() - 1).getId();
        return new CursorResponse<>(items, nextLastId);
    }
}
```

### 5. 대량 데이터 조회 시 스트리밍/Export

```java
@GetMapping("/items/export")
public void exportItems(HttpServletResponse response) {
    response.setContentType("text/csv");

    try (Writer writer = response.getWriter()) {
        // Streaming으로 대량 데이터 Export
        itemRepository.streamAllBy()
            .forEach(item -> writeCsv(writer, item));
    }
}
```

### 성능 비교

| 방식 | 페이지 1 | 페이지 1000 | 페이지 100000 | 복잡도 |
|------|----------|-------------|---------------|--------|
| OFFSET | 1ms | 50ms | 5000ms | O(n) |
| Cursor | 1ms | 1ms | 1ms | O(log n) |

### 개선 수치 (테스트 결과 기준)
- **First Page Response**: < 10ms ✅
- **Last Page Response (Cursor)**: < 10ms ✅
- **Consistent Performance**: O(log n) 유지 ✅
- **EXPLAIN Type**: index scan 확인 ✅

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N18-deep-paging.md`
- `docs/05_Reports/05_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`
