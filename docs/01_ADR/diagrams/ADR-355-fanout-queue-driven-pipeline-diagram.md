# ADR-355: Fan-Out Queue-Driven Pipeline — Architecture Diagram

## 1. Module Dependency (Hexagonal Architecture)

```mermaid
graph TD
    subgraph WEB ["module-web (Adapter In)"]
        V5Query["GameCharacterControllerV5<br/>(Query + Command)"]
        V5Status["TaskStatusController<br/>(Polling)"]
        V4Legacy["V4 AdmissionControl<br/>(Legacy Sync)"]
    end

    subgraph CORE ["module-core (Domain)"]
        QPort["CalculationQueuePort"]
        TSPort["TaskStatusPort"]
        CVPort["CharacterViewQueryPort"]
        Receipt["TaskReceipt"]
        TSEnum["TaskStatus Enum"]
        FOPort["EquipmentFanOutPort"]
        EPort["ExecutorPort"]
    end

    subgraph APP ["module-app (Application)"]
        QAdapter["CalculationQueuePortAdapter"]
        TSService["TaskStatusService"]
        CVAdapter["CharacterViewQueryAdapter"]
        Queue["ExpectationCalculationQueue<br/>(backpressure)"]
        PGView["PostgreSQL CharacterView"]
    end

    subgraph INFRA ["module-infra (Infrastructure)"]
        PgmqClient["PgmqClient<br/>(send / read / archive)"]
        RateLimiter["NexonRateLimiter<br/>(ReentrantLock, 50 permits)"]
        Metrics["QueueMetrics<br/>(Gauge)"]
        BatchLoader["NexonFanOutBatchLoader<br/>(Virtual Thread)"]
        ApiWrapper["MetricsNexonApiClientWrapper"]
        subgraph Workers ["PgmqWorker (abstract)"]
            W1["ExpectationCalcWorker (HIGH)"]
            W2["ExpectationCalcLowWorker (LOW)"]
            W3["NexonFanOutWorker"]
            W4["CalculationWorker"]
            W5["DonationWorker"]
        end
    end

    V5Query --> QPort
    V5Query --> CVPort
    V5Query --> FOPort
    V5Status --> TSPort

    QPort -.->|implements| QAdapter
    TSPort -.->|implements| TSService
    CVPort -.->|implements| CVAdapter

    QAdapter --> Queue
    TSService --> PgmqClient
    TSService --> CVPort
    Queue --> PgmqClient
    BatchLoader --> RateLimiter
    ApiWrapper --> RateLimiter
    Workers --> PgmqClient
    Workers --> RateLimiter
```

## 2. V5 Request Flow (Full Lifecycle)

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as V5 Controller
    participant PG as PostgreSQL
    participant Q as ExpectationCalcQueue
    participant PGMQ as PGMQ
    participant W as Worker
    participant RL as NexonRateLimiter

    C->>Ctrl: GET /api/v5/characters/{ign}/expectation

    Note over Ctrl,Pg: Step 1 — Query Side (PostgreSQL First)
    Ctrl->>PG: findByUserIgn(ign)

    alt HIT — Cached Result
        PG-->>Ctrl: CharacterView
        Ctrl-->>C: 200 OK (1-10ms)
    else MISS — Queue Calculation
        PG-->>Ctrl: empty

        Note over Ctrl,W: Step 2 — FanOut Pre-warm (best-effort)
        Ctrl-)W: FanOut preFetchByOcid (async)

        Note over Ctrl,PGMQ: Step 3 — Command Side (Queue)
        Ctrl->>Q: offerWithReceipt(HIGH)
        Q->>PGMQ: send(expectation_high, message)
        PGMQ-->>Q: msg_id = 42
        Q-->>Ctrl: TaskReceipt(taskId=42, queued=true)
        Ctrl-->>C: 202 Accepted<br/>X-Task-Id: 42

        Note over PGMQ,W: Step 4 — Worker Processing
        loop Every 300ms (@Scheduled)
            W->>PGMQ: READ(batch=50, VT=120s, SKIP LOCKED)
            PGMQ-->>W: messages[]
        end

        W->>RL: acquirePermit()
        Note over W: Heavy Calculation
        W->>PG: upsert CharacterView
        W->>PGMQ: archive(msg_id=42)
        W->>RL: releasePermit()
    end
```

## 3. Task Status Polling Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as TaskStatusController
    participant Svc as TaskStatusService
    participant PG as PostgreSQL
    participant PGMQ as PGMQ

    Note over C,PGMQ: Polling Loop — Task Status Check

    C->>Ctrl: GET /api/v5/characters/{ign}/task/42
    Ctrl->>Svc: getStatus(ign, taskId=42)

    Note over Svc,PG: Step 1: PostgreSQL (source of truth)
    Svc->>PG: findByUserIgn(ign)
    PG-->>Svc: empty

    Note over Svc,PGMQ: Step 2: PGMQ Archive Check
    Svc->>PGMQ: isArchived(expectation_high, msgId=42)
    PGMQ-->>Svc: false

    Note over Svc,PGMQ: Step 3: Active Queue Read Count
    Svc->>PGMQ: getMessageReadCount(expectation_high, msgId=42)
    PGMQ-->>Svc: read_ct = 1

    Svc-->>Ctrl: PROCESSING
    Ctrl-->>C: 200 OK<br/>Retry-After: 5<br/>{"taskId":"42","status":"PROCESSING"}

    Note over C,PGMQ: ... client retries after 5 seconds ...

    C->>Ctrl: GET /api/v5/characters/{ign}/task/42
    Ctrl->>Svc: getStatus(ign, taskId=42)

    Note over Svc,PG: Step 1: PostgreSQL (source of truth)
    Svc->>PG: findByUserIgn(ign)
    PG-->>Svc: CharacterView (HIT!)

    Svc-->>Ctrl: COMPLETED
    Ctrl-->>C: 200 OK<br/>{"taskId":"42","status":"COMPLETED"}
```

## 4. PGMQ Queue Architecture (Priority Strategy)

```mermaid
graph TD
    subgraph Backpressure ["ExpectationCalculationQueue"]
        direction LR
        HP["HIGH Priority<br/>max 1,000 msgs"]
        LP["LOW Priority<br/>max 10,000 msgs"]
    end

    subgraph HIGH ["PGMQ: expectation_high"]
        HQ["pgmq.q_expectation_high<br/>batch=50, VT=120s, poll=300ms"]
        HA["pgmq.a_expectation_high<br/>(archive)"]
    end

    subgraph LOW ["PGMQ: expectation_low"]
        LQ["pgmq.q_expectation_low<br/>batch=50, VT=120s, poll=300ms"]
        LA["pgmq.a_expectation_low<br/>(archive)"]
    end

    HW["ExpectationCalcWorker<br/>(HIGH)<br/>User-initiated<br/>Immediate processing"]
    LW["ExpectationCalcLowWorker<br/>(LOW)<br/>Batch/scheduled<br/>Background processing"]

    Calc["Heavy Calculation<br/>(Expectation)"]
    RL["NexonRateLimiter<br/>50 concurrent"]
    Result["PostgreSQL CharacterView<br/>(upsert result)"]

    HP --> HQ
    LP --> LQ
    HQ --> HW
    LQ --> LW
    HW --> Calc
    LW --> Calc
    Calc --> RL
    RL --> Result

    HQ -.->|archive| HA
    LQ -.->|archive| LA

    style HIGH fill:#e8f5e9,stroke:#4caf50
    style LOW fill:#fff3e0,stroke:#ff9800
    style HA fill:#e0e0e0,stroke:#9e9e9e
    style LA fill:#e0e0e0,stroke:#9e9e9e
```

## 5. NexonRateLimiter — Before vs After

```mermaid
graph LR
    subgraph Before ["Before ADR-355 (4 Scattered Semaphores)"]
        direction TB
        B1["MetricsNexonApiClientWrapper<br/>Semaphore(50)"]
        B2["NexonFanOutBatchLoader<br/>Semaphore(30)"]
        B3["Bulkhead<br/>Semaphore(50)"]
        B4["GlobalAdmissionControl<br/>Semaphore(100)"]
        BP["Problem:<br/>VT carrier pinning<br/>분산 관리<br/>No observability"]
    end

    subgraph After ["After ADR-355 (Single ReentrantLock)"]
        direction TB
        RL["NexonRateLimiter<br/>ReentrantLock + Condition<br/>permits = 50"]
        A1["MetricsWrapper<br/>acquirePermit()<br/>releasePermit()"]
        A2["FanOutBatchLoader<br/>acquirePermit()<br/>releasePermit()"]
        A3["FanOutWorker<br/>acquirePermit()<br/>releasePermit()"]
        Gauge["Gauge:<br/>nexon.rate-limit.permits.available"]

        RL --> A1
        RL --> A2
        RL --> A3
    end

    style Before fill:#ffebee,stroke:#f44336
    style After fill:#e8f5e9,stroke:#4caf50
    style BP fill:#ffcdd2,stroke:#f44336
```

## 6. PGMQ Worker Lifecycle (Single Message)

```mermaid
flowchart TD
    Start(["@Scheduled(300ms)<br/>processMessages()"]) --> Read["pgmqClient.read()<br/>batch=50, VT=120s"]
    Read --> Empty{"messages<br/>empty?"}
    Empty -->|Yes| Skip["return (skip)"]
    Empty -->|No| ForEach["forEach message"]

    ForEach --> Process["process(message)"]

    Process --> Result{Result?}

    Result -->|SUCCESS| Archive["pgmqClient.archive()<br/>Move to pgmq.a_queue"]
    Result -->|FAIL| RetryCheck{"readCount<br/>< maxRetries?"}

    RetryCheck -->|Yes| OnFail["onProcessingFailed()<br/>VT expires → auto retry"]
    RetryCheck -->|No| Delete["pgmqClient.delete()<br/>Permanent removal (DLQ)"]

    Archive --> Metrics
    OnFail --> Metrics
    Delete --> Metrics

    Metrics["Batch Metrics:<br/>pgmq.worker.processed{success} += N<br/>pgmq.worker.processed{failed} += N<br/>pgmq.worker.batch.latency record"]

    style Archive fill:#c8e6c9,stroke:#4caf50
    style OnFail fill:#fff9c4,stroke:#ffc107
    style Delete fill:#ffcdd2,stroke:#f44336
```

## 7. V4 vs V5 Comparison

```mermaid
graph LR
    subgraph V4 ["V4 — Legacy Synchronous"]
        direction TB
        V4C["Client"] --> V4Ctrl["Controller"]
        V4Ctrl --> V4AC["GlobalAdmissionControl"]
        V4AC --> V4Sem["Semaphore(100)"]
        V4AC --> V4Calc["Heavy Calc (sync)"]
        V4Calc --> V4Ret["200 OK (client waits)"]

        V4Note["Characteristics:<br/>• Synchronous (blocking)<br/>• No Task Receipt<br/>• No status polling<br/>• Semaphore-based (VT pinning risk)<br/>• No changes from ADR-355"]
    end

    subgraph V5 ["V5 — Queue-Driven Pipeline"]
        direction TB
        V5C["Client"] --> V5Ctrl["V5 Controller"]
        V5Ctrl --> V5PG["PostgreSQL Query"]
        V5PG -->|HIT| V5Hit["200 OK (1-10ms)"]
        V5PG -->|MISS| V5Q["PGMQ Queue"]
        V5Q --> V5W["Worker (async)"]
        V5W --> V5RL["ReentrantLock (50)"]
        V5W --> V5Upsert["Upsert result"]

        V5C --> V5Poll["TaskStatusController"]
        V5Poll --> V5Svc["TaskStatusService"]
        V5Svc --> V5Status["COMPLETED / PROCESSING / PENDING"]

        V5Note["Characteristics:<br/>• Async (202 + Task Receipt)<br/>• X-Task-Id header<br/>• Retry-After: 5<br/>• ReentrantLock (VT safe)<br/>• Observability (metrics)"]
    end

    style V4 fill:#fff3e0,stroke:#ff9800
    style V5 fill:#e8f5e9,stroke:#4caf50
```

## 8. FanOut Pre-Warm Flow (Best-Effort)

```mermaid
flowchart TD
    Start["V5 Controller (MISS path)"] --> FanOutEnabled{"fanOut.enabled?"}
    FanOutEnabled -->|false| QueueOnly["Queue HIGH task (main path)"]
    FanOutEnabled -->|true| Resolve["ocidPort.resolveOcid(userIgn)"]

    Resolve --> OcidNull{"ocid == null?"}
    OcidNull -->|Yes| QueueOnly
    OcidNull -->|No| PreFetch["fanOutPort.preFetchByOcid(ocid)"]

    PreFetch --> Cache{"Cache State?"}
    Cache -->|L1 Caffeine HIT| Instant["instant (0ms)"]
    Cache -->|In-Flight Coalescing| Wait["wait existing request"]
    Cache -->|Fast Lane| Fast["EquipmentFetchProvider<br/>fetchWithCache()"]
    Cache -->|Batch Lane| Batch["NexonFanOutBatchLoader.load()"]

    Batch --> VThread["Virtual Thread Pool<br/>(newVirtualThreadPerTaskExecutor)"]
    VThread --> Acquire["NexonRateLimiter.acquirePermit()"]
    Acquire --> Fetch["nexonApiClient.getItemDataByOcid(ocid)"]
    Fetch --> Release["NexonRateLimiter.releasePermit()"]
    Fetch --> Result2{Result?}
    Result2 -->|SUCCESS| CacheData["cache equipment data"]
    Result2 -->|"429 Rate Limit"| Enqueue["FanOutQueuePort.enqueue(ocid)"]
    Enqueue --> FanoutQ["PGMQ: nexon_fanout_queue"]
    FanoutQ --> FanoutWorker["NexonFanOutWorker<br/>(1~1.3s jitter retry)"]

    QueueOnly --> Note["Best-effort:<br/>실패해도 큐잉은 정상 수행"]

    style CacheData fill:#c8e6c9,stroke:#4caf50
    style Enqueue fill:#fff9c4,stroke:#ffc107
    style Note fill:#e3f2fd,stroke:#2196f3
```

## 9. Observability Stack

```mermaid
graph TD
    Grafana["Grafana Dashboard"]

    subgraph Prometheus ["Prometheus Metrics"]
        direction TB

        subgraph QM ["QueueMetrics (Gauge)"]
            Q1["pgmq.queue.depth{queue=expectation_high}"]
            Q2["pgmq.queue.depth{queue=expectation_low}"]
            Q3["pgmq.queue.depth{queue=fanout_retry}"]
        end

        subgraph WM ["PgmqWorker (Counter + Timer)"]
            W1["pgmq.worker.processed{status=success}"]
            W2["pgmq.worker.processed{status=failed}"]
            W3["pgmq.worker.batch.latency"]
        end

        subgraph RL ["NexonRateLimiter (Gauge)"]
            R1["nexon.rate-limit.permits.available"]
        end
    end

    Grafana --> Prometheus

    style QM fill:#e3f2fd,stroke:#2196f3
    style WM fill:#e8f5e9,stroke:#4caf50
    style RL fill:#fff3e0,stroke:#ff9800
```

## 10. Data Flow Summary (Sequence Diagram)

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctrl as V5 Controller
    participant Q as CalcQueue
    participant PGMQ as PGMQ
    participant W as Worker
    participant PG as PostgreSQL

    rect rgb(240, 248, 255)
        Note over C,PG: Query Side — Cache First
        C->>Ctrl: GET /characters/{ign}/expectation
        Ctrl->>PG: findByUserIgn(ign)
        alt HIT
            PG-->>Ctrl: CharacterView
            Ctrl-->>C: 200 OK (cached)
        else MISS
            PG-->>Ctrl: empty
    end

    rect rgb(255, 248, 240)
        Note over C,PGMQ: Command Side — Queue + Async Processing
        Ctrl->>Q: offerWithReceipt(HIGH)
        Q->>PGMQ: send(expectation_high, msg)
        PGMQ-->>Q: msgId = 42
        Q-->>Ctrl: TaskReceipt(taskId=42)
        Ctrl-->>C: 202 Accepted<br/>X-Task-Id: 42

        W->>PGMQ: READ (SKIP LOCKED, batch=50)
        PGMQ-->>W: messages[]
        W->>PG: calc + upsert CharacterView
        W->>PGMQ: ARCHIVE (msgId=42)
    end

    rect rgb(240, 255, 240)
        Note over C,PG: Polling — Task Status
        C->>Ctrl: GET /characters/{ign}/task/42
        Ctrl->>PG: getStatus(ign, 42)
        PG-->>Ctrl: CharacterView (HIT)
        Ctrl-->>C: 200 OK {"status":"COMPLETED"}
    end
```
