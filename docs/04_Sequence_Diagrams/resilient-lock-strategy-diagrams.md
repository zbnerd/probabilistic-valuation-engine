# ResilientLockStrategy 구조 및 흐름 다이어그램

> **작성일:** 2026-02-27
> **소스:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/ResilientLockStrategy.kt`

---

## 1. 아키텍처 개요도 (Class Diagram)

```mermaid
classDiagram
    direction TB

    class LockStrategy {
        <<interface>>
        +executeWithLock(key, waitTime, leaseTime, task) T
        +tryLock(key, waitTime, leaseTime) boolean
        +tryLockImmediately(key, leaseTime) boolean
        +unlock(key) void
        +executeWithOrderedLocks(keys, timeout, unit, leaseTime, task) T
    }

    class AbstractLockStrategy {
        <<abstract>>
        #executor: LogicExecutor
        +executeWithLock(key, waitTime, leaseTime, task) T
        +tryLock(key, waitTime, leaseTime) boolean
        #unlockInternal(key) void*
    }

    class ResilientLockStrategy {
        @Primary
        -redisLockStrategy: LockStrategy
        -mysqlLockStrategy: LockStrategy?
        -circuitBreaker: CircuitBreaker
        -fallbackMetrics: LockFallbackMetrics
        +executeWithLock(key, waitTime, leaseTime, task) T
        +tryLock(key, waitTime, leaseTime) boolean
        -handleFallback(t, key, op, mysqlFallback) T
        -isInfrastructureException(cause) boolean
    }

    class RedisDistributedLockStrategy {
        -redissonClient: RedissonClient
        +executeWithLock(key, waitTime, leaseTime, task) T
        +tryLockImmediately(key, leaseTime) boolean
        +unlock(key) void
    }

    class MySqlNamedLockStrategy {
        -jdbcTemplate: JdbcTemplate
        +executeWithLock(key, waitTime, leaseTime, task) T
        +unlock(key) void
    }

    class CircuitBreaker {
        <<Resilience4j>>
        +executeCheckedSupplier(supplier) T
        +state: State
    }

    class LogicExecutor {
        +executeWithFallback(task, fallback, context) T
        +executeWithFinally(task, finalizer, context) T
        +executeOrDefault(task, default, context) T
    }

    LockStrategy <|.. AbstractLockStrategy
    AbstractLockStrategy <|-- ResilientLockStrategy
    LockStrategy <|.. RedisDistributedLockStrategy
    LockStrategy <|.. MySqlNamedLockStrategy
    ResilientLockStrategy --> LockStrategy : redisLockStrategy
    ResilientLockStrategy --> LockStrategy : mysqlLockStrategy?
    ResilientLockStrategy --> CircuitBreaker : uses
    AbstractLockStrategy --> LogicExecutor : uses
```

---

## 2. 3-Tier Lock Architecture

```mermaid
flowchart TB
    subgraph Client["클라이언트 (Scheduler/Service)"]
        REQ[락 요청]
    end

    subgraph ResilientLayer["Tier 0: ResilientLockStrategy"]
        RLS[ResilientLockStrategy]
        CB[Circuit Breaker]
        FH[Fallback Handler]
    end

    subgraph Tier1["Tier 1: Redis (Primary)"]
        RDS[RedisDistributedLockStrategy]
        RL[Redisson RLock]
        WD[Watchdog 자동 갱신]
    end

    subgraph Tier2["Tier 2: MySQL (Fallback)"]
        MLS[MySqlNamedLockStrategy]
        GL[GET_LOCK / RELEASE_LOCK]
        SES[세션 기반 락]
    end

    subgraph Monitoring["모니터링"]
        MET[LockFallbackMetrics]
        LOG[Structured Logging]
    end

    REQ --> RLS
    RLS --> CB
    CB -->|정상| RDS
    CB -->|OPEN/실패| FH
    RDS --> RL
    RL --> WD
    FH --> MLS
    MLS --> GL
    GL --> SES

    RLS -.-> MET
    FH -.-> LOG

    style RLS fill:#4CAF50,color:white
    style CB fill:#FF9800,color:white
    style FH fill:#F44336,color:white
    style RDS fill:#2196F3,color:white
    style MLS fill:#9C27B0,color:white
```

---

## 3. executeWithLock 시퀀스 다이어그램 (정상 흐름)

```mermaid
sequenceDiagram
    autonumber
    participant Client as Scheduler/Service
    participant RLS as ResilientLockStrategy
    participant CB as CircuitBreaker
    participant Redis as RedisDistributedLockStrategy
    participant RLock as Redisson RLock
    participant Task as 비즈니스 Task

    Client->>RLS: executeWithLock(key, waitTime, leaseTime, task)
    RLS->>RLS: removeLockPrefix(key)
    RLS->>RLS: TaskContext 생성

    Note over RLS: executor.executeWithFallback()

    RLS->>CB: executeCheckedSupplier()
    CB->>Redis: executeWithLock(key, waitTime, leaseTime, task)

    Redis->>RLock: tryLock(waitTime, leaseTime)
    RLock-->>Redis: true (락 획득)

    Redis->>Task: task.get() 실행
    Task-->>Redis: 결과 반환

    Redis->>RLock: unlock()
    RLock-->>Redis: 완료

    Redis-->>CB: 결과
    CB-->>RLS: 결과
    RLS-->>Client: 결과

    Note over RLS,RLock: ✅ Redis Primary 경로 성공
```

---

## 4. executeWithLock 시퀀스 다이어그램 (Fallback 흐름)

```mermaid
sequenceDiagram
    autonumber
    participant Client as Scheduler/Service
    participant RLS as ResilientLockStrategy
    participant CB as CircuitBreaker
    participant Redis as RedisDistributedLockStrategy
    participant FH as FallbackHandler
    participant MySQL as MySqlNamedLockStrategy
    participant Task as 비즈니스 Task

    Client->>RLS: executeWithLock(key, waitTime, leaseTime, task)
    RLS->>RLS: removeLockPrefix(key)
    RLS->>RLS: TaskContext 생성

    RLS->>CB: executeCheckedSupplier()
    CB->>Redis: executeWithLock(key, waitTime, leaseTime, task)

    Note over Redis: ❌ Redis 장애 발생
    Redis-->>CB: RedisException / TimeoutException
    CB-->>RLS: Exception

    RLS->>RLS: handleFallback(exception, key, op, mysqlFallback)

    Note over RLS: isInfrastructureException() = true<br/>mysqlLockStrategy != null

    RLS->>RLS: log.warn("Redis failed -> MySQL fallback")

    RLS->>MySQL: executeWithLock(key, waitTime, leaseTime, task)
    MySQL->>MySQL: GET_LOCK(key, timeout)
    MySQL->>Task: task.get() 실행
    Task-->>MySQL: 결과 반환
    MySQL->>MySQL: RELEASE_LOCK(key)
    MySQL-->>RLS: 결과

    RLS-->>Client: 결과

    Note over RLS,MySQL: ⚠️ Fallback 성공 - Redis 장애 시에도 서비스 지속
```

---

## 5. 예외 분기 처리 흐름도

```mermaid
flowchart TD
    START[Exception 발생] --> UNWRAP[ExceptionUtils.unwrapAsyncException]
    UNWRAP --> CAUSE{cause 분석}

    CAUSE -->|InterruptedException| INT[Thread.interrupt]
    INT --> DLE1[throw DistributedLockException]

    CAUSE -->|ClientBaseException| BIZ[비즈니스 예외]
    BIZ --> PROP1[즉시 전파<br/>fallback 금지]

    CAUSE -->|DistributedLockException| INFRA1[인프라 예외]
    CAUSE -->|CallNotPermittedException| INFRA2[인프라 예외]
    CAUSE -->|RedisException| INFRA3[인프라 예외]
    CAUSE -->|RedisTimeoutException| INFRA4[인프라 예외]

    INFRA1 --> MYSQL{mysqlLockStrategy<br/>존재?}
    INFRA2 --> MYSQL
    INFRA3 --> MYSQL
    INFRA4 --> MYSQL

    MYSQL -->|Yes| FALLBACK[MySQL Fallback 실행]
    FALLBACK --> LOG1[log.warn: Redis failed -> MySQL]
    FALLBACK --> SUCCESS[결과 반환]

    MYSQL -->|No| REDIS_ONLY[Redis-only 모드]
    REDIS_ONLY --> LOG2[log.error: MySQL unavailable]
    REDIS_ONLY --> DLE2[throw DistributedLockException]

    CAUSE -->|Unknown| UNKNOWN[알 수 없는 예외]
    UNKNOWN --> LOG3[log.error: Unknown exception]
    UNKNOWN --> PROP2[throw IllegalStateException]

    style START fill:#FF5722,color:white
    style FALLBACK fill:#4CAF50,color:white
    style DLE1 fill:#F44336,color:white
    style DLE2 fill:#F44336,color:white
    style PROP1 fill:#FF9800,color:white
    style PROP2 fill:#FF9800,color:white
```

---

## 6. tryLock vs tryLockImmediately 차이

```mermaid
flowchart LR
    subgraph tryLock["tryLock(key, waitTime, leaseTime)"]
        TL1[락 획득 대기 가능] --> TL2[waitTime 만큼 재시도]
        TL2 --> TL3[성공 시 unlock 필요]
    end

    subgraph tryLockImmediately["tryLockImmediately(key, leaseTime)"]
        TLI1[즉시 락 획득만 시도] --> TLI2[waitTime = 0]
        TLI2 --> TLI3[실패 시 즉시 false]
    end

    subgraph MySQL["MySQL 한계"]
        NOSUP[tryLockImmediately 미지원]
        WHY[세션 기반 락은<br/>즉시 반환 불가]
    end

    tryLock --> |Fallback| MySQL
    tryLockImmediately --> |Fallback| NOSUP
    NOSUP --> WHY
    WHY --> EX[throw DistributedLockException]

    style NOSUP fill:#FF5722,color:white
    style EX fill:#F44336,color:white
```

---

## 7. Circuit Breaker 상태 전이

```mermaid
stateDiagram-v2
    [*] --> CLOSED: 초기 상태

    CLOSED --> OPEN: 실패율 > 임계값<br/>(10회 중 5회 실패)

    OPEN --> HALF_OPEN: 대기 시간 경과<br/>(60초)

    HALF_OPEN --> CLOSED: 성공<br/>(3회 연속)
    HALF_OPEN --> OPEN: 실패<br/>(1회)

    state CLOSED {
        [*] --> 정상요청
        정상요청 --> 통계수집
        통계수집 --> 정상요청
    }

    state OPEN {
        [*] --> 차단
        차단 --> Fallback직행
    }

    state HALF_OPEN {
        [*] --> 탐침요청
        탐침요청 --> 결과분석
    }
```

---

## 8. 다중 락 순서 보장 (executeWithOrderedLocks)

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client
    participant RLS as ResilientLockStrategy
    participant Redis as RedisDistributedLockStrategy
    participant MySQL as MySqlNamedLockStrategy

    Note over Client: keys = ["lock:A", "lock:B", "lock:C"]

    Client->>RLS: executeWithOrderedLocks(keys, timeout, unit, leaseTime, task)

    alt Redis 정상
        RLS->>Redis: executeWithOrderedLocks(keys, ...)
        Note over Redis: 사전 정렬된 순서로 락 획득
        Redis-->>RLS: 결과
    else Redis 실패
        RLS->>MySQL: executeWithOrderedLocks(keys, ...)
        Note over MySQL: 동일 순서로 락 획득
        MySQL-->>RLS: 결과
    end

    RLS-->>Client: 결과

    Note over RLS: 데드락 방지: 항상 동일 순서로 락 획득
```

---

## 9. 핵심 코드 매핑

| 다이어그램 요소 | 소스 코드 위치 |
|----------------|---------------|
| `ResilientLockStrategy` | `ResilientLockStrategy.kt:29` |
| `executeWithLock` | `ResilientLockStrategy.kt:55-81` |
| `handleFallback` | `ResilientLockStrategy.kt:165-223` |
| `isInfrastructureException` | `ResilientLockStrategy.kt:150-155` |
| `tryLock` | `ResilientLockStrategy.kt:86-100` |
| `handleTryLockFallback` | `ResilientLockStrategy.kt:105-143` |
| `executeWithOrderedLocks` | `ResilientLockStrategy.kt:278-312` |
| `CircuitBreaker` | `Resilience4j CircuitBreakerRegistry` |
| `LogicExecutor` | `LogicExecutor.kt` |

---

## 10. 프로덕션 검증 포인트

```mermaid
mindmap
  root((ResilientLockStrategy<br/>검증))
    장애 복구
      Redis 장애 → MySQL 전환
      Circuit Breaker OPEN 감지
      Zero-downtime Fallback
    성능
      Redis: < 1ms 지연
      MySQL Fallback: < 10ms
      Fallback 비율: < 0.1%
    안정성
      중복 실행: 0건
      데드락: 0건
      락 누수: 0건
    관측성
      Structured Logging
      LockFallbackMetrics
      Circuit Breaker 상태
```

---

## 참조 문서

- [Lock Strategy Guide](../03_Technical_Guides/lock-strategy.md)
- [ADR-006: Redis Lock Lease Timeout HA](../01_ADR/ADR-006-redis-lock (ARCHIVED: docs/_archive/redis-deprecated/).md)
- [ADR-310: Redis Lock Migration](../01_ADR/ADR-310-redis-lock-migration.md)
