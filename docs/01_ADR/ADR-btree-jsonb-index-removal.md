# ADR: idx_valuation_presets btree(JSONB) 인덱스 제거

**상태**: Approved
**날짜**: 2026-04-19
**영향**: character_valuation_views 테이블, 기대값 계산 파이프라인 전체

---

## 배경

10K IGN 부하 테스트 중 **96% 실패율** 발생. 단일 인덱스가 연쇄 붕괴의 근본 원인으로 확인됨.

### 에러

```
ERROR: index row size 2928 exceeds btree version 4 maximum 2704
for index "idx_valuation_presets"
Detail: Values larger than 1/3 of a buffer page cannot be indexed.
```

### 연쇄 붕괴 구조

```
[idx_valuation_presets btree(JSONB) row size 초과 (2928 > 2704)]
        ↓
[character_valuation_views INSERT 실패]
        ↓
[ExpectationCacheCoordinator.executeCalculatorWithAdmission() 실패]
        ↓
[1차: EquipmentDataProcessingException "Calculation failed with admission control"]
        ↓
[캐시에 실패/부분 데이터 저장됨]
        ↓
[2차 재시도: GZIP 압축 해제 실패 (손상된 캐시 데이터 읽기)]
        ↓
[max retries 초과 → DLQ]
        ↓
[시스템 전체 96% 실패율]
```

---

## 원인 분석

PostgreSQL의 btree 인덱스는 단일 인덱스 row 크기가 **2704바이트**(버퍼 페이지의 1/3)를 초과할 수 없습니다.

`presets` 컬럼은 JSONB 타입이며, 일부 캐릭터의 프리셋 데이터가 이 한계를 초과합니다.

### 왜 btree가 부적절했나

| 항목 | 설명 |
|------|------|
| presets 용도 | 대용량 JSONB 데이터 — 검색 조건으로 사용되지 않음 |
| btree 특성 | 등호/범위 비교에 적합 — JSONB 전체를 인덱싱하는 건 설계 오류 |
| 올바른 대안 | JSONB 키/값 검색이 필요하면 GIN 인덱스 사용 |
| 실제 필요성 | presets로 검색하는 쿼리가 없으므로 **인덱스 자체가 불필요** |

---

## 결정

**인덱스 제거.** JPA 엔티티와 DB 모두에서 완전히 삭제.

### 적용 내역

```sql
-- DB에서 제거
DROP INDEX IF EXISTS idx_valuation_presets;
```

```kotlin
// CharacterValuationEntity.kt에서 제거
// Before:
indexes = [
    Index(name = "idx_valuation_user_ign", columnList = "user_ign"),
    Index(name = "idx_valuation_calculated", columnList = "calculated_at DESC"),
    Index(name = "idx_valuation_presets", columnList = "presets"),  // ← 제거
],

// After:
indexes = [
    Index(name = "idx_valuation_user_ign", columnList = "user_ign"),
    Index(name = "idx_valuation_calculated", columnList = "calculated_at DESC"),
],
```

---

## 결과

| 항목 | 수정 전 | 수정 후 |
|------|---------|---------|
| Cache HIT (200) | 0 | 1,036 |
| Queued (202) | 1,001 | 1,058 |
| Worker Success | 65 | 증가 |
| Worker DLQ | 8,974 | 감소 |
| Throughput | 858 req/s | 945 req/s |

---

## 교훈

1. **JSONB 컬럼에 btree 인덱스 금지** — 크기 예측 불가, 2704바이트 제한 위반 위험
2. **인덱스는 실제 쿼리 패턴에만 생성** — "혹시 모르니까" 인덱스는 시스템 전체를 다운시킬 수 있음
3. **Hibernate ddl-auto: update는 엔티티 정의를 그대로 DB에 반영** — 엔티티의 잘못된 인덱스 정의가 프로덕션 장애로 이어짐
