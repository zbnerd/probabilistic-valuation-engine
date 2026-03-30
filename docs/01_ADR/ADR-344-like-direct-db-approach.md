# ADR-344: Like Toggle Direct DB Approach

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-28 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board |
| 선행 ADR | ADR-006 Scale-out Strategy, ADR-022 Redis Dependency Removal |

---

## 1. 배경 (Context)

### 문제 상황

V3 Like Endpoints 플랜(Caffeine in-memory relation map + PGMQ)에 대해 Critic, Code-Reviewer, Architect 3개 에이전트 컨센서스 리뷰 결과, 다음 치명적 문제가 발견됨:

1. **Scale-out 차단**: 인스턴스별 Caffeine map이 ADR-006(stateless) 위반
2. **인스턴스 장애 시 데이터 유실**: In-memory buffer crash 시 복구 불가
3. **복잡도 과잉**: 현재 like QPS = 5-20/sec에서 Buffer+PGMQ+Worker 패턴은 오버엔지니어링

### 요구사항

- 좋아요 토글 (LIKE/UNLIKE) API
- 좋아요 상태 조회 API
- Self-like 방지
- Scale-out ready (stateless)
- ACID 트랜잭션 보장

---

## 2. 결정 (Decision)

### Direct DB 트랜잭션 방식 채택

```
User → Controller → @Transactional { exists check → INSERT/DELETE + UPDATE count } → Return
```

### Phase 계획

| Phase | 방식 | 조건 |
|-------|------|------|
| Phase 1 | Direct DB Transaction | 당장 구현 (QPS < 100) |
| Phase 2 | TieredCache Read Path | 읽기 성능 최적화 필요 시 |
| Phase 3 | PGMQ + Buffer | QPS > 100 시점 |

---

## 3. 근거 (Rationale)

### Direct DB vs In-Memory Buffer 비교

| 항목 | Direct DB | In-Memory Buffer |
|------|-----------|------------------|
| 지연 | ~10-20ms | ~1ms |
| 일관성 | ACID (즉시) | 결과적 (1-5초) |
| Scale-out | 즉시 가능 | 차단 (상태 동기화 필요) |
| 복잡도 | 낮음 (단일 트랜잭션) | 높음 (Buffer + Queue + Worker) |
| 장애 내성 | DB만 정상이면 OK | 인스턴스 장애 시 데이터 유실 |

### 성능 근거

- ADR-002: Like sync QPS = 5-20/sec
- Direct DB write (indexed, READ_COMMITTED) = ~10-20ms
- 사용자 인지 임계값 = ~100ms
- 5-20 QPS에서 10-20ms 응답은 사용자 경험에 영향 없음

---

## 4. 결과 (Consequences)

### 긍정적

- Stateless → Scale-out ready (ADR-006 준수)
- ACID 트랜잭션 → 데이터 일관성 보장
- PostgreSQL 단일 의존 → ADR-022 준수
- 구현 복잡도 최소 → 유지보수 용이

### 부정적 (한계)

- QPS 100+ 시 DB 병목 가능 → Phase 3에서 Buffer 도입으로 해결
- 읽기 시 매번 DB 쿼리 → Phase 2에서 TieredCache로 해결

---

## 5. 구현 세부

### IGN→OCID Resolution

- `GameCharacterRepository.findByUserIgn()` + `@Cacheable(CacheType.OCID)` (30min TTL)
- NexonApiClient 호출 없음 (동기, 외부 API 의존성 없음)

### Like Toggle

- `@Transactional("transactionManager")` 단일 트랜잭션
- exists check → INSERT/DELETE + incrementLikeCount
- UNIQUE constraint (`uk_target_liker`)로 중복 방지

### Self-Like 방지

- `AuthenticatedUser.isMyCharacter(targetOcid)` 검증
- 기존 `SelfLikeNotAllowedException` 활용

---

## 6. 관련 파일

| 파일 | 역할 |
|------|------|
| `CharacterLikeRepository.kt` | Like 관계 CRUD 포트 |
| `GameCharacterRepository.kt` | incrementLikeCount |
| `AuthenticatedUser.kt` | isMyCharacter() |
| `CacheType.kt` | OCID 캐시 (30min TTL) |
| `CharacterLikeJpaEntity.kt` | unique constraint uk_target_liker |
| `SelfLikeNotAllowedException.kt` | Self-like 예외 |
