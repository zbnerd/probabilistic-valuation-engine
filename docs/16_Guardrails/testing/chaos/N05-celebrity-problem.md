---
id: GR-NIGHTMARE-N05
category: testing/chaos
severity: critical
keywords: [jdbc batch, batch misuse, performance, network roundtrip]
languages: [java, kotlin]
---

# N05: Celebrity Problem (JDBC Batch Misuse)

## DON'T (안티패턴)

```java
// Java - 일반 INSERT 반복 (Batch 무효)
@Transactional
public void bulkInsert(List<Item> items) {
    for (Item item : items) {
        // 각 INSERT가 개별 네트워크 왕복!
        repository.save(item);
    }
}
```

```kotlin
// Kotlin - 일반 INSERT 반복 (Batch 무효)
@Transactional
fun bulkInsert(items: List<Item>) {
    for (item in items) {
        // 각 INSERT가 개별 네트워크 왕복!
        repository.save(item)
    }
}
```

**장애 수치 (Before):**
- 10,000건 INSERT 시간: ~120초
- 네트워크 왕복: 10,000회
- DB CPU 사용량: 80%+
- Connection 점유 시간: 2분+

## DO (베스트 프랙티스)

```java
// Java - JDBC Batch 적용
@Transactional
public void bulkInsert(List<Item> items) {
    jdbcTemplate.batchUpdate(
        "INSERT INTO items (name, value) VALUES (?, ?)",
        new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Item item = items.get(i);
                ps.setString(1, item.getName());
                ps.setInt(2, item.getValue());
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        }
    );
}
```

```kotlin
// Kotlin - JDBC Batch 적용
@Transactional
fun bulkInsert(items: List<Item>) {
    jdbcTemplate.batchUpdate(
        "INSERT INTO items (name, value) VALUES (?, ?)"
    ) { ps, item ->
        ps.setString(1, item.name)
        ps.setInt(2, item.value)
    }
}
```

**개선 수치 (After):**
- 10,000건 INSERT 시간: ~3초 (40x 빠름)
- 네트워크 왕복: 100회 (batch size 100)
- DB CPU 사용량: 30%
- Connection 점유 시간: 3초

## 핵심 원칙

1. **JDBC Batch 사용**: `batchUpdate()`로 대량 insert 최적화
2. **Batch Size 설정**: 100-1000건 사이 조정
3. **rewriteBatchedStatements=true**: MySQL 옵션으로 추가 최적화
4. **SaveAll 주의**: JPA saveAll()은 실제 Batch가 아닐 수 있음

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N05-celebrity-problem.md`
- Nightmare Test N05: Celebrity Problem
- Test Class: `CelebrityProblemNightmareTest`
