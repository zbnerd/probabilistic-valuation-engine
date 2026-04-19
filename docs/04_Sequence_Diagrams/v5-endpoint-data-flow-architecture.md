# V5 Endpoint Data Flow Architecture

> V5 CQRS + Async Materialized View 패턴의 전체 데이터 흐름 아키텍처 (ADR-388)

## Architecture Overview

```mermaid
flowchart TB
    subgraph Client["Client"]
        C1["GET /api/v5/characters/{userIgn}/expectation"]
        C2["POST /api/v5/characters/{userIgn}/expectation/recalculate"]
        C3["GET /api/v5/characters/{userIgn}/task/{taskId}"]
    end

    subgraph Controller["GameCharacterControllerV5 (module-web)"]
        direction TB
        GQ["getExpectationV5()"]
        GP["recalculateExpectationV5()"]
        GQ -->|"CompletableFuture<br/>computeExecutor"| PQL["processPostgreSQLCacheFirstLookup()"]
        GP --> PCL["processCacheInvalidation()"]
    end

    subgraph QuerySide["Query Side — PostgreSQL Read"]
        direction TB
        QP["CharacterViewQueryPort<br/>(module-core)"]
        QPA["CharacterViewQueryPortAdapter<br/>(module-infra)"]
        QS["CharacterViewQueryServicePostgres<br/>(module-infra)"]
        JPA["CharacterValuationViewJpaRepository<br/>(Spring Data JPA)"]
        PG_TABLE[("PostgreSQL<br/>character_valuation_views")]
        VM["CharacterViewMapper<br/>→ EquipmentExpectationResponseV5"]

        QP --> QPA --> QS --> JPA --> PG_TABLE
    end

    subgraph PreWarm["PreWarm (Best-Effort, Async)"]
        direction TB
        OCID_RES["CharacterOcidPort.resolveOcid()"]
        FANOUT["EquipmentFanOutPort.preFetchByOcid()"]
        L1["L1 Caffeine Cache"]
        COALESCE["In-Flight Coalescing"]
        FAST_LANE["Fast Lane<br/>EquipmentFetchProvider"]
        BATCH_LANE["Batch Lane<br/>NexonFanOutBatchLoader"]

        OCID_RES --> FANOUT
        FANOUT --> L1
        L1 -->|"MISS"| COALESCE
        COALESCE --> FAST_LANE
        COALESCE --> BATCH_LANE
    end

    subgraph CommandSide["Command Side — PGMQ Queue"]
        direction TB
        CP["CalculationQueuePort<br/>(module-core)"]
        CPA["CalculationQueuePortAdapter<br/>(module-app)"]
        Q["ExpectationCalculationQueue<br/>(module-app)"]
        PGMQ_SEND["PgmqClient.send()"]
        PGMQ_HIGH[("PGMQ<br/>expectation_calc_high")]
        PGMQ_LOW[("PGMQ<br/>expectation_calc_low")]

        CP --> CPA --> Q
        Q -->|"REQUIRES_NEW TX"| PGMQ_SEND
        PGMQ_SEND --> PGMQ_HIGH
        PGMQ_SEND --> PGMQ_LOW
    end

    subgraph WorkerPool["Worker Pool (PriorityCalculationExecutor)"]
        direction TB
        PCE["PriorityCalculationExecutor"]
        HP["HIGH Priority Pool<br/>(Virtual Threads)"]
        LP["LOW Priority Pool<br/>(Virtual Threads)"]
        ECW["ExpectationCalcWorker<br/>(AbstractExpectationCalcWorker)"]
        ECLW["ExpectationCalcLowWorker"]

        PCE --> HP --> ECW
        PCE --> LP --> ECLW
        ECW --> PGMQ_HIGH
        ECLW --> PGMQ_LOW
    end

    subgraph CalculationPipeline["Calculation Pipeline (Same TX)"]
        direction TB
        V4PORT["ExpectationV4Port<br/>→ ExpectationV4PortAdapter"]
        V4SVC["EquipmentExpectationServiceV4<br/>.doCalculateExpectation()"]
        NEXON["Nexon API<br/>Equipment Data Fetch"]
        CALC["Expectation Calculation<br/>(Cube/Starforce/Flame)"]
        PERSIST["persistenceService.saveResults()"]
        INLINE["syncToViewTable()<br/>(Inline View Write)"]
        VT["ViewTransformer<br/>.toEntityFromResponse()"]
        PG_UPSERT["PostgreSQL upsert<br/>(optimistic locking)"]

        V4PORT --> V4SVC
        V4SVC --> NEXON --> CALC --> PERSIST
        PERSIST --> INLINE
        INLINE --> VT --> PG_UPSERT --> PG_TABLE
    end

    subgraph EventPublish["Event Publishing (Same TX)"]
        direction TB
        EVT["CalculationCompletedEvent<br/>(Spring ApplicationEvent)"]
        EL["CalculationCompletedEventListener<br/>@TransactionalEventListener<br/>BEFORE_COMMIT"]
        TEP["TransactionalEventPublisher"]
        PGMQ_SYNC[("PGMQ<br/>character-sync")]

        EVT --> EL --> TEP --> PGMQ_SYNC
    end

    subgraph TaskPolling["Task Status Polling"]
        direction TB
        TSC["TaskStatusController"]
        TSP["TaskStatusPort"]
        TSC --> TSP
        TSP --> PG_TABLE
    end

    C1 --> GQ
    PQL -->|"1. Query"| QP
    PQL -->|"HIT → 200 OK<br/>(1-10ms)"| VM

    PQL -->|"MISS → PreWarm<br/>(async)"| OCID_RES
    PQL -->|"MISS → Enqueue"| CP
    Q -->|"TaskReceipt<br/>(taskId = PGMQ messageId)"| R202["202 Accepted<br/>X-Task-Id header"]

    C2 --> GP
    PCL -->|"1. Invalidate"| QP
    PCL -->|"2. Force Enqueue"| CP

    PGMQ_HIGH --> ECW
    ECW -->|"preWarmBatch()"| OCID_RES
    ECW -->|"process()"| V4PORT

    V4SVC --> INLINE
    V4SVC --> EVT

    C3 --> TSC
    TSP -->|"PENDING/PROCESSING<br/>→ 200 + Retry-After: 5"| C3
    TSP -->|"COMPLETED → 200 OK"| C3
    TSP -->|"NOT_FOUND → 404"| C3

    style PG_TABLE fill:#336791,stroke:#fff,color:#fff
    style PGMQ_HIGH fill:#e76f51,stroke:#fff,color:#fff
    style PGMQ_LOW fill:#e76f51,stroke:#fff,color:#fff
    style PGMQ_SYNC fill:#e76f51,stroke:#fff,color:#fff
    style R202 fill:#2a9d8f,stroke:#fff,color:#fff
    style VM fill:#2a9d8f,stroke:#fff,color:#fff
    style L1 fill:#f4a261,stroke:#333,color:#333
```

## Query Path — Cache HIT Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant CTRL as GameCharacterControllerV5
    participant QP as CharacterViewQueryPortAdapter
    participant PG as PostgreSQL (character_valuation_views)
    participant MAP as CharacterViewMapper

    C->>CTRL: GET /api/v5/characters/{userIgn}/expectation
    CTRL->>CTRL: CompletableFuture.supplyAsync(computeExecutor)
    CTRL->>QP: findByUserIgn(userIgn)
    QP->>PG: findTopByUserIgnOrderByCalculatedAtDescIdDesc()
    PG-->>QP: CharacterValuationViewEntity
    QP-->>CTRL: Optional<CharacterView> (present)
    CTRL->>MAP: toResponseDto(view)
    MAP-->>CTRL: EquipmentExpectationResponseV5
    CTRL-->>C: 200 OK (1-10ms)
```

## Query Path — Cache MISS Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant CTRL as GameCharacterControllerV5
    participant QP as CharacterViewQueryPortAdapter
    participant OCID as CharacterOcidPort
    participant FO as EquipmentFanOutPort
    participant Q as ExpectationCalculationQueue
    participant PGMQ as PGMQ (expectation_calc_high)

    C->>CTRL: GET /api/v5/characters/{userIgn}/expectation
    CTRL->>QP: findByUserIgn(userIgn)
    QP-->>CTRL: Optional.empty() (MISS)

    par PreWarm (async, best-effort)
        CTRL->>OCID: resolveOcid(userIgn)
        OCID-->>CTRL: ocid
        CTRL->>FO: preFetchByOcid(ocid)
        FO-->>FO: L1 → Coalescing → Fast/Batch Lane
    and Queue (blocking)
        CTRL->>Q: offerHighPriorityWithReceipt(userIgn, false)
        Q->>Q: isQueueFull() check
        Q->>Q: findActiveMessageIdByUserIgn() (dedup)
        Q->>PGMQ: send("expectation_calc_high", ExpectationCalcMessage)
        PGMQ-->>Q: messageId
        Q-->>CTRL: TaskReceipt(taskId, queued=true)
    end

    CTRL-->>C: 202 Accepted + X-Task-Id header
```

## Worker Calculation Pipeline

```mermaid
sequenceDiagram
    participant PGMQ as PGMQ (expectation_calc_high)
    participant W as AbstractExpectationCalcWorker
    participant OCID as CharacterOcidPort
    participant FO as EquipmentFanOutPort
    participant V4 as EquipmentExpectationServiceV4
    participant NEXON as Nexon API
    participant VT as ViewTransformer
    participant PG as PostgreSQL (character_valuation_views)
    participant EVT as CalculationCompletedEvent
    participant EL as EventListener (BEFORE_COMMIT)
    participant TEP as TransactionalEventPublisher

    Note over W,PG: Same @Transactional boundary

    PGMQ->>W: poll ExpectationCalcMessage
    W->>W: preWarmBatch() — batch resolve OCIDs + FanOut prefetch

    W->>V4: calculateExpectationAsync(userIgn, force, taskId)
    V4->>NEXON: Fetch equipment data
    NEXON-->>V4: Equipment response
    V4->>V4: Calculate expectation (Cube/Starforce/Flame)
    V4->>V4: persistenceService.saveResults()

    Note over V4,PG: Inline View Write (ADR-388)
    V4->>VT: toEntityFromResponse(userIgn, character, response, taskId)
    VT-->>V4: CharacterValuationViewEntity
    V4->>PG: upsert(entity) — optimistic locking

    Note over V4,TEP: Event Publishing (same TX)
    V4->>EVT: publish CalculationCompletedEvent
    EVT->>EL: @TransactionalEventListener(BEFORE_COMMIT)
    EL->>TEP: publishCalculationCompleted(event)
    TEP->>PGMQ: send("character-sync", EventMessage) — same TX

    Note over W,PG: TX Commit — atomic: calculation + view + event
```

## Task Status Polling Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant TSC as TaskStatusController
    participant TSP as TaskStatusPort
    participant PG as PostgreSQL (character_valuation_views)

    C->>TSC: GET /api/v5/characters/{userIgn}/task/{taskId}
    TSC->>TSP: getStatus(userIgn, taskId)
    TSP->>PG: Check view table for completion

    alt PENDING / PROCESSING
        TSC-->>C: 200 OK + Retry-After: 5
    else COMPLETED
        TSC-->>C: 200 OK {taskId, status: "COMPLETED"}
    else NOT_FOUND
        TSC-->>C: 404 Not Found
    end
```

## Endpoint Summary

| Endpoint | Method | Role | Response |
|---|---|---|---|
| `/api/v5/characters/{userIgn}/expectation` | GET | CQRS Query — PostgreSQL read first | 200 HIT / 202 MISS (queued) |
| `/api/v5/characters/{userIgn}/expectation/recalculate` | POST | Cache invalidation + force recalculate | 202 Accepted |
| `/api/v5/characters/{userIgn}/task/{taskId}` | GET | Async calculation completion polling | 200 + Retry-After / 404 |

## Component Map

| Layer | Component | Module | Responsibility |
|---|---|---|---|
| Web | `GameCharacterControllerV5` | module-web | HTTP endpoint, CompletableFuture dispatch |
| Web | `TaskStatusController` | module-web | Task polling endpoint |
| Core Port | `CharacterViewQueryPort` | module-core | PostgreSQL read abstraction |
| Core Port | `CalculationQueuePort` | module-core | Queue enqueue abstraction |
| Core Port | `ExpectationV4Port` | module-core | Calculation execution abstraction |
| Core Port | `TaskStatusPort` | module-core | Task status query abstraction |
| Core Port | `CharacterOcidPort` | module-core | IGN → OCID resolution |
| Core Port | `EquipmentFanOutPort` | module-core | Equipment prefetch abstraction |
| Adapter | `CharacterViewQueryPortAdapter` | module-infra | JPA Entity → CharacterView adapter |
| Adapter | `CalculationQueuePortAdapter` | module-app | Queue port → ExpectationCalculationQueue |
| Adapter | `ExpectationV4PortAdapter` | module-app | V4 port → EquipmentExpectationServiceV4 |
| Infra | `CharacterViewQueryServicePostgres` | module-infra | PostgreSQL upsert/query with optimistic locking |
| Infra | `ExpectationCalculationQueue` | module-app | PGMQ-backed priority queue with backpressure |
| Infra | `AbstractExpectationCalcWorker` | module-infra | PGMQ consumer + batch prewarm |
| Infra | `PriorityCalculationExecutor` | module-app | HIGH/LOW worker pool lifecycle |
| Service | `EquipmentExpectationServiceV4` | module-app | Core calculation + inline view write |
| Service | `ViewTransformer` | module-app | V4 DTO → ViewEntity transformation |
| Service | `TransactionalEventPublisher` | module-app | Same-TX PGMQ event publishing |

## Key Design Decisions

- **Inline View Write (ADR-388)**: Worker의 계산 트랜잭션 내에서 view 테이블에 직접 upsert. 별도 Consumer 불필요
- **Same TX Atomicity**: 계산 + view write + event publish가 한 트랜잭션에서 원자적 수행. 하나라도 실패하면 전체 롤백
- **PGMQ taskId**: PGMQ messageId를 taskId로 활용하여 클라이언트 폴링 가능
- **PreWarm Best-Effort**: FanOut prefetch는 실패해도 큐잉에 영향 없음
- **Priority Isolation**: HIGH/LOW 워커 풀이 완전 분리되어 starvation 방지
