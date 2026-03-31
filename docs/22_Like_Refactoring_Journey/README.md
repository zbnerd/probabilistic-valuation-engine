# Like Domain Refactoring Journey — 좋아요, 123일간의 진화

> *"완벽한 코드는 한 번에 탄생하지 않는다. 수많은 장애, 레이스 컨디션, 그리고 아키텍처의 전환점을 거치며 빚어진다."*

---

## 개요

이 문서는 **Probabilistic Valuation Engine**의 **Like(좋아요) 도메인**이 2025년 11월부터 2026년 3월까지 123일간 겪은 리팩토링 여정을 담습니다.

단순한 기능 구현에서 시작해, 동시성 제어, Redis 원자성, Scale-out 대응, 헥사고날 아키텍처 전환, PostgreSQL 마이그레이션, 그리고 Direct DB 토글에 이르기까지 — 한 개의 도메인이 어떻게 시스템 전체의 진화를 견인했는지 보여줍니다.

---

### 여정 한눈에 보기

| 장 | 기간 | 테마 | 핵심 성과 |
|----|------|------|-----------|
| 1장 | 2025.11 - 12 | 탄생과 첫 위기 | 동시성 제어, Graceful Shutdown, 분산 락 |
| 2장 | 2026.01 초 | Redis 원자성 | Lua Script, 보상 트랜잭션, 데이터 정합성 |
| 3장 | 2026.01 말 | Scale-out 대응 | Pub/Sub 실시간 동기화, 어뷰징 방지 |
| 4장 | 2026.02 | 아키텍처 대전환 | 멀티모듈, Java→Kotlin, 헥사고날 |
| 5장 | 2026.03 초 | 헥사고날 분리 | core/infra 분리, Port/Adapter 패턴 |
| 6장 | 2026.03 중 | Redis에서 PostgreSQL로 | Redis 의존성 제거, PGMQ, UNLOGGED 테이블 |
| 7장 | 2026.03 말 | Direct DB와 보안 강화 | Fingerprint, DB Trigger, Nexon API 검증 |
| 부록 | — | 교훈과 데이터 | 메트릭, 커밋 통계, ADR 인덱스 |

### 숫자로 보는 여정

| 지표 | 값 |
|------|-----|
| 총 기간 | 123일 (2025.11.28 ~ 2026.03.31) |
| Like 관련 커밋 | 120+ |
| Like 관련 PR | 30+ |
| Like 관련 이슈 | 20+ |
| ADR 문서 | 9개 |
| P0 해결 | 12건¹ |
| P1 해결 | 19건 |
| DB QPS | 2,500-3,500/s → <200/s (12-17x 감소) |
| P99 Latency | 22-35ms → 8-12ms (3x 개선) |
| Redis RTT | 3-4회 → 1회 (Lua Script) |

> ¹ P0 12건: 5-Agent Council 분석 8건 (3장) + 데이터 정합성/성능/보안 개별 P0 4건 (6-7장)

---

## 목차

1. [1장: 탄생 — 동시성의 늪 (2025.11 ~ 12)](chapter-1-genesis.md)
2. [2장: Redis 원자성 — Lua Script의 등장 (2026.01 초)](chapter-2-redis-atomicity.md)
3. [3장: Scale-out — 여러 서버가 하나처럼 (2026.01 말)](chapter-3-scaleout.md)
4. [4장: 아키텍처 대전환 — 모듈의 분리 (2026.02)](chapter-4-multimodule.md)
5. [5장: 헥사고날 — 경계의 명확화 (2026.03 초)](chapter-5-hexagonal.md)
6. [6장: Redis에서 PostgreSQL로 — 인프라의 근본적 전환 (2026.03 중)](chapter-6-postgresql.md)
7. [7장: Direct DB — 마지막 퍼즐 (2026.03 말)](chapter-7-direct-db.md)
8. [부록 A: 메트릭 변천사](appendix-a-metrics.md)
9. [부록 B: 핵심 커밋 타임라인](appendix-b-timeline.md)
10. [부록 C: ADR 문서 인덱스](appendix-c-adr-index.md)
11. [부록 D: 이슈 & PR 인덱스](appendix-d-issues-prs.md)

---
