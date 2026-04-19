# V5 Query Server 분리: LOGGED Read Model + Next.js Query Server

**Status**: 계획 수립 (ADR 기반) | **Date**: 2026-04-19
**ADR**: [ADR-V5-query-server-nextjs-phase1](../01_ADR/ADR-V5-query-server-nextjs-phase1.md)
**Review**: Brainstorming gap analysis 완료 (GAP 7건 식별, 아키텍처 전면 수정)

---

## Context

V5 Query Server를 Next.js로 분리한다. Java V5 CQRS가 계산 결과를 LOGGED read model에 GZIP BYTEA로 저장하고, Next.js는 Read Replica에서 decompress하여 응답한다.

**핵심 원칙**: 조회 전용 경로를 Next.js로 완전히 분리. cache_storage (UNLOGGED)는 V4 전용으로 유지.

### 아키텍처

```
[V4 경로 — 기존 유지]
  Java → L1 (Caffeine) → L2 (cache_storage UNLOGGED, GZIP BYTEA)

[V5 경로 — 신규]
  Java V5 CQRS (Write)
    ↓ GZIP 압축 V5 JSON → BYTEA
    ↓ UPSERT
  character_expectation_read_model (LOGGED)
    ↓ WAL replication
  PostgreSQL Read Replica
    ↓ SELECT payload WHERE user_ign = $1
  Next.js Query Server (Vercel)
    ↓ gunzipSync(payload) → JSON
  Client
```

### 기각한 이전 접근

| 접근 | 기각 이유 |
|------|-----------|
| cache_storage (UNLOGGED) 직접 읽기 | WAL 미기록. Read replica 불가. crash 시 데이터 손실 |
| V4→V5 온더플라이 변환 | 필드 매핑 복잡. Double→BigDecimal 정밀도 손실. 타임존 가정 위험 |
| TypedValue 파싱 | cache_value가 BYTEA. JSON parse 불가. Jackson 직렬화 포맷 의존 |
| Phase 1/Phase 2 분리 | 애초에 LOGGED read model로 가는 게 정답. 굳이 UNLOGGED 경유 불필요 |

---

## 구축 항목

### Step 1: LOGGED Read Model 테이블 (Java)

**Flyway migration**:

```sql
-- VXXX__create_character_expectation_read_model.sql
CREATE TABLE character_expectation_read_model (
    user_ign       VARCHAR(100) PRIMARY KEY,
    payload        BYTEA NOT NULL,        -- GZIP 압축된 V5 JSON 응답
    calculated_at  TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE character_expectation_read_model
  IS 'V5 Query Server read model. GZIP compressed V5 response payload.';
```

**검증**:
- LOGGED 테이블 (UNLOGGED 아님)
- WAL 기록 → Read Replica 복제 가능
- `payload BYTEA`: GZIP 압축된 V5 JSON. decompress만 하면 최종 응답

### Step 2: Java V5 CQRS 쓰기 로직 (Java)

Java V5 CQRS (이미 구현됨, `v5.enabled=true` 활성화)에 read model 쓰기 추가.

**기존 흐름**:
```
V5 계산 완료 → EquipmentExpectationResponseV5 생성 → 응답
```

**변경 후**:
```
V5 계산 완료 → EquipmentExpectationResponseV5 생성
  → JSON 직렬화 → GZIP 압축
  → UPSERT character_expectation_read_model (user_ign, payload, calculated_at)
  → 응답
```

**주의사항**:
- UPSERT는 계산 트랜잭션 내에서 수행 (같은 트랜잭션 보장)
- `payload`는 V5 응답 전체를 GZIP 압축한 BYTEA
- `cache_storage`와 독립. V4 L2 캐시에 영향 없음

**검증**:
- [ ] Java V5 활성화 (`v5.enabled=true`)
- [ ] read model UPSERT 동작 확인
- [ ] GZIP 압축 → BYTEA 저장 확인
- [ ] 기존 cache_storage V4 경로에 영향 없음

### Step 3: Vultr PostgreSQL Read Replica 구축 (Infra)

Vultr에 Primary DB의 Read Replica 설정.

**필수 작업**:
1. `postgresql.conf`: `wal_level = replica`, `max_wal_senders` 설정
2. Replication slot 생성
3. Replica 인스턴스 구축 (동일 Vultr 리전)
4. Replica 연결 정보 (`REPLICA_DATABASE_URL`) 확보

**검증**:
- [ ] Replica에서 `SELECT * FROM character_expectation_read_model` 동작
- [ ] Replica lag < 1초 확인
- [ ] SSL 연결 설정 (Vercel → Replica)

### Step 4: Next.js Query Server 프로젝트 초기화

```
query-server/
├── app/
│   └── api/v5/characters/[userIgn]/
│       ├── expectation/route.ts     ← 메인 조회 엔드포인트
│       └── task/[taskId]/route.ts   ← Task Status 폴링
├── lib/
│   ├── db.ts                       ← Read Replica 연결
│   └── decompress.ts               ← GZIP BYTEA → JSON
├── package.json
├── next.config.ts
└── .env.local                      ← REPLICA_DATABASE_URL
```

**환경 변수**:
```env
REPLICA_DATABASE_URL=postgresql://...  # Read Replica 연결
```

### Step 5: Next.js → Read Replica 연결 + Decompress

**db.ts** (Read Replica 연결):
```typescript
import { Pool } from 'pg';

const pool = new Pool({
  connectionString: process.env.REPLICA_DATABASE_URL,
  max: 10,  // Serverless: 연결 수 제한
});

export async function query(text: string, params: unknown[]) {
  return pool.query(text, params);
}
```

**decompress.ts**:
```typescript
import { gunzipSync } from 'zlib';

export function decompressPayload(payload: Buffer): string {
  // GZIP magic number 검증
  if (payload[0] !== 0x1f || payload[1] !== 0x8b) {
    throw new Error('Invalid GZIP payload');
  }
  return gunzipSync(payload).toString('utf-8');
}
```

**route.ts** (메인 엔드포인트):
```typescript
import { NextRequest, NextResponse } from 'next/server';
import { query } from '@/lib/db';
import { decompressPayload } from '@/lib/decompress';

export async function GET(
  _request: NextRequest,
  { params }: { params: { userIgn: string } }
) {
  const { userIgn } = params;

  try {
    const result = await query(
      'SELECT payload, calculated_at FROM character_expectation_read_model WHERE user_ign = $1',
      [userIgn]
    );

    if (result.rows.length === 0) {
      return NextResponse.json({ status: 'pending' }, { status: 202 });
    }

    const row = result.rows[0];

    // TTL 만료 확인
    const ttlMinutes = parseInt(process.env.CACHE_TTL_MINUTES ?? '60', 10);
    const expiresAt = new Date(row.calculated_at.getTime() + ttlMinutes * 60_000);
    if (new Date() > expiresAt) {
      return NextResponse.json({ status: 'pending' }, { status: 202 });
    }

    // GZIP decompress → JSON 응답
    const json = decompressPayload(row.payload);

    return new NextResponse(json, {
      headers: { 'Content-Type': 'application/json' },
    });
  } catch (error) {
    // GZIP 손상 또는 DB 장애
    return NextResponse.json(
      { status: 'error', message: 'Query service unavailable' },
      { status: 503 },
    );
  }
}
```

**검증**:
- [ ] Read Replica에서 데이터 읽기
- [ ] GZIP decompress 동작
- [ ] TTL 만료 시 202 응답
- [ ] 존재하지 않는 userIgn → 202
- [ ] 손상된 payload → 503
- [ ] DB 장애 → 503

### Step 6: Vercel 배포 설정

1. Vercel 프로젝트 생성 (`query-server/` 루트)
2. 환경 변수 설정: `REPLICA_DATABASE_URL`, `CACHE_TTL_MINUTES`
3. Serverless Function 메모리 1024MB
4. Vercel 리전: Replica와 동일 리전

---

## 에러 응답 체계

| 상황 | HTTP Status | 응답 |
|------|-------------|------|
| 데이터 있음 + 유효 | 200 | V5 JSON (decompressed) |
| 데이터 없음 / 계산 중 | 202 | `{ status: "pending" }` |
| GZIP 손상 | 503 | `{ status: "error" }` |
| DB 장애 | 503 | `{ status: "error", message: "Query service unavailable" }` |
| TTL 만료 | 202 | `{ status: "pending" }` |

---

## Verification Checklist

1. [ ] LOGGED read model 테이블 생성 (Flyway migration)
2. [ ] Java V5 CQRS에 read model UPSERT 추가
3. [ ] Java V5 활성화 (`v5.enabled=true`)
4. [ ] Read Replica 구축 + 복제 동작 확인
5. [ ] Next.js에서 Replica 조회 → decompress → 응답
6. [ ] TTL 만료 후 202 응답
7. [ ] 손상된 payload → 503
8. [ ] DB 장애 시 503
9. [ ] 존재하지 않는 userIgn → 202
10. [ ] Vercel 배포 + SSL 연결

---

## References

- [ADR-V5-QueryServer](../01_ADR/ADR-V5-query-server-nextjs-phase1.md)
- [ADR-V5 CQRS](../01_ADR/ADR-V5-cqrs-mongodb-readside.md)
- [GameCharacterControllerV5.kt](../../module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt)
- [PostgresL2CacheStrategy.kt](../../module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheStrategy.kt)
