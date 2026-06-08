# Like Port Merge Design (6 → 2)

- Date: 2026-06-06
- Owner: TBD
- Related: #897, ADR-391

---

## 1. Background / Problem

### Background

ADR-391 + #897 audit classified 49 outbound ports. Like-related ports (6) were flagged as having overlapping responsibilities across three concerns: data fetch, buffer, sync. Specification #1154 §5 proposed merging them into 2 ports.

### Problem

Current 6 ports:

| Port | Concern | Status |
|------|---------|--------|
| `LikeAtomicFetchStrategy` | atomic fetch (Redis) | Active seam |
| `LikeBufferStrategy` | buffer push + size | Active seam |
| `LikeRelationBufferStrategy` | relation buffer (Redis) | Active seam |
| `LikeSyncPort` | flush L2 → persistence | Active seam |
| `LikeRelationSyncPort` | relation sync (Redis → MySQL) | Active seam |
| `LikeEventPublisher` | publish LikeEvent | Active seam |

3 concerns split across 6 ports. Caller must know which port to inject. Adapter count = 6 (~5 in `module-infra`, 1 in test).

### Goal

Merge into 2 ports with cohesive concerns: `LikeReadPort` (fetch + buffer control) and `LikeSyncPort` (sync + publish). Remove the 4 deprecated ports in the same PR.

---

## 2. Decision

> Replace 6 Like-related outbound ports with 2 (`LikeReadPort`, `LikeSyncPort`). Adapters consolidate. `LikeSyncPort` is the new owner of the existing name; `LikeReadPort` is a new name.

```text
// New: data fetch + buffer manipulation (3 → 1)
interface LikeReadPort {
    fun fetchAtomic(userId: String): Optional<LikeData>
    fun bufferPush(event: LikeEvent): CompletableFuture<Void>
    fun bufferSize(): Int
}

// New: sync + event publish (3 → 1)
interface LikeSyncPort {
    fun syncRelation(fromUser: String, toUser: String): Result<Unit>
    fun publish(event: LikeEvent): Result<Unit>
}
```

Removed ports: `LikeAtomicFetchStrategy`, `LikeBufferStrategy`, `LikeRelationBufferStrategy`, `LikeRelationSyncPort`, `LikeEventPublisher`. `LikeSyncPort` is the surviving port (replaces its old flush semantics with the new sync+publish semantics).

---

## 3. Adapter Mapping

| Old Adapter | New Adapter | Location |
|------------|-------------|----------|
| `RedisLikeAtomicFetchAdapter` | `LikeReadPortAdapter` (read methods) | `module-infra/.../like/` |
| `RedisLikeBufferAdapter` | `LikeReadPortAdapter` (buffer methods) | same file |
| `RedisLikeRelationBufferAdapter` | (delete — buffer merged into read) | removed |
| `LikeSyncExecutor` (flush) | `LikeSyncPortAdapter` (sync methods) | `module-infra/.../like/` |
| `RedisLikeRelationSyncAdapter` | `LikeSyncPortAdapter` (relation sync) | same file |
| `KafkaLikeEventPublisher` | `LikeSyncPortAdapter` (publish) | same file |

3 adapter files instead of 6.

---

## 4. Trade-offs

### Sensitivity

* Donation hot path (every like triggers fetch + buffer + eventual sync)
* Number of consumers across `module-core` (currently ~10 inject sites)
* Test fakes (4-5 in `module-core/src/test/`)
* Adapter count in Spring context (boot classpath scan)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 6 → 2 port 병합 | 신규 개발자 학습 ↓, type graph 단순, 어댑터 6→3 | 각 port fat (3-4 메서드), 단일 책임 약화 |
| `LikeSyncPort` 이름 재사용 (의미 변경) | import site diff ↓, file path 동일 | 기존 caller는 시그니처 변경을 명시적으로 인식 필요 |
| `LikeRelationBufferStrategy` 완전 제거 | redis 의존 1개 감소 | relation buffer 단독 테스트 시 별도 fake 필요 |
| 동일 PR에 제거 | 깔끔, dead code 0 | PR 큼 (~25 파일 변경), 리뷰 부담 |

### Risk

* `LikeSyncPort` 이름 충돌 — 신규 시그니처로 alias 만들기보다 rename이 안전
* `LikeRelationBufferStrategy` 제거 후 buffer 책임 unclear — `LikeReadPort.bufferPush`로 명시
* Adapter 3개로 합치면서 Spring bean wiring 누락 가능 — `@Primary` 명시로 boot 안정성 확보

### Non-Risk

* `LikeAtomicFetchStrategy` adapter가 port 변경 후에도 Redis 의존성 동일
* Boot classpath scan은 port 이름으로 wiring하므로 의존성 그래프 단순화 효과
* 테스트 fake는 mock library로 쉽게 통합 가능

---

## 5. Migration Plan (single PR)

1. Create `LikeReadPort` + `LikeSyncPort` in `module-core/.../core/port/out/`
2. Create `LikeReadPortAdapter` (Redis fetch + buffer), `LikeSyncPortAdapter` (flush + relation sync + publish) in `module-infra/.../like/`
3. Update all `module-core` consumers (5-10 sites)
4. Update all `module-infra` adapter call sites
5. Delete old 5 ports + 3 adapter files
6. Update test fakes in `module-core/src/test/`

---

## 6. Test Strategy

* `LikeReadPortAdapter` unit test: fetch / bufferPush / bufferSize each verified
* `LikeSyncPortAdapter` unit test: sync / publish each verified
* `module-core` consumer integration test: existing tests use updated fake
* Boot context test: ensure no orphan bean wiring

Coverage target: 80%+ on adapters, consumer regression = 0.

---

## 7. Success Signal

* LOC: 6 port files + 3 adapter files = ~9 files, ~600 LOC → 2 port files + 2 adapter files = ~4 files, ~350 LOC
* Tests: existing like-related test 0 fail, new adapter test 4-6 pass

---

## 8. Out of Scope

* Monitoring 7→2 merge (PR2)
* Dead port removal (PR3)
* Inbound port consolidation (not in #897 scope)
