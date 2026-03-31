# 부록 C: ADR 문서 인덱스

---

## C.1 Like 도메인 ADR 목록

| ADR | 제목 | 날짜 | 상태 | 파일 |
|-----|------|------|------|------|
| ADR-003 | LikeSyncScheduler 헥사고날 아키텍처 리팩토링 | 2026-03-01 | Accepted | `docs/01_ADR/` |
| ADR-005 | LikeSyncScheduler 이관 | 2026-03-01 | Accepted | `docs/01_ADR/` |
| ADR-012 | Like 패키지 core/infra 분리 완료 | 2026-03-02 | Accepted | `docs/01_ADR/` |
| ADR-015 | Like Endpoint P1 Acceptance | 2026-01-29 | Accepted | `docs/01_ADR/ADR-015-like-endpoint-p1-acceptance.md` |
| ADR-331 | Like Infra Migration Build Plan | 2026-02 | Superseded | `docs/01_ADR/ADR-331-like-to-infra-migration-build-plan.md` |
| ADR-332 | Like Infra Migration | 2026-02 | Superseded | `docs/01_ADR/ADR-332-like-to-infra-migration.md` |
| ADR-344 | Like Direct DB Approach | 2026-03-28 | Accepted | `docs/01_ADR/ADR-344-like-direct-db-approach.md` |
| ADR-346 | Like Fingerprint Account ID Trigger | 2026-03-29 | Accepted | `docs/01_ADR/ADR-346-like-fingerprint-account-id-trigger.md` |
| ADR-361 | Like Buffer Restore on Circuit Open | 2026-03 | Accepted | `docs/01_ADR/ADR-361-like-buffer-restore-on-circuit-open.md` |

> *참고: ADR-003, ADR-005, ADR-012는 커밋 메시지에서 참조된 번호입니다. 실제 ADR 파일은 `docs/01_ADR/` 디렉토리에서 확인하세요. ADR-029는 PR #622 커밋 메시지에 등장하나, 실제 파일은 ADR-344로 존재합니다.*

---

## C.2 주요 ADR 요약

### ADR-015: Like Endpoint P1 Acceptance

**결정**: 4개의 P1 이슈를 명시적으로 수용(Accept)

수용된 이슈:
1. **P1-4**: DB fallback thundering herd — Atomic Toggle로 cold buffer 시나리오 제거
2. **P1-6**: Synchronous controller — Virtual Threads + 1-3ms Redis → 비동기 불필요
3. **P1-9**: Pub/Sub at-most-once — Eventual Consistency (3-5초) + TTL 자동 만료
4. **P1-12**: Circuit Breaker 미적용 — `executeOrDefault` 패턴이 동등한 보호

**의미**: 완벽을 추구하지 않고, 트레이드오프를 명시적으로 문서화.

### ADR-344: Like Direct DB Approach

**결정**: Like 토글을 Direct DB 트랜잭션으로 직접 처리

**배경**: Redis 제거 후 PGMQ + Buffer의 간접층이 과잉 설계

**대안 검토**:
- ❌ PGMQ 비동기: 메시지 유실 가능, 복잡도 증가
- ❌ UNLOGGED Buffer: 서버 크래시 시 유실
- ✅ Direct DB: 단순, 정확, Trigger로 원자성 보장

### ADR-346: Like Fingerprint Account ID Trigger

**결정**: fingerprint 컬럼 + DB Trigger로 self-like 방지와 count 정합성 동시 해결

**핵심 설계**:
- `fingerprint` VARCHAR(64) = 기기 식별자
- `account_id` VARCHAR(64) = fingerprint와 동일
- `fn_like_count_trigger()` = INSERT/DELETE 시 자동 count 업데이트
- Lazy backfill = fingerprint 미배정 캐릭터에만 stamp

### ADR-361: Like Buffer Restore on Circuit Open

**결정**: Circuit Breaker 오픈 시 버퍼 데이터를 보존하는 메커니즘

**문제**: Circuit Breaker가 오픈되면 LikeSync가 실행되지 않아, 버퍼가 무한 증가

**해결**: Buffer overflow 임계값 설정 + Circuit Close 시 밀려있던 데이터 일괄 처리
