# ADR: Redis 분산 캐시 도입 조건

**상태**: Approved (도입 연기)
**날짜**: 2026-04-19
**영향**: TieredCache, Caffeine L1, PostgreSQL L2, scale-out 아키텍처

---

## 배경

현재 캐시 아키텍처는 **Caffeine L1 (인메모리) + PostgreSQL L2 (UNLOGGED)** 구조입니다.

10K IGN 부하 테스트에서 **단일 서버 7,300 RPS** 달성 (Round 5, 캐시 웜업 후).
이 수치는 단일 인스턴스에서 충분한 성능이며, 현재 트래픽 패턴에서 병목이 아닙니다.

---

## 현재 아키텍처

```
[Request]
    ↓
[L1: Caffeine (인메모리, ~1ms)]
    ↓ miss
[L2: PostgreSQL cache_storage (UNLOGGED, ~3-5ms)]
    ↓ miss
[Calculator → Nexon API → 결과 생성]
    ↓
[L1 + L2 캐시 저장]
```

### 성능 특성

| 항목 | L1 (Caffeine) | L2 (PostgreSQL) |
|------|--------------|-----------------|
| 응답 시간 | ~1ms | ~3-5ms |
| 용량 | JVM 힙 제한 | 디스크 제한 |
| TTL | Caffeine 설정 | expires_at 컬럼 |
| 직렬화 | Java 객체 | GZIP BYTEA |
| 인스턴스 간 공유 | **불가** | 공유 (DB) |

---

## 도입 트리거: 앱 서버 Scale-out

Redis 도입이 필요해지는 시점은 **멀티 서버 환경 전환** 시입니다.

### 문제 시나리오

```
[Server A] 캐시 PUT: userIgn="abc", value=V1  → Caffeine(A)에만 저장
[Server B] 캐시 GET: userIgn="abc"            → Caffeine(B)에 없음 → L2 조회
                                                    → L2에도 없거나 stale → 재계산

[Server A] 캐시 EVICT: userIgn="abc"           → Caffeine(A)에서만 제거
[Server B] 캐시 GET: userIgn="abc"            → Caffeine(B)에 stale 데이터 존재
                                                    → 잘못된 데이터 반환
```

**Caffeine은 JVM 로컬 캐시이므로 인스턴스 간 무효화가 불가능합니다.**
scale-out 시 서버 간 캐시 불일치가 필연적으로 발생합니다.

### 현재 무효화 메커니즘

`PostgresNotifySubscriber`가 PostgreSQL `LISTEN/NOTIFY`로 무효화 이벤트를 브로드캐스트합니다.
그러나 이는 **동일 DB에 연결된 인스턴스**에서만 동작하며, 네트워크 파티션 시 누락 가능합니다.

---

## Redis 도입 시점

| 조건 | 현재 | 도입 필요 |
|------|------|-----------|
| 서버 인스턴스 수 | 1 | **2+** |
| 캐시 일관성 요구 | 단일 서버로 문제없음 | 멀티 서버 간 일관성 필요 |
| RPS 한계 | 7,300 (단일) | 단일 서버 한계 도달 |
| 무효화 방식 | LISTEN/NOTIFY | Redis Pub/Sub 또는 TTL |

**도입 시점**: 앱 서버를 2대 이상 운영하면서 캐시 일관성이 중요한 시점.

---

## Redis 도입 시 아키텍처

```
[Request]
    ↓
[L1: Caffeine (인메모리, ~1ms)]    ← 로컬 캐시 유지 (L1 역할)
    ↓ miss
[L2: Redis (~1-2ms)]               ← PostgreSQL L2를 Redis로 교체
    ↓ miss
[Calculator → 결과 생성]
    ↓
[L1 + Redis 저장]
    ↓
[Redis Pub/Sub → 다른 서버 L1 무효화]
```

### 변경 사항

1. **L2 교체**: `PostgresL2CacheStrategy` → `RedisL2CacheStrategy` 구현
2. **무효화**: Redis Pub/Sub로 인스턴스 간 L1 무효화 브로드캐스트
3. **설정**: `cache.l2.type: redis` 추가 (기존 `postgres`에서 전환)
4. **의존성**: `spring-boot-starter-data-redis` 추가

---

## 현재 도입하지 않는 이유

1. **단일 서버 충분**: 7,300 RPS로 현재 트래픽 처리 가능
2. **인프라 복잡도 증가**: Redis 클러스터 운영, 장애 대응 추가
3. **PostgreSQL L2로 충분**: UNLOGGED 테이블로 캐시 의미론 충족, WAL 오버헤드 없음
4. **의존성 최소화**: Redis 없이도 동작하는 구조 유지 선호

---

## 교훈

1. **YAGNI**: 현재 필요하지 않은 Redis를 미리 도입하면 운영 부채만 증가
2. **트리거 기반 결정**: "나중에 필요할 수도"가 아니라 "scale-out 시점에 필요"로 명확한 기준 설정
3. **인터페이스 분리의 가치**: `L2CacheStrategy` 인터페이스 덕분에 Redis 도입 시 구현체만 추가하면 됨

---

## 참고

- [TieredCache 구현](../../module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt)
- [PostgresL2CacheStrategy](../../module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheStrategy.kt)
- [PostgresNotifySubscriber](../../module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/PostgresNotifySubscriber.kt)
