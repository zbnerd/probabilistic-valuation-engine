# V6 Read Path: userIGN 기반 Read Model 설계

- Status: Proposed
- Date: 2026-05-16
- Owner: zbnerd

## Goal

V6 read path가 userIgn으로 직접 batch 조회하여 ocid resolve 단계를 제거한다.
신규 캐릭터, 이름 변경 캐릭터도 self-healing으로 처리한다.

## Architecture

### 1. Read Model 스키마 변경

`character_equipment_read_model`에 `user_ign` 컬럼 추가 + 조회 인덱스.

```sql
-- Step 1: 컬럼 추가 (nullable로 시작)
ALTER TABLE character_equipment_read_model
  ADD COLUMN user_ign TEXT;

-- Step 2: Backfill — game_character에서 userIgn 역조회
UPDATE character_equipment_read_model r
SET user_ign = gc.user_ign
FROM game_character gc
WHERE r.ocid = gc.ocid;

-- Step 3: NOT NULL + 인덱스
ALTER TABLE character_equipment_read_model
  ALTER COLUMN user_ign SET NOT NULL;

CREATE INDEX idx_equipment_read_model_user_ign_preset
  ON character_equipment_read_model (user_ign, preset_no);
```

- PK: 기존 read_key 유지 (변경 없음)
- UNIQUE(ocid, preset_no): integrity 유지
- INDEX(user_ign, preset_no): V6 batch 조회용

### 2. V6 Read Path 쿼리 흐름 (3-layer miss handling)

```
userIgn batch [아델, 강은호, 진격캐넌]
  ↓
① read_model WHERE user_ign IN (batch)
  ↓ hit → 200 (fast path)
  ↓ miss
② game_character WHERE user_ign IN (miss_list)
  ↓ exists → PGMQ high-priority queue에 job enqueue → 202 (cold known)
  ↓ not exists
③ External OCID lookup (Nexon API: GET /id?character_name={userIgn})
  ↓ exists → game_character upsert + PGMQ high-priority queue에 job enqueue → 202 (new character)
  ↓ not exists → 404 (invalid)
```

### 3. Synchronizer 변경

현재: 결과 파일(ocid) → `ocid:presetNo`로 read_model 저장
변경: 결과 파일(ocid) → `game_character` JOIN으로 userIgn 획득 → `userIgn` 컬럼 포함하여 저장

- `EquipmentDocumentBuilder`: `userIgn` 필드 추가
- `EquipmentDocumentPreparer`: `read_key`를 `ocid:presetNo` 그대로 유지하되 `user_ign` 컬럼 추가
- `EquipmentReadModelRepository`: `user_ign` 포함하여 bulk upsert

### 4. Daily Sync (이름 변경 대응)

```
External API (하루 1회 스케줄러)
  → 전체 userIgn→ocid 수집 (Nexon API)
  → Kafka 이벤트 발행

Synchronizer (consumer)
  → 기존 read_model의 userIgn과 비교
  → 이름 변경 감지 시 read_model의 user_ign 업데이트
```

## Trade-offs

### Sensitivity
- Nexon API rate limit (daily sync 전체 조회)
- read model↔game_character JOIN 지연 (synchronizer write path)
- 캐릭터 이름 변경 빈도

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| userIgn 조회 인덱스 | V6 read path ocid resolve 제거 (1쿼리) | read model에 userIgn 동기화 필요 |
| 3-layer miss handling | 신규/이름변경 캐릭터 자동 복구 | miss 시 external API 호출 latency |
| PK를 ocid 기반으로 유지 | 이름 변경 안정성 | userIgn lookup은 index에 의존 |

### Risk
- Daily sync 실패 시 이름 변경 캐릭터가 old userIgn으로 조회됨 (self-healing miss flow로 복구 가능)
- Synchronizer에 game_character JOIN 추가로 write path latency 증가

### Non-Risk
- read model PK 변경 없음 → 기존 write path 호환성 유지
- ocid unique constraint 유지 → 데이터 integrity 보장

## Scope

Phase 2에서 구현. Phase 1 (buffering)은 이미 완료.

### Phase 2a: Read Model 스키마 + Synchronizer 변경
- ALTER TABLE (user_ign 컬럼 + 인덱스)
- Synchronizer: game_character JOIN으로 userIgn 획득 후 저장
- V6 BatchReadScheduler: userIgn batch 조회 로직

### Phase 2b: 3-layer Miss Handling
- game_character fallback lookup (batch WHERE user_ign IN)
- External OCID lookup fallback (Nexon API, rate-limited)
- PGMQ high-priority queue에 job enqueue (기존 `CalculationQueuePort` 재사용)

### Phase 2c: Daily Sync
- External API: daily scheduler + Kafka producer
- Synchronizer: Kafka consumer + userIgn 업데이트

## Summary

> read model에 userIgn 조회 인덱스를 추가하고, 3-layer miss handling (read_model → game_character → external OCID)로 self-healing read path를 구성한다.
