# V5 Query Server 분리: Phase 접근 (cache_storage → LOGGED Read Model)

**Status**: Phase 1 Java 완료 (PR #722 머지) | **Date**: 2026-04-19
**Review**: 2차 Consensus Review 통과 (P0 4건 해결, FP 1건 판명)

---

## Context

V5 Query Server를 Next.js로 분리하려면 계산 결과를 읽을 수 있어야 함. `cache_storage` UNLOGGED 테이블에 이미 GZIP 압축된 전체 응답이 저장되어 있음.

**Phase 접근**:
- **Phase 1 (단기)**: cache_storage 직접 읽기 + 완화 조치 — Java 변경 완료 (PR #722), Next.js 구현 대기
- **Phase 2 (장기)**: LOGGED read model 테이블 마이그레이션

---

## Consensus Review 결과 (2차 — 3 에이전트 × 2라운드)

### 해결된 P0 이슈

| ID | 이슈 | 해결책 |
|----|------|--------|
| P0-1 | V4→V5 필드 매핑 오류 | 정확한 필드명 사용 (`blackCubeCost` etc), `flameCost=0` 기본값 |
| P0-2 | TypedValue/GZIP 파싱 에러 핸들링 누락 | null 체크, GZIP magic number 검증, try-catch + 503 |
| P0-3 | Double→BigDecimal 정밀도 손실 | `toPrecision(17)` 사용 (IEEE 754 full precision) |
| P0-4 | LocalDateTime→Instant 타임존 가정 | UTC 가정 명시 (`+ 'Z'` suffix) |

### P1 이슈 (완화)

| ID | 이슈 | 완화 |
|----|------|------|
| P1-1 | Thundering herd (UNLOGGED crash) | CB + stale-while-revalidate + 기존 admission control |
| P1-2 | TTL 연장 | `PostgresL2CacheFactory.kt:85` 상수 대신 YAML 설정 사용 (**PR #722에서 완료**) |
| P1-3 | Key format 결합도 | 양쪽 환경변수화 (Java CacheProperties + Next.js env) (**PR #722에서 Java 완료**) |

### False Positive

| ID | 이슈 | 판명 |
|----|------|------|
| FP | Cache key format 불일치 (`expectation:v3:...`) | **FALSE** — Critic이 V2/V3 `TotalExpectationCacheService`를 V5 경로로 혼동. 실제 V5 키 = `expectationV4:v1:{userIgn}` |

---

## Phase 1: cache_storage 직접 읽기 (단기)

### Java 변경사항 (완료 — PR #722)

1. **TTL 설정 오버라이드**: `PostgresL2CacheFactory` — `L2_TTL_SECONDS` 상수 제거 → YAML `l2-ttl-minutes` 설정값 사용. expectationV4 TTL 15분→60분 연장
2. **Key format 환경변수화**: `CacheProperties.keyVersion` 추가, `PostgresL2CacheStrategy`에서 YAML 설정 사용
3. **Lambda Hell 해결**: TTL 계산 로직 `resolveTtlSeconds()` private method 추출 + 미등록 캐시 warning 로그

### Next.js 구현 (대기)

#### 데이터 흐름

```
cache_storage (UNLOGGED)
  cache_key: "expectationV4:v1:{userIgn}"   ← PostgresL2CacheStrategy.generateKey()
  cache_value: TypedValue JSON { "@type": "...", "value": Base64(GZIP(V4 JSON)) }
  expires_at: TTL (YAML l2-ttl-minutes 설정값, 현재 60분)

Next.js Query Server
  1. SELECT cache_value, expires_at FROM cache_storage WHERE cache_key = $1
  2. Safe TypedValue 파싱 (null/type 체크)
  3. Base64 decode → GZIP magic number (0x1f 0x8b) 검증 → gunzip
  4. V4 JSON → V5 변환 (정확한 필드 매핑)
  5. JSON 응답 반환
```

#### V4→V5 필드 매핑 (검증 완료)

```
V4 CostBreakdownDto (4 fields, Double)     V5 CostBreakdownDto (5 fields, BigDecimal)
  blackCubeCost: Double        →           blackCubeCost: BigDecimal
  redCubeCost: Double          →           redCubeCost: BigDecimal
  additionalCubeCost: Double   →           additionalCubeCost: BigDecimal
  starforceCost: Double        →           starforceCost: BigDecimal
  (없음)                       →           flameCost: BigDecimal = "0"  (V5 추가)

V4 Response                                V5 Response
  totalExpectedCost: Double    →           totalExpectedCost: BigDecimal
  calculatedAt: LocalDateTime  →           calculatedAt: Instant  (UTC 가정)
  items[].expectedCost: Double →           items[].expectedCost: BigDecimal
```

소스: `EquipmentExpectationResponseV4.kt:299-303`, `EquipmentExpectationResponseV5.kt:111-116`

#### Step 1: 환경 설정

```env
DATABASE_URL=postgresql://...
CACHE_KEY_PREFIX=expectationV4
CACHE_KEY_VERSION=v1
```

#### Step 2: Safe Cache 읽기 + 파싱

```typescript
// GET /api/v5/characters/{userIgn}/expectation
const key = `${process.env.CACHE_KEY_PREFIX}:${process.env.CACHE_KEY_VERSION}:${userIgn}`;

let row;
try {
  row = await db.query(
    'SELECT cache_value, expires_at FROM cache_storage WHERE cache_key = $1',
    [key]
  );
} catch (dbError) {
  // Circuit Breaker: DB 장애 시 503
  return Response.json(
    { status: 'error', message: 'Query service unavailable' },
    { status: 503 },
  );
}

if (!row || row.expires_at < new Date()) {
  return Response.json({ status: 'pending' }, { status: 202 });
}

// Safe TypedValue 파싱 (P0-2 해결)
const typedValue = safeParseTypedValue(row.cache_value);
if (!typedValue) {
  return Response.json({ status: 'error' }, { status: 503 });
}

// Safe GZIP 파싱 (P0-2 해결)
const v4Response = safeGunzipAndParse(typedValue.value);
if (!v4Response) {
  return Response.json({ status: 'error' }, { status: 503 });
}

const v5Response = convertV4ToV5(v4Response);
return Response.json(v5Response);
```

#### Step 3: Safe 파싱 유틸리티

```typescript
// TypedValue JSON 파싱 (null/type 체크)
function safeParseTypedValue(cacheValue: Buffer): { value: string } | null {
  try {
    const parsed = JSON.parse(cacheValue.toString('utf-8'));
    if (!parsed || typeof parsed.value !== 'string' || !parsed.value) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

// GZIP 파싱 (magic number 검증 + 에러 핸들링)
function safeGunzipAndParse(base64Value: string): V4Response | null {
  try {
    const gzipBytes = Buffer.from(base64Value, 'base64');
    // GZIP magic number 검증: 0x1f 0x8b
    if (gzipBytes[0] !== 0x1f || gzipBytes[1] !== 0x8b) {
      return null;
    }
    const decompressed = gunzipSync(gzipBytes);
    return JSON.parse(decompressed.toString('utf-8'));
  } catch {
    return null;
  }
}
```

#### Step 4: V4→V5 변환 (정확한 필드 매핑)

```typescript
function convertV4ToV5(v4: V4Response): V5Response {
  return {
    ...v4,
    totalExpectedCost: toBigDecimal(v4.totalExpectedCost),
    calculatedAt: toISOInstant(v4.calculatedAt),  // Asia/Seoul 가정
    items: v4.items.map(convertItemV4ToV5),
  };
}

function convertItemV4ToV5(item: V4Item): V5Item {
  return {
    ...item,
    expectedCost: toBigDecimal(item.expectedCost),
    costBreakdown: {
      blackCubeCost: toBigDecimal(item.costBreakdown.blackCubeCost),
      redCubeCost: toBigDecimal(item.costBreakdown.redCubeCost),
      additionalCubeCost: toBigDecimal(item.costBreakdown.additionalCubeCost),
      starforceCost: toBigDecimal(item.costBreakdown.starforceCost),
      flameCost: "0",  // V4에 없음, 기본값
    },
  };
}

// P0-3 해결: IEEE 754 full precision 유지
function toBigDecimal(doubleVal: number): string {
  return doubleVal.toPrecision(17);
}

// P0-4 해결: LocalDateTime → Instant (Asia/Seoul 가정)
function toISOInstant(localDateTime: string): string {
  // V4의 LocalDateTime은 타임존 없음. 서버가 Asia/Seoul이므로 +09:00 가정
  // Phase 2에서는 LOGGED 테이블에 Instant로 저장되어 이 변환 불필요
  return localDateTime + '+09:00';
}
```

#### Step 5: Task Status 폴링

```
GET /api/v5/characters/{userIgn}/task/{taskId}
```

cache_storage 존재 여부로 COMPLETED 판단.

### 고려사항

- **UNLOGGED**: PostgreSQL 크래시 시 데이터 손실 → 재계산으로 복구 (캐시 의미론)
- **Read replica 불가**: WAL 미기록 → Primary DB 직접 연결
- **Stale-while-revalidate**: 만료 직전 캐시는 허용하되 백그라운드에서 재계산 트리거

---

## Phase 2: LOGGED Read Model 마이그레이션 (장기)

### 목표

cache_storage 구조적 한계 근본 해결 (UNLOGGED, TypedValue 래퍼, key 결합도, V4 스펙, 타임존 가정).

### 신규 테이블: `character_read_model` (LOGGED)

```sql
CREATE TABLE character_read_model (
    id              BIGSERIAL PRIMARY KEY,
    user_ign        VARCHAR(100) NOT NULL,
    message_id      VARCHAR(100),
    response_json   JSONB NOT NULL,
    calculated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Phase 2 변경사항

1. **Flyway migration**: `character_read_model` LOGGED 테이블 생성
2. **Java dual-write**: `EquipmentExpectationServiceV4.syncToViewTable()`에 V5 JSON 저장 추가
3. **Next.js 전환**: `character_read_model.response_json` 직접 읽기 (zero transformation)
4. **Cleanup**: cache_storage 의존 제거, TypedValue 래퍼 불필요, V4→V5 변환 불필요

Phase 2 상세 계획은 별도 플랜으로 진행.

---

## Verification (Phase 1 Next.js)

1. Next.js에서 `cache_storage` SELECT → 데이터 읽기 확인
2. TypedValue 파싱 → Base64 디코드 → GZIP magic number 검증 → V4 JSON 확인
3. V4→V5 변환: `blackCubeCost`/`redCubeCost`/`additionalCubeCost`/`starforceCost` → BigDecimal, `flameCost="0"` 확인
4. Double→BigDecimal: `toPrecision(17)` 정밀도 유지 확인
5. LocalDateTime→Instant: `+09:00` suffix로 Asia/Seoul 가정 확인
6. 손상된 TypedValue/Base64/GZIP → 503 반환 확인
7. TTL 만료 후 202 응답 확인
8. DB 장애 시 503 (circuit breaker) 확인
9. 존재하지 않는 userIgn → 404 또는 202 확인
