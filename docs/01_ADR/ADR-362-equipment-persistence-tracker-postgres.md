# ADR-362: EquipmentPersistenceTracker PostgreSQL Migration

## 상태 (Status)

**수락됨 (Accepted)**

## 컨텍스트 (Context)

### 현재 상태

기존 EquipmentPersistenceTracker는 ConcurrentHashMap을 사용하여 인스턴스 로컬에 데이터를 저장합니다:

```java
// EquipmentPersistenceTracker.java
public class EquipmentPersistenceTracker {
    private final ConcurrentHashMap<String, String> equipmentPersistenceMap = new ConcurrentHashMap<>();

    public void save(String uuid, String value) {
        equipmentPersistenceMap.put(uuid, value);
    }
}
```

### 문제 정의

1. **Crash Loss**: 인스턴스 종료 시 데이터 영구 손실
2. **Scale-out 한계**: 다중 인스턴스 간 데이터 공지 불가
3. **Audit Trail**: 변경 이력 추적 불가
4. **Recovery 복구**: 장애 발생 시 복구 불가

## 결정 (Decision)

### 1. Port/Adapter 패턴 도입

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/PersistenceTrackerPort.kt
interface PersistenceTrackerPort {
    fun save(uuid: String, value: String)
    fun find(uuid: String): String?
    @PostConstruct
    fun recover(): Unit
}
```

### 2. PostgreSQL 어댑터 구현

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/PostgresPersistenceTrackerAdapter.kt
@Repository
class PostgresPersistenceTrackerAdapter(
    private val jdbcTemplate: JdbcTemplate
) : PersistenceTrackerPort {

    override fun save(uuid: String, value: String) {
        jdbcTemplate.update(
            """
            INSERT INTO equipment_persistence (uuid, value, created_at)
            VALUES (?, ?, NOW())
            ON CONFLICT (uuid) DO UPDATE SET
                value = EXCLUDED.value,
                updated_at = NOW()
            """,
            uuid, value
        )
    }

    @PostConstruct
    override fun recover() {
        // L1 캐시로 복구
        jdbcTemplate.query(
            "SELECT uuid, value FROM equipment_persistence",
            rs -> {
                val uuid = rs.getString("uuid")
                val value = rs.getString("value")
                equipmentPersistenceMap.put(uuid, value)
                return@query null
            }
        )
    }
}
```

### 3. Layering 준수 구조

```
module-app (Java)
    ↓
module-core (Kotlin) ← PersistenceTrackerPort
    ↓
module-infra (Kotlin) ← PostgresPersistenceTrackerAdapter
```

## 결과 (Consequences)

### 긍정적 영향

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| Crash Recovery | 불가능 | DB 복구 |
| Audit Trail | 없음 | DB 이력 |
| Scale-out | 단일 인스턴스 | 다중 인스턴스 |

### 부정적 영향

| 항목 | 영향 | 완화 방안 |
|------|------|---------|
| 성능 오버헤드 | DB Write 지연 | Buffer + Batch 처리 |
| 복잡성 증가 | Layer 추가 | Port/Adapter 패턴 |

### 거부된 옵션 (Rejected Options)

1. **UNLOGGED Table**: PostgreSQL 장애 시 데이터 손실 → 기능 무효
2. **JdbcTemplate in module-app**: Layering 위반 → Port/Adapter 적용
3. **In-Memory Only**: Scale-out 지원 불가 → 근본적 문제 해결

### 마이그레이션 경로

1. **Phase 1**: PersistenceTrackerPort 인터페이스 생성
2. **Phase 2**: PostgresPersistenceTrackerAdapter 구현
3. **Phase 3**: Flyway V103 마이그레이션 스크립트
4. **Phase 4**: EquipmentPersistenceTracker 리팩토링

## 이력 (History)

| 날짜 | 변경 내용 | 작성자 |
|------|---------|--------|
| 2026-03-29 | 초안 작성 | Claude (Haiku 4.5) |

## 참조 (References)

### 관련 문서
- [Port/Adapter Pattern](https://alistair.cockburn.us/hexagonal-architecture/)
- [Flyway Database Migration](https://flywaydb.org/documentation/)

### 구현 파일
- `module-core/src/main/kotlin/maple/expectation/core/port/out/PersistenceTrackerPort.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/PostgresPersistenceTrackerAdapter.kt`
- `module-app/src/main/java/maple/expectation/application/service/shutdown/EquipmentPersistenceTracker.java`
- `db/migration/V103__equipment_persistence_tracker.sql`

### 관련 Issue
- Issue #633: Equipment Persistence Tracker PostgreSQL Migration