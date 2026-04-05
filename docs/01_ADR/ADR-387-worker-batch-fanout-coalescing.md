# ADR-387: Worker Batch Fan-out + Coalescing Pre-warm

**Status**: Accepted
**Date**: 2026-04-05
**Context**: ExpectationCalcWorker, ExpectationCalcLowWorker, PgmqWorker

## Context

PR #699에서 Worker RPS를 2.75→98.5까지 개선했으나, 배치 내 **동일 OCID에 대한 중복 API 호출**이 병목으로 확인됨.

### 문제

배치 91개 메시지가 각각 독립적으로 OCID 해석 + 장비 fetch → 동일 OCID가 N번 fetch됨.
Nexon API 호출 수: 91 messages × 3 API calls = 273 calls (unique OCID는 ~50개).

### 기존 인프라

V4에 이미 구축된 micro-batch coalescing 인프라:
- `AdaptiveMicroBatchUserService`: semaphore(10) 기반 Fast/Batch Lane 분기
- `NexonEquipmentMicroBatchAdapter`: `preFetchByOcid()` → coalescing 트리거
- `NexonFanOutBatchLoader`: Batch Lane에서 병렬 batch fetch
- `CharacterOcidPort.resolveOcids()`: IN clause batch OCID resolve

## Decision

`PgmqWorker`에 `preWarmBatch()` 훅을 추가하고, `ExpectationCalcWorker`/`ExpectationCalcLowWorker`에서 override하여 배치 처리 전 OCID dedup + 장비 캐시 pre-warm 수행.

### 설계

```
processMessages() {
    messages = pgmq.read(batch)
    preWarmBatch(messages)     // ← 신규 훅
    messages.forEach { process(it) }  // 기존 로직 (캐시 warm 상태)
}
```

#### preWarmBatch() 흐름

1. 배치 내 unique IGN 추출 (`messages.map { it.payload.userIgn }.toSet()`)
2. Batch OCID resolve (`CharacterOcidPort.resolveOcids(igns)` → 1 DB query)
3. **Concurrent** `EquipmentFanOutPort.preFetchByOcid(ocid)` submission
   - 동시 submit 필수: 순차 호출 시 semaphore 항상 available → 전부 Fast Lane → coalescing 미발생
   - Virtual Thread에서 동시 submit → semaphore(10) 초과 → Batch Lane routing → `NexonFanOutBatchLoader.load()` batch fetch
4. `orTimeout(15s)` + `.handle { _, _ -> }` → best-effort

### Best-effort 정책

- pre-warm 실패해도 메시지 처리에 영향 없음
- `resolveOcids` 누락 IGN (신규 캐릭터) → `process()`에서 정상 경로로 처리
- pre-warm 타임아웃 → `process()`에서 캐시 miss → API 호출 fallback

## Consequences

### 긍정
- Nexon API 호출 수: 273 → ~50 (82% 감소 예상)
- 기존 V4 micro-batch 인프라 100% 재사용 (중복 구현 없음)
- `PgmqWorker` 훅은 open → 다른 Worker도 필요시 override 가능

### 부정
- pre-warm으로 인한 배치 처리 시작 지연 (~1-3초, 타임아웃 15s)
- 캐시 TTL 만료 시 pre-warm 무효 가능 (TTL 설정에 따라)

### 변경 파일

| 파일 | 변경 |
|------|------|
| `PgmqWorker.kt` | `preWarmBatch()` open 훅 추가 + `processMessages()`에서 호출 |
| `ExpectationCalcWorker.kt` | `preWarmBatch()` override + `CharacterOcidPort`, `EquipmentFanOutPort` 주입 |
| `ExpectationCalcLowWorker.kt` | 동일 패턴 적용 |

### 재사용 컴포넌트 (변경 없음)

- `CharacterOcidPort.resolveOcids(Set<String>): Map<String, String>`
- `EquipmentFanOutPort.preFetchByOcid(ocid: String): Boolean`
- `NexonEquipmentMicroBatchAdapter` → `AdaptiveMicroBatchUserService` → `NexonFanOutBatchLoader`
