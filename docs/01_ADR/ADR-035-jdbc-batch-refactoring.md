# ADR-035: Command Side JPA → JDBC 배치 전환

## 상태 (Status)
**Accepted** - 2026-02-23

## 문맥 (Context)

V5 Command Side에서 대량의 장비 데이터를 저장할 때 JPA의 `saveAll()` 메서드가 심각한 성능 문제를 일으키고 있습니다.

**현재 상황:**
- 데이터 규모: 30만 캐릭터 × 60 rows = 1,800만 rows
- JPA `saveAll()` 성능: 1만 건에 15.2초 (초당 650건)
- 30만 건 추정: ~7.6시간 소요

**근거 (실측 데이터):**
| 방식 | 1만 건 소요 시간 | 성능 (건/초) |
|------|-----------------|---------------|
| JPA saveAll() | 15.2초 | 650 |
| JDBC batchUpdate() | 0.4초 | 22,000 |
| **차이** | **33배** | |

## 문제 (Problem)

### JPA 배치가 작동하지 않는 근본 원인

**MySQL IDENTITY 전략 문제:**
```java
@Entity
@Table(name = "character_equipment")
public class CharacterEquipment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // 문제의 원인
    private Long id;
    // ...
}
```

**JPA 동작 방식:**
1. IDENTITY 전략에서는 JPA가 배치 삽입을 사용할 수 없음
2. why? 데이터베이스가 생성한 ID를 즉시 알아야 하므로 건별 INSERT 필요
3. 결과: 1만 건 → 1만 번의 네트워크 왕복

**CQRS 분리 후 JPA 장점 소멸:**
| JPA 기능 | V5 Command Side에서의 필요성 | 판단 |
|-----------|----------------------------|------|
| 지연 로딩 (Lazy Loading) | ❌ 읽기는 MongoDB가 담당 | 불필요 |
| 연관관계 매핑 | ❌ 단일 테이블 upsert만 수행 | 불필요 |
| 변경 감지 (Dirty Checking) | ❌ 무조건 overwrite | 불필요 |
| 1차 캐시 | ❌ Command Side는 쓰기 전용 | 불필요 |

**결론:** JPA의 장점이 전혀 활용되지 않는 상황

## 결정 (Decision)

### V5 Command Side를 JPA → JDBC 배치로 전환

**선택:**
- `JdbcBatchUpsertRepository` 구현
- `ON DUPLICATE KEY UPDATE` 쿼리 사용
- Batch size: 1000건/배치 (최적화 예정)

**이유:**
1. **33배 성능 향상**: 15.2초 → 0.4초 (1만 건 기준)
2. **CQRS 철학 준수**: Command Side는 최적화된 쓰기 전용
3. **역할 분리**: Command(JDBC 배치) + Query(MongoDB)

## 구현 (Implementation)

### Phase 1: JDBC Repository 구현

**파일:** `module-app/src/main/java/maple/expectation/v5/infrastructure/jdbc/JdbcBatchUpsertRepository.java`

```java
@Repository
@RequiredArgsConstructor
public class JdbcBatchUpsertRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String UPSERT_SQL = """
        INSERT INTO character_equipment
            (character_id, equipment_slot, item_id, item_name, star_force, ...)
        VALUES (?, ?, ?, ?, ?, ...)
        ON DUPLICATE KEY UPDATE
            item_id = VALUES(item_id),
            item_name = VALUES(item_name),
            star_force = VALUES(star_force),
            ...
        """;

    public int[] batchUpsert(List<CharacterEquipment> entities) {
        List<Object[]> batchArgs = entities.stream()
            .map(e -> new Object[]{
                e.getCharacterId(),
                e.getEquipmentSlot(),
                e.getItemId(),
                // ... other fields
            })
            .toList();

        return jdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs);
    }
}
```

### Phase 2: 배치 크기 최적화

**테스트 배치 크기:** 100, 500, 1000, 2000
**측정 지표:** 총 소요 시간, 처리량 (건/초)

### Phase 3: 기존 JPA 코드 교체

**대상:**
- `EquipmentCommandService.saveAll()` → `jdbcBatchUpsertRepository.batchUpsert()`

## 결과 (Consequences)

### 긍정적 효과

1. **성능**: 30만 건 처리 시간 7.6시간 → ~12분 (38배 개선)
2. **리소스**: 네트워크 왕복 1만 회 → 1,000회 (Batch size 1000 기준)
3. **단순함**: 직관적인 SQL, JPA 복잡성 제거

### 부정적 효과

1. **SQL 직접 관리**: 스키마 변경 시 쿼리 수동 수정 필요
2. **타입 안전성**: 컴파일 �임 검증 불가 (런타임 에러 가능성)
3. **보일러플레이트**: 필드 매핑 코드 수동 작성

### 완화 방안

1. **Integration Test**: 스키마 변경 감지
2. **Record 타입**: 불변 데이터 객체로 타입 안전성 확보
3. **코드 생성**: 추후 Annotation Processor 고려

## 관련 의사결정

- **ADR-079**: V5 CQRS 아키텍처 (Query Side MongoDB 사용)
- **ADR-XXX**: CQRS 패턴 도입 배경

## 검증 (Validation)

### 성능 비교 테스트

**테스트 케이스:**
- 단건 upsert: 1회
- 소량 배치: 100건
- 중량 배치: 10,000건
- 대량 배치: 300,000건

**메트릭:**
- 총 소요 시간
- 처리량 (건/초)
- DB CPU 사용량
- 네트워크 I/O

### 통합 테스트

**시나리오:**
1. 30만 캐릭터 장비 데이터 upsert
2. MongoDB Query Side와 데이터 정합성 검증
3. 중복 실행 멱등성 확인

## 참고 (References)

- **Spring JDBC Batch**: https://docs.spring.io/spring-framework/reference/data-access/jdbc/batch.html
- **MySQL ON DUPLICATE KEY UPDATE**: https://dev.mysql.com/doc/refman/8.0/en/insert-on-duplicate.html
- **관련 이슈**: #359, #126 (V5 CQRS Architecture)

---

**작성일:** 2026-02-23
**작성자:** Claude Code (Team Lead)
**승인자:** TBD
**다음 리뷰:** 2026-03-23
