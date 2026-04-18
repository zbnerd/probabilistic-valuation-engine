# Issues #715 & #716: cache_storage 마이그레이션 누락 + 버전 카운터 충돌 수정 계획

## 배경

Issue #704 Multi-Instance Cache Invalidation Test 구현 중 두 가지 버그 발견.

## Issue #715 (P0): cache_storage 테이블 CREATE 마이그레이션 누락

**문제**: `cache_storage` 테이블이 코드베이스 어디에도 CREATE되지 않음. V102, V107은 인덱스만 생성. `PostgresL2CacheStrategy`는 해당 테이블에 SELECT/INSERT/DELETE를 실행.

**영향**: 신규 환경 배포 시 L2 캐시(PostgreSQL) 전체 기능 동작 안 함.

**해결**: V110 마이그레이션으로 `CREATE UNLOGGED TABLE IF NOT EXISTS` 추가.

## Issue #716 (P1): TieredCache 버전 카운터 충돌

**문제**: `TieredCache.evict()`가 `versionCounter.get()`을 사용하여 `put()`과 동일 버전 발행. 각 인스턴스의 counter가 `AtomicLong(0)`에서 독립 시작하므로 cross-instance 버전 충돌 발생.

**근본 원인** (Critic 발견):
- `TieredCache.kt:112` — `put()`: `versionCounter.incrementAndGet()` (증가)
- `TieredCache.kt:138` — `evict()`: `versionCounter.get()` (증가 없음)
- 결과: put(v=1) → evict(v=1) 동일 버전 발행

**재현 경로**:
1. Instance A: put("key") → v=1, publish evict(v=1)
2. Instance B: get("key") → L2 backfill → keyVersions["key"]=1
3. Instance A: evict("key") → publish evict(v=1) (동일 버전!)
4. Instance B: `event.version(1) <= currentVersion(1)` → stale 판단, evict 스킵

## Consensus Review 결과 (Architect + Critic + Code-Reviewer)

### Cross-Agent Convergence
- 3/3 동의: V110 마이그레이션 필요
- Critic이 근본 원인 발견: evict()의 `.get()` vs put()의 `.incrementAndGet()`

### Key Divergence → 합의
- Architect: version check 제거
- Critic: check 유지 + strict `<` + evict increment (근본 원인 해결)
- Code-Reviewer: L2 기반 버전 관리
- **합의**: Critic 접근 채택 (근본 원인 해결 + 방어막 유지)

## 수정 계획

### 1. V110 cache_storage CREATE 마이그레이션 (Issue #715)
**파일**: `module-infra/src/main/resources/db/migration/V110__cache_storage_create_table.sql` (신규)
```sql
CREATE UNLOGGED TABLE IF NOT EXISTS cache_storage (
    cache_key VARCHAR(500) PRIMARY KEY,
    cache_value BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
COMMENT ON TABLE cache_storage IS 'PostgreSQL L2 cache tier (UNLOGGED). Key format: {cacheName}:v1:{actualKey}';
```

### 2. evict() 버전 카운터 수정 (Issue #716 근본 원원인, P0)
**파일**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt:138`
- `versionCounter.get()` → `versionCounter.incrementAndGet()`

### 3. version check strict `<` 변경 (Issue #716, P1)
**파일**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/PostgresNotifySubscriber.kt:235`
- `event.version <= currentVersion` → `event.version < currentVersion`
- 동일 버전은 통과시키고, 명확히 구버전만 스킵

### 4. clearKeyVersion() 단일 키 삭제 메서드 추가 (P1)
**파일**: `TieredCache.kt` — `fun clearKeyVersion(key: Any)` 추가
**파일**: `TieredCacheManager.kt` — `fun clearKeyVersion(cacheName: String, key: Any)` 위임 메서드 추가

### 5. clear()에서 keyVersions.clear() 추가 (P1)
**파일**: `TieredCache.kt:157`
- `l1.clear()` 후 `keyVersions.clear()` 호출 (메모리 누수 방지)

### 6. EVICT 후 clearKeyVersion 호출 (P1)
**파일**: `PostgresNotifySubscriber.kt:246`
- L1 evict 후 `tieredCacheManager.clearKeyVersion()` 호출

### 7. EVICT 로그에 version 정보 추가 (P2)
**파일**: `PostgresNotifySubscriber.kt:248-254`
- eventVersion, currentVersion 포함

### 8. ADR 문서 작성
**파일**: `docs/01_ADR/ADR-XXX-cache-storage-migration-version-fix.md`

## 검증
```bash
./gradlew compileKotlin compileJava --continue
./gradlew test
```

## 이미 검증 완료
- 컴파일: PASS
- 테스트: PASS (37 tasks)
