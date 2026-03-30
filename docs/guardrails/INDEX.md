# Guardrails Index

**Version:** 2.0.0 (Kotlin-compatible)
**Last Updated:** 2026-02-25
**Total Patterns:** 86

## Overview

이 문서는 probabilistic-valuation-engine 프로젝트의 모든 가드레일(Guardrails) 패턴을 카테고리별로 정리한 인덱스입니다. 각 가드레일은 `DON'T` (안티패턴)와 `DO` (베스트 프랙티스) 형식으로 작성되었으며, Java와 Kotlin 코드 예시를 포함합니다.

**관련 문서:**
- **INDEX.json:** `INDEX.json` - Hooks 연동용 패턴 인덱스 (regex, keywords, AI judgment)
- **HOOK_GUIDE.md:** `HOOK_GUIDE.md` - Claude Code Hooks 연동 가이드

---

## 카테고리 요약

| 카테고리 | 패턴 수 | 설명 |
|----------|--------|------|
| 🏗️ Architecture | 8 | 아키텍처 설계 원칙과 패턴 |
| 🔧 Backend/Spring | 12 | Spring Framework 관련 가드레일 |
| 🛡️ Backend/Resilience | 8 | 회복 탄력성 및 Circuit Breaker |
| 💾 Backend/Cache | 5 | 캐시 전략 및 TieredCache |
| ⚡ Backend/Concurrency | 7 | 비동기 처리 및 동시성 |
| 🧪 Testing | 7 | 테스트 전략 및 Best Practice |
| 🌪️ Testing/Chaos | 19 | Nightmare N01-N19 시나리오 |
| 🏛️ Infrastructure | 3 | Redis, Scale-out 등 인프라 |
| 🗄️ Database | 2 | 데이터베이스 연결 풀 및 최적화 |
| 🎨 Coding Style | 1 | 코딩 스타일 가이드 |
| 🔒 Security | 9 | 보안 관련 가드레일 |
| 🔄 Architecture/Refactor | 8 | 리팩토링 사례 및 해결 방안 |
| **합계** | **88** | |

---

## 🏗️ Architecture

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-ARCH-001 | [TieredCache](architecture/system-design.md) | warning | TieredCache, Caffeine, Redis, L1, L2 |
| GR-ARCH-002 | [SingleFlight](architecture/system-design.md) | warning | SingleFlight, cache, stampede |
| GR-ARCH-003 | [Stateless](architecture/stateless.md) | critical | HttpSession, stateless, Redis |
| GR-ARCH-003-2 | [Static Mutable](architecture/stateless.md) | critical | static, mutable, state |
| GR-ARCH-005 | [FixedRate Scheduler](architecture/adr-decisions.md) | critical | @Scheduled, fixedRate, fixedDelay |
| GR-ARCH-007 | [JPA IDENTITY](architecture/adr-decisions.md) | warning | JPA, IDENTITY, batch, JDBC |
| GR-ARCH-010 | [V4 to V2 Call](architecture/service-modules.md) | warning | V2, V4, module, dependency |
| GR-ARCH-015 | [Synchronous Drain](architecture/service-modules.md) | warning | Write-Behind, drain, @Scheduled |

---

## 🔧 Backend/Spring

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-001 | [LogicExecutor](backend/spring/logic-executor.md) | critical | try-catch, LogicExecutor, execute |
| GR-002 | [RuntimeException](backend/spring/exception-handling.md) | critical | RuntimeException, exception, ClientBaseException |
| GR-003 | [AOP Self-Invocation](backend/spring/aop-facade.md) | critical | AOP, Facade, self-invocation, CGLIB |
| GR-004 | [Lambda Hell](backend/spring/optional-chaining.md) | warning | lambda, nested, method reference |
| GR-005 | [Null Check](backend/spring/optional-chaining.md) | warning | Optional, null, chaining |
| GR-AOP-001 | [Facade Pattern](backend/spring/aop-facade.md) | critical | Facade, AOP, orchestration |
| GR-AOP-002 | [Filter Bean](backend/spring/aop-facade.md) | critical | OncePerRequestFilter, @Bean, CGLIB |
| GR-AOP-003 | [SecurityContext](backend/spring/aop-facade.md) | critical | SecurityContext, thread-safe |
| GR-AOP-004 | [Sensitive Data](backend/spring/aop-facade.md) | critical | toString, masking, API Key |
| GR-AOP-005 | [API Key JWT](backend/spring/aop-facade.md) | critical | API Key, JWT, Redis, HMAC |
| GR-AOP-006 | [Security Headers](backend/spring/aop-facade.md) | warning | CSP, HSTS, Spring Security |
| GR-LOGIC-001 | [LogicExecutor Patterns](backend/spring/logic-executor.md) | critical | 6 patterns, TaskContext |
| GR-LOGIC-002 | [Lambda 3-Line Rule](backend/spring/logic-executor.md) | warning | lambda, 3 lines, extraction |

---

## 🛡️ Backend/Resilience

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-RESILIENCE-001 | [Circuit Breaker](backend/resilience/circuit-breaker.md) | critical | CircuitBreaker, Resilience4j |
| GR-RESILIENCE-002 | [Marker Interface](backend/resilience/marker-interface.md) | critical | Marker, CircuitBreakerIgnoreMarker |
| GR-RESILIENCE-003 | [Fallback](backend/resilience/fallback.md) | warning | fallback, graceful degradation |
| GR-RESILIENCE-004 | [Redis Sentinel](../../_archive/redis-deprecated/redis-sentinel-readmode.md) | warning | Redis, Sentinel, ReadMode |
| GR-RESILIENCE-005 | [Redis Failover](../../_archive/redis-deprecated/redis-failover-topology.md) | warning | Redis, Failover, Topology |
| GR-RESILIENCE-006 | [Auto Warmup](backend/resilience/auto-warmup-strategy.md) | warning | Warmup, ColdCache, Scheduler |
| GR-RESILIENCE-007 | [Distributed Lock](backend/resilience/distributed-lock-scheduler.md) | critical | Scheduler, DistributedLock, Redis |
| GR-RESILIENCE-008 | [Redis ZSET TTL](../../_archive/redis-deprecated/redis-zset-ttl.md) | warning | Redis, ZSET, TTL, MemoryLeak |

---

## 💾 Backend/Cache

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-CACHE-001 | [Cache Stampede](backend/cache/tiered-cache-singleflight.md) | critical | TieredCache, SingleFlight, stampede |
| GR-CACHE-002 | [Follower Timeout](backend/cache/tiered-cache-singleflight.md) | critical | SingleFlight, follower, timeout |
| GR-CACHE-003 | [Cache Configuration](backend/cache/tiered-cache-singleflight.md) | warning | TTL, maximumSize, OOM |
| GR-CACHE-004 | [Graceful Degradation](backend/cache/tiered-cache-singleflight.md) | critical | Redis failure, fallback |
| GR-CACHE-005 | [Hash Tag](backend/cache/tiered-cache-singleflight.md) | critical | Redis Cluster, Lua, hash tag |

---

## ⚡ Backend/Concurrency

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-CONC-001 | [Async Pipeline](backend/concurrency/async-patterns.md) | critical | CompletableFuture, .join(), async |
| GR-CONC-002 | [Thread Pool](backend/concurrency/thread-pool.md) | critical | CallerRunsPolicy, AbortPolicy |
| GR-CONC-003 | [Virtual Threads](backend/concurrency/virtual-threads.md) | warning | VirtualThread, Java 21 |
| GR-CONC-004 | [Lock Strategy](backend/concurrency/lock-strategy.md) | critical | Redis, MySQL, DistributedLock |
| GR-CONC-005 | [Race Condition](backend/concurrency/race-condition.md) | critical | AtomicUpdate, HotRow, LuaScript |
| GR-CONC-006 | [Deadlock](backend/concurrency/deadlock-prevention.md) | critical | Deadlock, LockTimeout, SKIP_LOCKED |
| GR-CONC-007 | [SKIP LOCKED](backend/concurrency/skip-locked.md) | warning | SKIP_LOCKED, Outbox, batch |

---

## 🧪 Testing

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-TEST-001 | [Thread.sleep](testing/unit-test.md) | critical | Thread.sleep, Awaitility |
| GR-TEST-002 | [awaitTermination](testing/concurrency-test.md) | critical | ExecutorService, shutdown |
| GR-TEST-003 | [@DirtiesContext](testing/unit-test.md) | warning | @DirtiesContext, test isolation |
| GR-TEST-004 | [Testcontainers](testing/unit-test.md) | warning | Testcontainers, @Testcontainers |
| GR-TEST-005 | [Clock Injection](testing/unit-test.md) | warning | Clock, LocalDate.now, time-based |
| GR-TEST-006 | [Random ID](testing/unit-test.md) | warning | UUID, random, Supplier |
| GR-TEST-007 | [@Transactional](testing/concurrency-test.md) | critical | @Transactional, multithread |
| GR-TEST-008 | [Load Test](testing/load-test-strategy.md) | critical | load test, wrk, performance |
| GR-CHAOS-001 | [Chaos Strategy](testing/chaos-engineering.md) | critical | Chaos, 5-Agent, Nightmare |
| GR-NIGHTMARE-001 | [Nightmare N01-N19](testing/nightmare-tests.md) | critical | 19 scenarios, chaos |

---

## 🌪️ Testing/Chaos (Nightmare N01-N19)

| ID | 시나리오 | 심각도 | 키워드 |
|----|----------|--------|--------|
| GR-CHAOS-N01 | Thundering Herd | Critical | SingleFlight, stampede |
| GR-CHAOS-N02 | Deadlock Trap | Critical | Named Lock, ordering |
| GR-CHAOS-N03 | Thread Pool Exhaustion | Critical | ThreadPool, async |
| GR-CHAOS-N04 | Connection Vampire | Critical | Connection Pool, @Transactional |
| GR-CHAOS-N05 | Celebrity Problem | Critical | Hot Key, cache |
| GR-CHAOS-N06 | Timeout Cascade | Critical | Timeout, zombie |
| GR-CHAOS-N07 | MDL Freeze | Critical | Metadata Lock, DDL |
| GR-CHAOS-N08 | Redis Death | Critical | Redis, fallback |
| GR-CHAOS-N09 | Circular Lock | Critical | Coffman conditions |
| GR-CHAOS-N10 | CallerRunsPolicy | Critical | ThreadPool, backpressure |
| GR-CHAOS-N11 | Lock Fallback | High | Lock, HikariCP |
| GR-CHAOS-N12 | Async Context Loss | High | MDC, ThreadLocal |
| GR-CHAOS-N13 | Zombie Outbox | High | Outbox, JVM crash |
| GR-CHAOS-N14 | Pipeline Exception | High | LogicExecutor, silent |
| GR-CHAOS-N15 | AOP Order | Medium | @Order, transaction |
| GR-CHAOS-N16 | Self-Invocation | Medium | AOP, @Cacheable |
| GR-CHAOS-N17 | Poison Pill | Medium | DLQ, HOL blocking |
| GR-CHAOS-N18 | Deep Paging | Medium | OFFSET, cursor |
| GR-CHAOS-N19 | Outbox Replay | Critical | Outbox, API outage |

---

## 🏛️ Infrastructure

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-INFRA-001 | [Redis](infra/redis.md) | critical | Redis, Redisson, Lua, DLQ |
| GR-INFRA-002 | [Scale-out](infra/scaleout.md) | critical | ScaleOut, stateful |
| GR-INFRA-003 | [Resilience](infra/resilience-reliiability.md) | warning | Failover, HA |

---

## 🗄️ Database

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-DB-001 | [Connection Pool](database/connection-pool.md) | critical | HikariCP, MySQL |
| GR-DB-002 | [InnoDB Buffer](database/innodb-buffer-pool.md) | warning | InnoDB, buffer pool |

---

## 🎨 Coding Style

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-STYLE-001 | [FQCN](coding-style/imports.md) | warning | FQCN, import, package |

---

## 🔒 Security

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-SEC-001 | [JWT](security/jwt-security.md) | critical | JWT, token, HMAC |
| GR-SEC-002 | [CORS](security/cors-security.md) | critical | CORS, wildcard |
| GR-SEC-003 | [Input Validation](security/input-validation.md) | critical | Injection, XSS |
| GR-SEC-004 | [Sensitive Logging](security/sensitive-data-logging.md) | critical | Logging, masking |
| GR-SEC-005 | [Secrets](security/secrets-management.md) | critical | Secrets, env, Vault |
| GR-SEC-006 | [Security Filter](security/spring-security-filter.md) | critical | Filter, SecurityContext |
| GR-SEC-007 | [API Client](security/api-client-security.md) | warning | WebClient, onErrorResume |
| GR-SEC-008 | [OWASP Top 10](security/owasp-top10.md) | critical | OWASP, injection |
| GR-SEC-009 | [Security Testing](security/security-testing.md) | critical | SAST, DAST |

---

## 🔄 Architecture/Refactor

리팩토링 과정에서 발견된 안티패턴과 해결 방안:

| ID | 문제 | 해결 방안 |
|----|------|----------|
| GR-REFACTOR-001 | Deadlock | Lock Ordering, TryLock |
| GR-REFACTOR-002 | Transaction | @Transactional 범위 |
| GR-REFACTOR-003 | Timeout Hierarchy | Zombie Request 방지 |
| GR-REFACTOR-004 | Outbox | At-least-once |
| GR-REFACTOR-005 | Circuit Breaker | Resilience4j |
| GR-REFACTOR-006 | SOLID SRP | 단일 책임 분리 |
| GR-REFACTOR-007 | DRY | 중복 제거 |
| GR-REFACTOR-008 | Env Naming | 환경변수 규칙 |

---

## 패턴 ID 체계

| 접두사 | 카테고리 | 범위 |
|--------|----------|------|
| GR-XXX | Core Spring | 001-006 |
| GR-ARCH-XXX | Architecture | 001-015 |
| GR-RESILIENCE-XXX | Resilience | 001-008 |
| GR-TEST-XXX | Testing | 001-008 |
| GR-CONC-XXX | Concurrency | 001-007 |
| GR-CACHE-XXX | Cache | 001-005 |
| GR-AOP-XXX | AOP & Facade | 001-008 |
| GR-LOGIC-XXX | LogicExecutor | 001-002 |
| GR-CHAOS-NXX | Nightmare | 01-19 |
| GR-STYLE-XXX | Code Style | 001 |
| GR-SEC-XXX | Security | 001-009 |
| GR-REFACTOR-XXX | Refactoring | 001-008 |

---

## 심각도별 통계

| 심각도 | 패턴 수 | 비율 |
|--------|--------|------|
| **critical** | 57 | 65% |
| **warning** | 22 | 25% |
| **medium** | 9 | 10% |

---

## 검증 명령어

```bash
# try-catch 사용 확인 (금지)
grep -r "try {" src/main/java --include="*.java" | grep -v "DefaultLogicExecutor"

# RuntimeException 확인 (금지)
grep -r "new RuntimeException" src/main/java --include="*.java"

# Thread.sleep 확인 (금지)
grep -r "Thread.sleep" src/test/java --include="*.java"

# FQCN 확인
grep -r "new [a-z]+\.[A-Z]" src/main/java --include="*.java"
```

---

## 관련 문서

- **CLAUDE.md:** `../../../CLAUDE.md` - 프로젝트 코어 규칙
- **INDEX.json:** `INDEX.json` - Hooks 연동용 패턴 인덱스
- **HOOK_GUIDE.md:** `HOOK_GUIDE.md` - Claude Code Hooks 연동 가이드
- **infrastructure.md:** `../../../03_Technical_Guides/infrastructure.md`
- **testing-guide.md:** `../../../03_Technical_Guides/testing-guide.md`

---

## 변경 로그

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| 2.0.0 | 2026-02-25 | Kotlin 호환, AI 판단 4개 패턴, 88개 패턴, Nightmare N01-N19 |
| 1.3.0 | 2025-02-25 | 초기 27개 패턴 |
