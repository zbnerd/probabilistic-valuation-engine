# ADR-V5-QueryServer: Next.js Query Server 분리 (LOGGED Read Model + Replica)

**Status**: Proposed
**Date**: 2026-04-19
**Related**: [ADR-V5-cqrs-mongodb-readside](ADR-V5-cqrs-mongodb-readside.md)
**Supersedes**: Previous cache_storage direct-read approach ( invalidated by gap analysis)

---

## Executive Summary

V5 Query Server를 Next.js로 분리한다. Java V5 CQRS가 LOGGED read model에 GZIP BYTEA를 저장하고, Next.js는 Read Replica에서 decompress하여 응답한다. cache_storage (UNLOGGED)는 V4 전용으로 유지.

---

## Fail If Wrong

1. **[F1]** Read replica lag > 5초로 stale 데이터 서비스
2. **[F2]** LOGGED read model 스키마와 Java V5 CQRS 직렬화 불일치
3. **[F3]** Vercel cold start + replica 연결로 p99 > 500ms
4. **[F4]** GZIP BYTEA decompress 실패 (손상, 포맷 변경)

---

## Terminology

| 용어 | 정의 |
|------|------|
| **Query Server** | Read-only Next.js 서버. V5 API 엔드포인트 제공 |
| **LOGGED Read Model** | WAL 기록되는 PostgreSQL 테이블. V5 CQRS가 GZIP BYTEA 저장 |
| **Read Replica** | Primary DB의 WAL 복제본. Next.js가 여기서 읽기 |
| **cache_storage** | UNLOGGED 테이블. V4 L2 캐시 전용. V5와 무관 |

---

## Context

### 문제 정의

Spring Boot 서버가 read/write를 모두 처리하여 확장성 제한. V5의 조회 전용 경로를 Next.js로 완전히 분리해야 한다.

### 기존 시스템 분석

| 컴포넌트 | 상태 | 비고 |
|----------|------|------|
| Java V5 CQRS | 구현 완료 (`v5.enabled=false`) | CharacterValuationViewEntity 기반 |
| cache_storage (UNLOGGED) | 운영 중 | V4 L2 캐시. WAL 미기록. replica 불가 |
| LOGGED read model | **미구현** | 신규 생성 필요 |
| Read Replica | **미구현** | Vultr에 구축 필요 |
| Next.js Query Server | **미구현** | 신규 생성 필요 |

### 제약 사항

1. **V4/V5 경로 분리**: V4는 기존 cache_storage 유지. V5는 별도 LOGGED read model 사용
2. **Read Replica 필요**: Next.js가 Primary가 아닌 Replica에서 읽어야 write 부하 격리
3. **GZIP BYTEA**: Java가 이미 GZIP 압축된 응답을 BYTEA로 저장. Next.js는 decompress만

---

## Decision

### 1. 아키텍처: V5 CQRS → LOGGED Read Model → Replica → Next.js

```
[V4 경로 — 기존 유지]
  Java → L1 (Caffeine) → L2 (cache_storage UNLOGGED)

[V5 경로 — 신규]
  Java V5 CQRS (Write)
    → LOGGED read model 테이블에 GZIP BYTEA 저장
    → WAL replication
    → Read Replica
    → Next.js (Query)
      → GZIP BYTEA 읽기 → decompress → JSON 응답
```

**선택 근거**:
- V4/V5 경로 완전 분리. V4 캐시에 영향 없음
- LOGGED 테이블이므로 WAL 복제 가능. Read Replica 구축 가능
- Next.js는 decompress만 하면 됨. V4→V5 변환 불필요. 필드 매핑 불필요
- Primary DB write 부하와 Query read 부하 격리

**기각한 대안**:

| 대안 | 기각 이유 |
|------|-----------|
| cache_storage (UNLOGGED) 직접 읽기 | WAL 미기록. replica 불가. crash 시 손실 |
| cache_storage + V4→V5 변환 | 변환 오버헤드. 필드 매핑 복잡. 정밀도 손실 위험 |
| Java V5만 활성화 (Next.js 없음) | read/write 분리 안 됨. Spring Boot 확장성 제한 유지 |

### 2. 프로젝트 구조: Monorepo

```
probabilistic-valuation-engine/
├── module-api/          (Spring Boot — V4 + V5 write)
├── module-domain/
├── module-infra/
├── query-server/        ← NEW: Next.js Query Server
│   ├── app/
│   │   └── api/v5/characters/[userIgn]/expectation/route.ts
│   ├── lib/
│   │   ├── db.ts              (Read Replica 연결)
│   │   └── decompress.ts      (GZIP BYTEA → JSON)
│   ├── package.json
│   └── next.config.ts
```

### 3. LOGGED Read Model 테이블 (신규)

```sql
CREATE TABLE character_expectation_read_model (
    user_ign       VARCHAR(100) PRIMARY KEY,
    payload        BYTEA NOT NULL,        -- GZIP 압축된 V5 JSON 응답
    calculated_at  TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**설계 근거**:
- `payload BYTEA`: Java가 V5 응답을 GZIP 압축하여 저장. Next.js는 decompress만
- `calculated_at`: TTL 판별 및 stale 감지
- `user_ign` PK: 사용자별 최신 결과 1건. 간단한 조회
- LOGGED 테이블: WAL 기록. Read Replica 복제 가능. crash 안전

### 4. Java V5 CQRS 쓰기 경로

Java V5 CQRS (이미 구현됨, `v5.enabled=true`로 활성화)가 계산 완료 후:
1. V5 응답 JSON을 GZIP 압축
2. `character_expectation_read_model`에 UPSERT
3. 기존 `CharacterValuationViewEntity` 경로와 병행 또는 대체

### 5. 배포: Vercel Serverless

**선택 근거**: 운영 부담 최소화. Auto-scaling.

**DB 연결**: Read Replica에 연결. `@neondatabase/serverless` (WebSocket 모드) 또는 `pg` 모듈.

**Read Replica 연결 시 이점**:
- Primary write 부하와 격리
- Replica 장애 시 Primary로 폴백 가능
- 연결 풀 부담이 Primary에 영향 없음

### 6. Next.js 파이프라인

```typescript
// GET /api/v5/characters/{userIgn}/expectation
// 1. Read Replica에서 조회
const row = await db.query(
  'SELECT payload, calculated_at FROM character_expectation_read_model WHERE user_ign = $1',
  [userIgn]
);

// 2. 없으면 202 (계산 대기)
if (!row) return Response.json({ status: 'pending' }, { status: 202 });

// 3. 만료 확인
if (isExpired(row.calculated_at)) {
  return Response.json({ status: 'pending' }, { status: 202 });
}

// 4. GZIP decompress → JSON 응답
const json = gunzipSync(row.payload).toString('utf-8');
return new Response(json, {
  headers: { 'Content-Type': 'application/json' },
});
```

**핵심**: decompress만 함. 변환, 매핑, TypedValue 파싱 모두 불필요.

---

## 에러 응답 체계

| 상황 | HTTP Status | 응답 |
|------|-------------|------|
| 데이터 있음 + 유효 | 200 | V5 JSON (decompressed) |
| 데이터 없음 / 계산 중 | 202 | `{ status: "pending" }` |
| GZIP 손상 | 503 | `{ status: "error" }` |
| Replica 장애 | 503 | `{ status: "error", message: "Query service unavailable" }` |
| TTL 만료 | 202 | `{ status: "pending" }` |

---

## 구축 필요 항목

| # | 항목 | 담당 | 전제조건 |
|---|------|------|----------|
| 1 | LOGGED read model 테이블 DDL + Flyway migration | Java | 없음 |
| 2 | Java V5 CQRS에 read model 쓰기 로직 추가 | Java | #1 |
| 3 | Vultr PostgreSQL Read Replica 구축 | Infra | #1 |
| 4 | Next.js Query Server 프로젝트 초기화 | Next.js | 없음 |
| 5 | Next.js → Read Replica 연결 + decompress 파이프라인 | Next.js | #3 |
| 6 | Vercel 배포 설정 | Infra | #4, #5 |

---

## Consequences

### 긍정적

- V4/V5 경로 완전 분리. V4 캐시에 영향 없음
- Read Replica로 write/read 부하 격리
- LOGGED 테이블로 crash 안전. 데이터 유실 없음
- Next.js 파이프라인 극도로 단순 (decompress만)
- V4→V5 변환 불필요. 정밀도 손실 위험 제거

### 부정적

- Read Replica 구축 필요 (Vultr 인프라 작업)
- Replica lag으로 약간의 stale 가능성 (보통 < 1초)
- Java V5 CQRS에 read model 쓰기 로직 추가 필요
- Vercel → Vultr 연결 SSL 설정 필요

### Risks

- **Replica lag**: 계산 직후 조회 시 아직 복제 안 된 경우. 완화: 202 응답 + 클라이언트 재시도
- **GZIP 포맷 변경**: Java 직렬화 방식 변경 시 Next.js도 동기화 필요. Monorepo로 추적 가능

---

## References

- [ADR-V5 CQRS](ADR-V5-cqrs-mongodb-readside.md)
- [PostgresL2CacheStrategy.kt](../../module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheStrategy.kt)
- [GameCharacterControllerV5.kt](../../module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt)
- [V5 Query Server Plan](../09_Plans/v5-query-server-nextjs-separation.md) (legacy — will be updated)
