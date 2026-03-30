# Portfolio — Probabilistic Valuation Engine

> **Backend Engineer (Java 21 / Spring Boot 3.5)** — 공통 인프라 설계 + 장애 격리 + 데이터 생존

## 10-Second Summary

- **Java 21 + Spring Boot 3.5.4** 기반 연산 백엔드
- **7개 공통 인프라 모듈**을 직접 설계하여 35+ 서비스에서 재사용 가능한 구조로 구현
- 장애 격리(Circuit Breaker + Fallback), 데이터 생존(Outbox), 비용-성능 최적점 분석
- **498개 테스트** (Unit 90+ / Integration 20+ / Chaos 24 시나리오)로 검증

## Why This Matters for Platform Engineering

| 관점 | 증명하는 것 |
|------|-----------|
| **공통 기능 도출** | 반복되는 패턴(예외 처리, 캐시, 락, 제한)을 식별하고 재사용 가능한 모듈로 추출 |
| **다른 개발자의 생산성** | LogicExecutor 하나로 35+ 서비스의 try-catch 스파게티 제거 |
| **트러블슈팅 경험** | 예외 오분류 버그 발견 → 3-tier 분류 정책 설계 → 12개 회귀 테스트 |
| **장애 검증** | Chaos Engineering(Nightmare N01-N24)로 시스템 복원 탄력성 테스트 |
| **데이터 생존** | Outbox + Replay로 장애 시 데이터 유실 방지 검증 |

---

## 1. 공통 인프라 모듈 (Platform Components)

> **"다른 개발자들이 불편한 것들을 모아서 공통화"** — 7개 모듈을 직접 설계했습니다.

### LogicExecutor — Cross-Cutting 실행 프레임워크

**문제:** 35+ 서비스에서 try-catch 패턴이 제각각 → 장애 시 에러 추적 불가, 메트릭 수집 누락

**해결:** 예외 처리 + 메트릭 수집 + 로깅을 한곳에서 관리하는 공통 실행기

```java
// Before: 서비스마다 다른 try-catch 패턴
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);  // 어떤 서비스? 어떤 작업? 추적 불가
    return null;
}

// After: LogicExecutor — 6가지 실행 패턴, 자동 메트릭/로깅
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", id)  // 자동 추적 가능
);
```

**6가지 실행 패턴:** `execute`, `executeVoid`, `executeOrDefault`, `executeWithRecovery`, `executeWithFinally`, `executeWithTranslation`

**임팩트:** 35+ 서비스 적용, 에러 분류 자동화, 서비스별 성능 메트릭 자동 수집

### ResilientLockStrategy — 장애 격리 락 전략

**문제:** Redis 장애 시 전체 서비스 중단 — 락을 쓰는 모든 서비스가 영향

**해결:** Redis 실패 → MySQL fallback + CircuitBreaker 자동 전환

```
정상: Redis Lock (빠름)
  ↓ Redis 장애 감지
자동 전환: MySQL Named Lock (안전)
  ↓ CircuitBreaker Half-Open
자동 복구: Redis Lock (빠름)
```

**3-tier 예외 분류 정책:**
- 인프라 예외 (RedisException 등) → MySQL fallback 발동
- 비즈니스 예외 (ClientBaseException 등) → fallback 없이 즉시 전파
- 알 수 없는 예외 (NPE 등) → 보수적 처리 (fallback 안 함)

**임팩트:** Redis 장애가 비즈니스 로직에 전파되지 않음, 12개 회귀 테스트로 정책 검증

### TieredCache — 3계층 캐시 + Stampede 방지

```
L1 HIT: < 5ms   (Caffeine 로컬 메모리)
L2 HIT: < 20ms  (Redis)
MISS:   Singleflight로 1회만 DB 호출 → 나머지 대기 후 결과 공유
```

**효과:** Cache Stampede 완전 방지, DB 쿼리 비율 ≤ 10%

### 나머지 공통 모듈

| 모듈 | 역할 | 핵심 설계 |
|------|------|----------|
| **IdempotencyGuard** | SETNX 기반 멱등성 보장 | PROCESSING → COMPLETED 상태 머신 |
| **PartitionedFlushStrategy** | 분산 락 + 보상 트랜잭션 | 락 실패 시 데이터 복원, 부분 실패 처리 |
| **Rate Limiting 3-tier** | API 보호 (Facade → Service → Strategy) | 계층별 독립 정책 적용 가능 |
| **WriteBackBuffer** | 비동기 쓰기 버퍼 | ACK/NACK + DLQ + 재시도 |

---

## 2. 트러블슈팅 경험

### Issue #130: 비즈니스 예외가 인프라 장애로 오분류

**증상:** `CharacterNotFoundException`(비즈니스 예외)이 발생했는데 MySQL fallback이 동작함

**원인 분석:**
- 비동기 실행 중 비즈니스 예외가 `CompletionException`으로 래핑
- 예외 분류 로직이 래핑된 예외를 인프라 장애로 판단
- → 불필요한 MySQL fallback 발동 → MySQL 부하 증가

**해결:**
- 3-tier 예외 분류 정책 도입 (인프라 / 비즈니스 / 알 수 없음)
- `CompletionException` unwrap 로직 추가
- 12개 회귀 테스트 작성 (ResilientLockStrategyExceptionFilterTest)

**배움:** "예외 분류는 설계의 영역이지, catch-all로 해결할 문제가 아니다."

📄 [Postmortem Report](docs/postmortem/ISSUE-130-Exception-Misclassification.md)

---

## 3. 성능 분석 + 비용 최적화

### 로컬 부하 테스트 결과

| 메트릭 | 값 | 비고 |
|--------|-----|------|
| **RPS** | 965 | 요청당 200~300KB (고부하 시나리오) |
| **p50** | 95ms | |
| **p99** | 214ms | |
| **Error Rate** | 0% | 1,000+ 동시 사용자 시뮬레이션 |

> **참고:** 이 수치는 로컬 환경에서 wrk로 측정한 벤치마크 결과입니다. 실제 운영 경험은 아니며, 장애 시나리오 검증과 성능 병목 파악을 목적으로 했습니다.

### 비용-성능 최적점 분석 (N23)

| 인스턴스 | 월 비용 | RPS | $/RPS | 판단 |
|---------|--------|-----|-------|------|
| t3.small | $15 | 965 | $0.0155 | 기준 |
| t3.medium | $30 | 1,928 | $0.0156 | 선형 확장 |
| **t3.large** | **$45** | **2,989** | **$0.0151** | **최적** ✅ |
| t3.xlarge | $75 | 3,058 | $0.0245 | -37% 비효율 |

**의사결정:** 비용 대비 효율이 꺾이는 지점을 찾아 최적점 선택

📄 [Cost Performance Report](docs/05_Reports/Cost_Performance/COST_PERF_REPORT_N23.md)

### 최적화 성과

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| JSON 압축 | 350KB | 17KB | 95% |
| 동시 요청 처리 | 5.3s | 1.1s | 4.8x |
| DB 인덱스 튜닝 | 0.98s | 0.02s | 50x |
| 메모리 사용량 | 300MB | 30MB | 90% |

---

## 4. 모니터링 + 장애 검증

### 현재 구현

- **Prometheus:** 커스텀 메트릭 수집 (CircuitBreaker 상태, Lock 획득 시간, Queue 적체량)
- **Discord Alert:** 장애 등급별 채널 분리, 증거(PromQL 결과값) 포함 알림
- **Chaos Tests:** Nightmare N01-N24로 장애 시나리오 자동 검증

### 데이터 유실 방지 검증 (N19)

- 시뮬레이션: 외부 API 6시간 장애 → **2,100,874개 이벤트 누적**
- Transactional Outbox + File Backup 3중 안전망 작동
- 복구 후 자동 재처리 **99.98%**, 수동 개입 **0**

📄 [Recovery Report](docs/05_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)

### 자동 장애 완화 검증 (N21)

1. **탐지:** `hikaricp_connections_active = 30/30` (100% 포화)
2. **자동 차단:** Circuit Breaker OPEN (실패율 61% > 임계치 50%)
3. **자동 복구:** Half-Open 전환 → p99 21초 → 3초 복구
4. **운영자 대응 시간:** 0분 (알림만 확인)

📄 [Incident Report](docs/05_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md)

---

## 5. 테스트 전략

| 카테고리 | 규모 | 특징 |
|----------|------|------|
| **Unit Tests** | 90+ 파일 | Mock 기반, 순수 로직 검증 |
| **Integration Tests** | 20+ 파일 | Testcontainers (MySQL/Redis) |
| **Chaos Tests** | 24 시나리오 | Nightmare N01-N24 |
| **Total** | **498 @Test** | CI: Unit Only (3분) / Nightly: Full (60분) |

**주목할 테스트 패턴:**
- ResilientLockStrategyExceptionFilterTest: 3-tier 예외 분류 12개 시나리오
- InMemoryBufferStrategyTest: CyclicBarrier 기반 동시성 검증 (5 threads × 100 msgs, 중복 0)
- CostFormatterTest: Spring 의존성 0, 순수 단위 테스트 (~1ms 실행)

### Chaos Engineering 결과

| 테스트 | 시나리오 | 결과 |
|--------|---------|------|
| **N01** | Thundering Herd (Cache Stampede) | **PASS** |
| **N02** | Deadlock Trap | **FAIL→FIX** (Lock Ordering 적용) |
| **N03** | Thread Pool Exhaustion | **FAIL→FIX** (AbortPolicy + Bulkhead) |
| **N04** | Connection Vampire | **CONDITIONAL** (트랜잭션 범위 분리 권장) |
| **N05** | Celebrity Problem (Hot Key) | **PASS** |
| **N06** | Timeout Cascade | **FAIL→FIX** (타임아웃 계층 정렬) |
| **N19** | Outbox Replay | **PASS** (210만 건 유실 0) |
| **N21** | Auto Mitigation | **PASS** (MTTD 30s, MTTR 4m) |
| **N23** | Cost Performance | **PASS** (비용 최적점 도출) |

---

## Tech Stack

| 분야 | 기술 |
|------|------|
| **Core** | Java 21, Spring Boot 3.5.4 |
| **Database** | MySQL 8.0, JPA/Hibernate |
| **Cache** | Caffeine (L1), Redis/Redisson 3.27.0 (L2) |
| **Resilience** | Resilience4j 2.2.0 (Circuit Breaker, Retry, TimeLimiter) |
| **Testing** | JUnit 5, Testcontainers, wrk |
| **Monitoring** | Prometheus, Grafana, Discord Alert |

## Domain Note

- 데이터 도메인: MMORPG economy simulation (예시 도메인)
- **핵심은 공통 인프라 설계 + 장애 격리 + 데이터 생존**
- Codename: `probabilistic-valuation-engine` (내부 문서)

## Links

- [Full README](README.md)
- [Architecture](docs/00_Start_Here/architecture.md)
- [Chaos Tests](docs/02_Chaos_Engineering/06_Nightmare/)
- [Postmortem: Issue #130](docs/postmortem/ISSUE-130-Exception-Misclassification.md)

---

*Last Updated: 2026-02-17*
