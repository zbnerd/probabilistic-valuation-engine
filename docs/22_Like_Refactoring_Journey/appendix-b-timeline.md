# 부록 B: 핵심 커밋 타임라인

---

## B.1 전체 타임라인

### Phase 1: 탄생 (2025.11 ~ 2025.12)

| 날짜 | 해시 | 내용 | PR |
|------|------|------|-----|
| 2025-11-28 | `92707f08` | Concurrency test failure - Pessimistic Lock 기각 | — |
| 2025-11-29 | `054af475` | High-contention lock mechanism 구현 | — |
| 2025-11-29 | `5e4f003f` | Like feature 동시성 제어 구현 | — |
| 2025-12-19 | `ac1bf6d4` | 디자인 패턴 도입 (Proxy, Decorator) | — |
| 2025-12-23 | `f2323727` | 시스템 관측 가능성 확보 | #55 |
| 2025-12-23 | `d8840de8` | 좋아요 버퍼 Graceful Shutdown | #60 |
| 2025-12-24 | `09cf532d` | 분산 락 AOP 스케줄러 중복 방지 | #61 |
| 2025-12-26 | `57ba1eca` | AOP 기반 캐싱 전략 + 예외 처리 | #69 |
| 2025-12-26 | `48aad9f3` | 분산 락 AOP 가독성 개선 | #70 |
| 2025-12-27 | `6543f06b` | TraceAspect 포인트컷 지시자 | #74 |
| 2025-12-28 | `52f576a2` | Graceful Shutdown like buffer 완성 | #89 |
| 2025-12-28 | `4b326926` | Scale-out 대응 통합 릴리즈 | #97 |

### Phase 2: Redis 원자성 (2026.01 초)

| 날짜 | 해시 | 내용 | PR |
|------|------|------|-----|
| 2026-01-02 | `efd37c69` | 장애 복구 데이터 정합성 수정 | #124 |
| 2026-01-03 | `4dc6260a` | v2.3.0 Redis HA 가용성 강화 | #136 |
| 2026-01-04 | `c61e0fa0` | LogicExecutor 예외 처리 구조화 | #140 |
| 2026-01-07 | `5a507294` | LogicExecutor Policy Pipeline | #144 |
| 2026-01-07 | `baccd0a2` | v2.4.0 LogicExecutor Pipeline Architecture | #154 |
| 2026-01-08 | `5a682305` | Nexon WebClient 무한 대기 방지 | #156 |
| 2026-01-09 | `16ed3af3` | DP 기반 큐브 기대값 엔진 | #159 |
| 2026-01-12 | `18141cd9` | LikeSync Redis 원자성 (Lua Script) | #164 |
| 2026-01-12 | `720ef598` | LikeSync 보상 트랜잭션 | #175 |
| 2026-01-12 | `cdf6a716` | BYOK 인증 시스템 | — |
| 2026-01-14 | `0d94d4a1` | Watchdog 모드 활성화 | #183 |
| 2026-01-16 | `ec32ac5a` | LikeSync 성능 최적화 + 순환 참조 제거 | #189 |

### Phase 3: Scale-out (2026.01 말)

| 날짜 | 해시 | 내용 | PR |
|------|------|------|-----|
| 2026-01-27 | `2026c579` | V5 Stateless 아키텍처 전환 | — |
| 2026-01-27 | `14d6103f` | DB 인덱스 최적화 | #276 |
| 2026-01-28 | `37137764` | Scale-out 실시간 좋아요 동기화 | #280 |
| 2026-01-29 | `fc3f4d90` | 좋아요 어뷰징 방지 | #288 |
| 2026-01-29 | `8e4d86e1` | Executor 강화 + RReliableTopic | #298 |
| 2026-01-29 | `9aac41d1` | LogicExecutor Pipeline 개선 | #290 |
| 2026-01-30 | `195cc551` | 좋아요 토글 기능 구현 | — |
| 2026-02-01 | `a95ea156` | Scale-out Sprint 2+3 전환 | — |

### Phase 4: 모듈 분리 (2026.02)

| 날짜 | 해시 | 내용 | PR |
|------|------|------|-----|
| 2026-02-13 | `65f6c168` | 멀티모듈 마이그레이션 | — |
| 2026-02-18 | `350` | module-common Java→Kotlin | #350 |
| 2026-02-24 | `f9456442` | Java-to-Kotlin Phase 1-1, 2-1, 2-2 | #390 |
| 2026-02-26 | `b2075654` | Kotlin-Java Interop 수정 | #383 |
| 2026-02-28 | `ca912a96` | Module separation Phase 2 | #445 |
| 2026-02-28 | `026c047a` | Kotlin DTO nullability 수정 | #444 |

### Phase 5: 헥사고날 (2026.03 초)

| 날짜 | 해시 | 내용 | PR |
|------|------|------|-----|
| 2026-03-01 | `9f8c2e32` | ADR-003 헥사고날 리팩토링 | #451 |
| 2026-03-01 | `0979c84d` | ADR-005 LikeSyncScheduler 이관 | #481 |
| 2026-03-02 | `c0b37b54` | ADR-012 like 패키지 core/infra 분리 | #535 |
| 2026-03-03 | `2a1d5766` | ADR-004 Phase 5 빈 패키지 제거 | #538 |
| 2026-03-03 | `6e8a05b2` | ADR-004 Phase 5-E/F like/donation 이관 | #539 |
| 2026-03-04 | `04a5b505` | ADR-004 Phase 5-G/H 패키지 이관 | #540 |

### Phase 6: PostgreSQL (2026.03 중)

| 날짜 | 해시 | 내용 | PR |
|------|------|------|-----|
| 2026-03-10 | `774b7595` | LikeSync scheduler timing 정렬 | #583 |
| 2026-03-10 | `e45a208a` | PostgreSQL scale-out migration | #584 |
| 2026-03-11 | `c42d00c5` | Redis/Redisson 의존성 제거 | #589* |
| 2026-03-15 | — | Core Unit Test Template | #602 |
| 2026-03-17 | `8066cd45` | PostgreSQL chaos tests | #606 |
| 2026-03-18 | `04bd04fa` | PostgreSQL integration tests | #607 |
| 2026-03-23 | `e91501d6` | ClassCastException fix + bulk 최적화 | #614 |

### Phase 7: Direct DB (2026.03 말)

| 날짜 | 해시 | 내용 | PR |
|------|------|------|-----|
| 2026-03-28 | `6756cb75` | Direct DB 토글 서비스 (ADR-344) | #622 |
| 2026-03-28 | `672d3690` | OCID cache bug + 401 auth | #661 |
| 2026-03-29 | `06952c40` | Fingerprint identity + DB Trigger | #666 |
| 2026-03-29 | `4c9b3652` | Nexon API validation on login | #668 |
| 2026-03-29 | `542f69b4` | Scale-out data integrity 4 P1 | #632-635 |
| 2026-03-31 | `bd9641df` | Merge develop into master | #679 |
