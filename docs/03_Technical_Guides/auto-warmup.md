# Auto Warmup - 인기 캐릭터 자동 웜업 (#275)

> **상위 문서**: [CLAUDE.md](../../CLAUDE.md) | [infrastructure.md](infrastructure.md)
>
> **Last Updated:** 2026-02-05
> **Applicable Versions:** Spring Boot 3.5.4, Redis 7.x
> **Documentation Version:** 1.0
> **Production Status:** Active (Validated through V4 cold start incidents)

## Terminology

| 용어 | 정의 |
|------|------|
| **Cold Cache** | 초기 시작 시 캐시가 비어있는 상태 |
| **Warm Cache** | 자주 조회되는 데이터가 캐시된 상태 |
| **PopularCharacterTracker** | 인기 캐릭터 추적 서비스 |
| **ZINCRBY** | Redis Sorted Set 점수 증가 명령 |
| **ZREVRANGE** | Redis Sorted Set 상위 N개 조회 |

## Documentation Integrity Statement

This guide is based on **V4 API cold start performance analysis**:
- Cold cache impact: RPS dropped from 310 to 95 (69% reduction) (Evidence: [N23_V4_API_RESULTS.md](../05_Reports/Cost_Performance/N23_V4_API_RESULTS.md))
- Warmup effectiveness: 3.3x RPS improvement after warmup (95 -> 310 RPS)
- Stateless design: Distributed lock prevents duplicate warmup across instances (Evidence: Issue #275)

---

## 1. 개요

> **Design Rationale:** Pre-warm cache eliminates cold start penalty for popular characters.
> **Why NOT eager load all:** Full load takes >30 minutes; selective warmup of top 100 takes <10 seconds.
> **Known Limitations:** New popular characters (not in yesterday's top N) will experience cold cache on first access.
> **Rollback Plan:** Disable warmup scheduler if Redis Sorted Set memory exceeds 100MB.

V4 API의 Cold Cache 문제를 해결하기 위한 자동 웜업 시스템입니다.
전날 인기 캐릭터 TOP N을 추적하여 서버 시작 시 또는 매일 새벽에 자동으로 캐시를 채웁니다.

### 1.1 문제 상황

> **Performance Evidence:** Load test N23 measured 95 RPS cold vs 310 RPS warm (Evidence: [N23 Results](../05_Reports/Cost_Performance/N23_V4_API_RESULTS.md)).

| 상태 | RPS | P50 Latency | Timeout |
|------|-----|-------------|---------|
| Cold Cache | ~95 | 760ms | 높음 |
| Warm Cache | ~310 | 620ms | 낮음 |

Cold Cache 상태에서 **RPS가 3배 이상 저하**됩니다.

### 1.2 해결책

```
┌─────────────────────────────────────────────────────────────┐
│                    Auto Warmup Flow                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [API Request]                                              │
│       │                                                     │
│       ▼                                                     │
│  ┌─────────────────┐                                        │
│  │ Controller V4   │──────▶ PopularCharacterTracker        │
│  └─────────────────┘        (Redis ZINCRBY)                 │
│                                    │                        │
│                                    ▼                        │
│                        ┌───────────────────────┐            │
│                        │ Redis Sorted Set      │            │
│                        │ popular:characters:   │            │
│                        │ {yyyy-MM-dd}          │            │
│                        └───────────────────────┘            │
│                                    │                        │
│                                    │ (Daily 5AM)            │
│                                    ▼                        │
│                        ┌───────────────────────┐            │
│                        │ WarmupScheduler       │            │
│                        │ (Distributed Lock)    │            │
│                        └───────────────────────┘            │
│                                    │                        │
│                                    ▼                        │
│                        ┌───────────────────────┐            │
│                        │ V4 API Call (force=0) │            │
│                        │ → Cache Populated     │            │
│                        └───────────────────────┘            │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 2. 아키텍처

### 2.1 Stateless 설계 원칙

| 컴포넌트 | Stateless 보장 방법 |
|---------|---------------------|
| PopularCharacterTracker | Redis Sorted Set (모든 인스턴스 공유) |
| WarmupScheduler | 분산 락 (단일 인스턴스만 실행) |
| 호출 기록 | Fire-and-Forget (API 지연 없음) |

### 2.2 Redis 데이터 구조

```
Key:    popular:characters:{yyyy-MM-dd}
Type:   Sorted Set (ZSET)
Score:  호출 횟수
Member: userIgn (캐릭터 닉네임)
TTL:    48시간 (전날 데이터 참조용)
```

**Redis 명령어**:
- `ZINCRBY`: 호출 횟수 증가 (O(log N))
- `ZREVRANGE`: 상위 N개 조회 (O(log N + M))

## 3. 컴포넌트

### 3.1 PopularCharacterTracker

호출 횟수를 Redis에 기록합니다.

```java
@Component
public class PopularCharacterTracker {

    // 호출 기록 (Fire-and-Forget)
    public void recordAccess(String userIgn);

    // 인기 캐릭터 조회 (상위 N개)
    public List<String> getTopCharacters(LocalDate date, int limit);

    // 전날 인기 캐릭터 조회 (웜업용)
    public List<String> getYesterdayTopCharacters(int limit);
}
```

### 3.2 PopularCharacterWarmupScheduler

인기 캐릭터를 자동 웜업합니다.

```java
@Component
@ConditionalOnProperty(name = "scheduler.warmup.enabled", havingValue = "true")
public class PopularCharacterWarmupScheduler {

    // 매일 새벽 5시 웜업
    @Scheduled(cron = "0 0 5 * * *")
    public void dailyWarmup();

    // 서버 시작 후 30초 뒤 초기 웜업
    @Scheduled(initialDelay = 30000, fixedDelay = Long.MAX_VALUE)
    public void initialWarmup();
}
```

## 4. 설정

### 4.1 application.yml

```yaml
scheduler:
  warmup:
    enabled: false           # local: 비활성화
    top-count: 50            # 웜업할 상위 캐릭터 수
    delay-between-ms: 100    # 요청 간 지연 (ms)
```

### 4.2 application-prod.yml

```yaml
scheduler:
  warmup:
    enabled: true            # prod: 활성화
    top-count: 100           # 상위 100개 웜업
    delay-between-ms: 50     # 빠른 웜업
```

## 5. 메트릭

| 메트릭 | 설명 |
|--------|------|
| `warmup.tracker.record{status}` | 호출 기록 성공/실패 |
| `warmup.execution{type,status}` | 웜업 실행 결과 |
| `warmup.duration{type}` | 웜업 소요 시간 |
| `warmup.last.success_count` | 마지막 웜업 성공 수 |
| `warmup.last.fail_count` | 마지막 웜업 실패 수 |

## 6. 5-Agent Council 합의

| Agent | 역할 | 반영 사항 |
|-------|------|-----------|
| 🟢 Green | Performance | 웜업으로 Cold Start 해결, RPS 3x 향상 |
| 🔵 Blue | Architect | Stateless 설계, 분산 락, TTL 관리 |
| 🔴 Red | SRE | 요청 간 지연으로 Thundering Herd 방지 |
| 🟣 Purple | Auditor | LogicExecutor 패턴, 메트릭 추적 |
| 🟡 Yellow | QA | Fire-and-Forget 안전성, 실패 시 무시 |

## 7. 운영 가이드

### 7.1 웜업 상태 확인

```bash
# Redis에서 오늘 인기 캐릭터 조회
redis-cli ZREVRANGE "popular:characters:$(date +%Y-%m-%d)" 0 9 WITHSCORES
```

### 7.2 수동 웜업 트리거

Actuator 엔드포인트는 제공하지 않습니다 (스케줄러 자동 실행).
서버 재시작 시 30초 후 자동 웜업됩니다.

### 7.3 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| 웜업 안됨 | `scheduler.warmup.enabled=false` | prod 프로필 확인 |
| 전날 데이터 없음 | 첫날 운영 | 정상 (다음날부터 웜업) |
| 웜업 중 오류 | DB/Redis 연결 | 로그 확인, 인프라 점검 |

## 8. 관련 문서

- [infrastructure.md](infrastructure.md) - Redis, Cache 설정
- [async-concurrency.md](async-concurrency.md) - 비동기 처리
- [CLAUDE.md](../../CLAUDE.md) - 프로젝트 가이드라인

## Evidence Links
- **PopularCharacterTracker:** `src/main/java/maple/expectation/service/v4/warmup/PopularCharacterTracker.java` (Evidence: [CODE-WARMUP-TRACKER-001])
- **PopularCharacterWarmupScheduler:** `src/main/java/maple/expectation/scheduler/PopularCharacterWarmupScheduler.java` (Evidence: [CODE-WARMUP-SCHEDULER-001])
- **Configuration:** `src/main/resources/application.yml` (scheduler.warmup 섹션) (Evidence: [CONF-WARMUP-001])
- **Performance Report:** `docs/05_Reports/Cost_Performance/N23_V4_API_RESULTS.md` (Cold vs Warm comparison)

## Technical Validity Check

This guide would be invalidated if:
- **Warmup not executing**: scheduler.warmup.enabled setting verification needed
- **Thundering Herd occurring**: delay-between-ms setting verification needed
- **Memory leak**: ZSET TTL setting verification needed
- **Duplicate warmup across instances**: Distributed lock behavior verification needed

### Verification Commands
```bash
# 웜업 스케줄러 확인
find src/main/java -name "*WarmupScheduler.java"

# 웜업 설정 확인
grep -A 5 "scheduler.warmup" src/main/resources/application.yml

# ZSET 패턴 확인
grep -r "popular:characters" src/main/java --include="*.java"

# Warmup metrics 확인
curl -s http://localhost:8080/actuator/metrics/warmup.execution | jq

# Redis ZSET 확인
redis-cli ZREVRANGE "popular:characters:$(date +%Y-%m-%d)" 0 9 WITHSCORES
```

### Related Evidence
- N23 V4 Results: `docs/05_Reports/Cost_Performance/N23_V4_API_RESULTS.md`
- V4 Specification: `docs/api/v4_specification.md`
