# 부록 D: 이슈 & PR 인덱스

---

## D.1 핵심 이슈 목록

### Security (보안)

| 이슈 | 우선순위 | 제목 | 상태 | PR |
|------|----------|------|------|-----|
| #146 | P0 | Admin/핵심 API 인증·인가 최소선 구축 | CLOSED | #165 |
| #662 | P0 | fingerprint 컬럼 추가 — self-like 방지 | CLOSED | #666 |
| #667 | P0 | Login 시 Nexon API 계정 검증 누락 | OPEN | #668* |

### Data Integrity (데이터 정합성)

| 이슈 | 우선순위 | 제목 | 상태 | PR |
|------|----------|------|------|-----|
| #9 | Bug | Race Condition Unique 제약 위반 | CLOSED | — |
| #147 | — | LikeSyncService 원자성 및 보상 트랜잭션 | CLOSED | #175 |
| #330 | Bug | LikeSyncCompensationIntegrationTest Flaky | CLOSED | — |
| #626 | P0 | Like Buffer Race Condition — fetchAndClear | CLOSED | — |
| #635 | P1 | Circuit Breaker 오픈 시 Like 카운트 유실 | CLOSED | — |
| #649 | P2 | LikeSyncExecutor mid-batch 실패 시 보상 부재 | OPEN | — |
| #664 | P1 | like_count와 character_like 불일치 — DB Trigger | CLOSED | #666 |
| #665 | — | Cache coherency failure — split-brain | CLOSED | #666 |

### Performance (성능)

| 이슈 | 우선순위 | 제목 | 상태 | PR |
|------|----------|------|------|-----|
| #171 | — | LikeSync 성능 최적화 및 순환 참조 제거 | CLOSED | #189 |
| #276 | — | Repository 쿼리 DB 인덱스 최적화 | CLOSED | #276 |
| #284 | — | 대규모 트래픽(1000+ RPS) P0/P1 병목 해결 | CLOSED | #298 |
| #627 | P0 | Carrier Thread Pinning — Caffeine + Virtual Thread | CLOSED | — |
| #645 | P2 | L2 Cache LIKE 풀테이블 스캔 → 인덱스 활용 | OPEN | — |

### Architecture (아키텍처)

| 이슈 | 우선순위 | 제목 | 상태 | PR |
|------|----------|------|------|-----|
| #27 | — | Scale-out 확장 저장소 코드 분석 및 동시성 제어 | CLOSED | #89 |
| #47 | — | 분산 환경 스케줄러 중복 실행 방지 | CLOSED | #61 |
| #194 | — | 낮은 커버리지 영역 테스트 보강 (53%→90%) | CLOSED | — |
| #271 | — | V5 Stateless 아키텍처 전환 | CLOSED | — |
| #278 | — | Scale-out 실시간 좋아요 동기화 (Pub/Sub) | CLOSED | #280 |
| #426 | — | Redis 어댑터 이관 | CLOSED | — |
| #436 | — | Port/Adapter 패턴 적용 — 인터페이스 분리 | CLOSED | — |
| #552 | — | PGMQ 프로듀서 & 컨슈머 구현 | CLOSED | — |
| #559 | — | 좋아요 시스템 (PostgreSQL UNLOGGED + PGMQ) | CLOSED | — |
| #589 | — | Redis/Redisson 의존성 완전 제거 | CLOSED | — |
| #623 | — | 동기식 Fan-Out I/O + 읽기/쓰기 결합 안티패턴 | OPEN | — |
| #633 | P1 | EquipmentPersistenceTracker → PostgreSQL 전환 | CLOSED | — |

### Reliability (안정성)

| 이슈 | 우선순위 | 제목 | 상태 | PR |
|------|----------|------|------|-----|
| #29 | — | 성능 저하 및 장애 원인 추적 핵심 지표 정의 | CLOSED | #55 |
| #208 | — | DB 성능 최적화 InnoDB Buffer Pool 튜닝 | CLOSED | — |
| #344 | P0 | MySQL Connection Pool 고갈로 서비스 불안정 | CLOSED | — |

---

## D.2 핵심 PR 목록

### 기능 구현

| PR | 제목 | 병합일 | 변경 파일 | 핵심 |
|----|------|--------|-----------|------|
| #60 | 좋아요 버퍼 Graceful Shutdown | 2025-12-23 | — | @PreDestroy flush |
| #89 | Graceful Shutdown like buffer 완성 | 2025-12-28 | — | 안전한 종료 |
| #164 | LikeSync Redis 원자성 (Lua Script) | 2026-01-12 | — | fetchAndClear 원자화 |
| #175 | LikeSync 보상 트랜잭션 | 2026-01-12 | — | CompensationCommand |
| #280 | Scale-out 실시간 좋아요 동기화 | 2026-01-28 | — | Redis Pub/Sub |
| #288 | 좋아요 어뷰징 방지 | 2026-01-29 | — | Self-like + Lua Script |
| #584 | PostgreSQL scale-out migration | 2026-03-10 | — | Redis-free operation |
| #622 | Direct DB 토글 서비스 | 2026-03-28 | — | ADR-344, 단일 트랜잭션 |
| #666 | Fingerprint + DB Trigger | 2026-03-29 | — | #662-#665 통합 해결 |
| #668* | Nexon API validation on login | 2026-03-29 | — | API Key 검증 |

### 리팩토링

| PR | 제목 | 병합일 | 핵심 |
|----|------|--------|------|
| #69 | AOP 기반 캐싱 전략 + 예외 처리 | 2025-12-26 | TieredCache |
| #451 | ADR-003 헥사고날 리팩토링 | 2026-03-01 | Port/Adapter |
| #535 | ADR-012 like 패키지 core/infra 분리 | 2026-03-02 | 계층 분리 |
| #538 | Phase 5 빈 패키지 제거 | 2026-03-03 | usecase 이관 |
| #539 | Phase 5-E/F like/donation 이관 | 2026-03-03 | application 계층 |
| #583 | LikeSync scheduler timing 정렬 | 2026-03-10 | 2x multiplier |
| #589 | Redis/Redisson 의존성 제거 | 2026-03-11 | PostgreSQL 전환 완료 |

### 테스트 & 성능

| PR | 제목 | 병합일 | 핵심 |
|----|------|--------|------|
| #602 | Core Unit Test Template | 2026-03-15 | 순수 도메인 테스트 |
| #606 | PostgreSQL chaos tests | 2026-03-17 | PGMQ + CB + Network |
| #607 | PostgreSQL integration tests | 2026-03-18 | Testcontainers |
| #614 | ClassCastException fix + bulk 최적화 | 2026-03-23 | L2 캐시 타입 안전 |

---

> *이 목록은 Like 도메인과 직접 관련된 핵심 이슈/PR만 포함합니다. 전체 목록은 GitHub Issues/PR 페이지를 참조하세요.*
>
> *PR #589, #668 등은 커밋으로 존재하나 GitHub PR 페이지 접근이 제한될 수 있습니다.*
