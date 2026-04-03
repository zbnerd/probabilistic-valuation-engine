# ADR-355: Fan-Out Queue-Driven Pipeline — Architecture Diagram

## 1. Module Dependency (Hexagonal Architecture)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          module-web (Adapter In)                       │
│  ┌──────────────────────┐  ┌────────────────────┐  ┌────────────────┐ │
│  │GameCharacterController│  │TaskStatusController │  │  (V4 Legacy)  │ │
│  │       V5 (Query)      │  │    V5 (Polling)     │  │  AdmissionCtrl│ │
│  └──────────┬────────────┘  └─────────┬──────────┘  └──────┬─────────┘ │
└─────────────┼─────────────────────────┼───────────────────┼───────────┘
              │ depends on (ports)       │                   │
              ▼                         ▼                   │
┌─────────────────────────────────────────────────────────────┼──────────┐
│                     module-core (Domain)                     │         │
│  ┌──────────────────┐ ┌──────────────┐ ┌─────────────────┐ │         │
│  │CalculationQueue  │ │TaskStatusPort│ │CharacterView    │ │         │
│  │     Port         │ │  TaskReceipt │ │  QueryPort      │ │         │
│  └──────────────────┘ └──────────────┘ └─────────────────┘ │         │
│  ┌──────────────────┐ ┌──────────────┐ ┌─────────────────┐ │         │
│  │EquipmentFanOut   │ │ ExecutorPort │ │ TaskStatus Enum │ │         │
│  │     Port         │ │              │ │                 │ │         │
│  └──────────────────┘ └──────────────┘ └─────────────────┘ │         │
└─────────────────────────────┬───────────────────────────────┼─────────┘
                              │ implements                    │
                              ▼                               │
┌─────────────────────────────────────────────────────────────┼─────────┐
│                    module-app (Application)                            │
│  ┌────────────────────┐  ┌─────────────────┐  ┌──────────────────┐   │
│  │CalculationQueue    │  │ TaskStatus      │  │ CharacterView    │   │
│  │  PortAdapter       │  │   Service       │  │  QueryAdapter    │   │
│  └────────┬───────────┘  └───────┬─────────┘  └──────────────────┘   │
│           │                      │                                     │
│  ┌────────▼───────────┐  ┌───────▼─────────┐                         │
│  │ExpectationCalc     │  │ PostgreSQL      │                         │
│  │  ulationQueue      │  │  CharacterView  │                         │
│  └────────┬───────────┘  └─────────────────┘                         │
└───────────┼──────────────────────────────────────────────────────────┘
            │ uses
            ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    module-infra (Infrastructure)                     │
│  ┌────────────┐  ┌───────────────┐  ┌────────────────────────────┐  │
│  │ PgmqClient │  │NexonRate      │  │ PgmqWorker (abstract)      │  │
│  │ (send/read │  │  Limiter      │  │  ┌──────────────────────┐  │  │
│  │  /archive) │  │ (ReentrantLock│  │  │ExpectationCalcWorker │  │  │
│  └────────────┘  │  50 permits)  │  │  │ExpectationCalcLow    │  │  │
│  ┌────────────┐  └───────┬───────┘  │  │NexonFanOutWorker     │  │  │
│  │QueueMetrics│          │          │  │CalculationWorker     │  │  │
│  │ (Gauge)    │          │          │  │DonationWorker        │  │  │
│  └────────────┘          │          │  └──────────────────────┘  │  │
│  ┌────────────────────┐  │          └────────────────────────────┘  │
│  │NexonFanOut         │  │          ┌────────────────────────────┐  │
│  │  BatchLoader       │──┘          │MetricsNexonApiClient      │  │
│  │ (Virtual Thread)   │             │  Wrapper                  │──┘
│  └────────────────────┘             └────────────────────────────┘
└──────────────────────────────────────────────────────────────────────┘
```

## 2. V5 Request Flow (Full Lifecycle)

```
Client                     V5 Controller              PGMQ                Worker              PostgreSQL
  │                            │                        │                    │                     │
  │  GET /characters/{ign}     │                        │                    │                     │
  │  /expectation              │                        │                    │                     │
  │───────────────────────────>│                        │                    │                     │
  │                            │                        │                    │                     │
  │                            │  1. Query PostgreSQL   │                    │                     │
  │                            │  (CharacterView)       │                    │                     │
  │                            │─────────────────────────────────────────────────────────────────>│
  │                            │                        │                    │                     │
  │                            │  2a. HIT → 200 OK     │                    │                     │
  │                            │<─────────────────────────────────────────────────────────────────│
  │  200 OK (cached result)    │                        │                    │                     │
  │<───────────────────────────│                        │                    │                     │
  │                            │                        │                    │                     │
  │  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─                     │
  │                            │                        │                    │                     │
  │                            │  2b. MISS path         │                    │                     │
  │                            │                        │                    │                     │
  │                            │  3. FanOut Pre-warm    │                    │                     │
  │                            │  (best-effort, async)  │                    │                     │
  │                            │────────────────────────────────────────────>│  Nexon API          │
  │                            │                        │                    │  (L1/L2 cache)      │
  │                            │                        │                    │                     │
  │                            │  4. Queue HIGH priority│                    │                     │
  │                            │  offerWithReceipt()    │                    │                     │
  │                            │───────────────────────>│                    │                     │
  │                            │  PGMQ msg_id = 42      │                    │                     │
  │                            │<───────────────────────│                    │                     │
  │                            │                        │                    │                     │
  │  202 Accepted              │                        │                    │                     │
  │  X-Task-Id: 42             │                        │                    │                     │
  │<───────────────────────────│                        │                    │                     │
  │                            │                        │                    │                     │
  │                            │                        │  5. @Scheduled    │                     │
  │                            │                        │  poll (300ms)     │                     │
  │                            │                        │<───────────────────│                     │
  │                            │                        │  READ (SKIP LOCK) │                     │
  │                            │                        │  batch=50, VT=120s│                     │
  │                            │                        │                    │                     │
  │                            │                        │  msg {ign, force}  │                     │
  │                            │                        │───────────────────>│                     │
  │                            │                        │                    │                     │
  │                            │                        │                    │  6. Heavy Calc      │
  │                            │                        │                    │  NexonRateLimiter   │
  │                            │                        │                    │  (50 concurrent)    │
  │                            │                        │                    │                     │
  │                            │                        │                    │  7. Upsert result   │
  │                            │                        │                    │────────────────────>│
  │                            │                        │                    │  PostgreSQL         │
  │                            │                        │                    │  CharacterView      │
  │                            │                        │                    │                     │
  │                            │                        │  8. ARCHIVE        │                     │
  │                            │                        │<───────────────────│                     │
  │                            │                        │  (completed msg)   │                     │
  │                            │                        │                    │                     │
```

## 3. Task Status Polling Flow

```
Client                   TaskStatusController          TaskStatusService         PostgreSQL      PGMQ
  │                            │                            │                       │            │
  │  GET /characters/{ign}     │                            │                       │            │
  │  /task/42                  │                            │                       │            │
  │───────────────────────────>│                            │                       │            │
  │                            │                            │                       │            │
  │                            │  getStatus(ign, taskId=42) │                       │            │
  │                            │───────────────────────────>│                       │            │
  │                            │                            │                       │            │
  │                            │                            │  Step 1: PostgreSQL   │            │
  │                            │                            │  (source of truth)    │            │
  │                            │                            │──────────────────────>│            │
  │                            │                            │                       │            │
  │                            │                            │  ┌─ HIT? ──────────────┐           │
  │                            │                            │  │ → COMPLETED         │           │
  │                            │                            │  └─────────────────────┘           │
  │                            │                            │                       │            │
  │                            │                            │  Step 2: PGMQ archive │            │
  │                            │                            │  isArchived(msgId=42) │            │
  │                            │                            │────────────────────────────────────>│
  │                            │                            │                       │            │
  │                            │                            │  ┌─ archived? ──────────────────────┐│
  │                            │                            │  │ → COMPLETED                      ││
  │                            │                            │  └──────────────────────────────────┘│
  │                            │                            │                       │            │
  │                            │                            │  Step 3: Active queue │            │
  │                            │                            │  getMessageReadCount() │            │
  │                            │                            │────────────────────────────────────>│
  │                            │                            │                       │            │
  │                            │                            │  ┌─ read_ct > 0? ───────────────────┐│
  │                            │                            │  │ → PROCESSING                     ││
  │                            │                            │  ├─ read_ct = 0? ───────────────────┤│
  │                            │                            │  │ → PENDING                        ││
  │                            │                            │  └──────────────────────────────────┘│
  │                            │                            │                       │            │
  │                            │  TaskStatus { PROCESSING } │                       │            │
  │                            │<───────────────────────────│                       │            │
  │                            │                            │                       │            │
  │  200 OK                    │                            │                       │            │
  │  Retry-After: 5            │                            │                       │            │
  │  {"taskId":"42",           │                            │                       │            │
  │   "status":"PROCESSING"}   │                            │                       │            │
  │<───────────────────────────│                            │                       │            │
  │                            │                            │                       │            │
  │  ... client retries ...    │                            │                       │            │
  │                            │                            │                       │            │
  │  GET /characters/{ign}     │                            │                       │            │
  │  /task/42                  │                            │                       │            │
  │───────────────────────────>│                            │                       │            │
  │                            │                            │  Step 1: PostgreSQL   │            │
  │                            │                            │──────────────────────>│            │
  │                            │                            │  → HIT!              │            │
  │                            │                            │                       │            │
  │  200 OK                    │                            │                       │            │
  │  {"taskId":"42",           │                            │                       │            │
  │   "status":"COMPLETED"}    │                            │                       │            │
  │<───────────────────────────│                            │                       │            │
```

## 4. PGMQ Queue Architecture (Priority Strategy)

```
                         ExpectationCalculationQueue
                              (backpressure)
                         ┌─────────────────────────┐
                         │ HIGH: max 1,000 msgs    │
                         │ LOW:  max 10,000 msgs   │
                         └──────────┬──────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼                               ▼
        ┌───────────────────┐           ┌───────────────────┐
        │ PGMQ Queue:       │           │ PGMQ Queue:       │
        │ expectation_high  │           │ expectation_low   │
        │                   │           │                   │
        │ pgmq.q_expect..  │           │ pgmq.q_expect..  │
        │ _high             │           │ _low              │
        │                   │           │                   │
        │ batch = 50        │           │ batch = 50        │
        │ VT = 120s         │           │ VT = 120s         │
        │ poll = 300ms      │           │ poll = 300ms      │
        └─────────┬─────────┘           └─────────┬─────────┘
                  │                               │
                  ▼                               ▼
        ┌───────────────────┐           ┌───────────────────┐
        │ExpectationCalc    │           │ExpectationCalc    │
        │  Worker (HIGH)    │           │  Worker (LOW)     │
        │                   │           │                   │
        │ User-initiated    │           │ Batch/scheduled   │
        │ Immediate proc.   │           │ Background proc.  │
        └─────────┬─────────┘           └─────────┬─────────┘
                  │                               │
                  └───────────────┬───────────────┘
                                  ▼
                        ┌───────────────────┐
                        │  Heavy Calc       │
                        │  (Expectation)    │
                        │                   │
                        │  NexonRateLimiter │
                        │  (50 concurrent)  │
                        └─────────┬─────────┘
                                  │
                                  ▼
                        ┌───────────────────┐
                        │  PostgreSQL       │
                        │  CharacterView    │
                        │  (upsert result)  │
                        └───────────────────┘

        ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─

        PGMQ Archive (after processing):
        ┌───────────────────┐  ┌───────────────────┐
        │ pgmq.a_expect..  │  │ pgmq.a_expect..  │
        │ _high             │  │ _low              │
        │                   │  │                   │
        │ Completed msgs    │  │ Completed msgs    │
        │ (auto cleanup)    │  │ (auto cleanup)    │
        └───────────────────┘  └───────────────────┘
```

## 5. NexonRateLimiter — Centralized Concurrency Control

```
 Before ADR-355 (4 scattered Semaphores):
 ┌────────────────────────────────────────────────────────────────┐
 │                                                                │
 │  MetricsNexonApiClientWrapper ──── Semaphore(50)              │
 │  NexonFanOutBatchLoader     ──── Semaphore(30)              │
 │  Bulkhead                   ──── Semaphore(50)              │
 │  GlobalAdmissionControl     ──── Semaphore(100)             │
 │                                                                │
 │  Problem: VT carrier pinning,分散管理, no observability       │
 └────────────────────────────────────────────────────────────────┘

 After ADR-355 (Single ReentrantLock):
 ┌────────────────────────────────────────────────────────────────┐
 │                                                                │
 │                   NexonRateLimiter                             │
 │               ┌──────────────────┐                             │
 │               │  ReentrantLock   │                             │
 │               │  + Condition     │                             │
 │               │  permits = 50    │                             │
 │               └────────┬─────────┘                             │
 │                        │                                       │
 │          ┌─────────────┼─────────────┐                         │
 │          ▼             ▼             ▼                         │
 │  MetricsWrapper  FanOutBatch   FanOutWorker                   │
 │  .acquirePermit() .acquirePermit() .acquirePermit()           │
 │  .releasePermit() .releasePermit() .releasePermit()           │
 │                                                                │
 │  Gauge: nexon.rate-limit.permits.available                     │
 └────────────────────────────────────────────────────────────────┘
```

## 6. PGMQ Worker Lifecycle (Single Message)

```
 PgmqWorker.processMessages()
 │
 ├─ @Scheduled(300ms) poll
 │   │
 │   ├─ pgmqClient.read(queue, batch=50, VT=120s)
 │   │   │
 │   │   ▼
 │   │   ┌─── empty? ──→ return (skip)
 │   │   │
 │   │   └─── messages ──→ forEach message:
 │   │           │
 │   │           ▼
 │   │       processSingleMessage(message)
 │   │           │
 │   │           ├─ process(message) ──→ SUCCESS
 │   │           │       │
 │   │           │       ▼
 │   │           │   pgmqClient.archive(queue, msgId)
 │   │           │       │
 │   │           │       ▼
 │   │           │   Move to pgmq.a_<queue>
 │   │           │   (permanent record)
 │   │           │
 │   │           ├─ process(message) ──→ FAIL + readCount < maxRetries
 │   │           │       │
 │   │           │       ▼
 │   │           │   onProcessingFailed(message)
 │   │           │       │
 │   │           │       ▼
 │   │           │   VT expires → next poll re-reads
 │   │           │   (automatic retry)
 │   │           │
 │   │           └─ process(message) ──→ FAIL + readCount >= maxRetries
 │   │                   │
 │   │                   ▼
 │   │               pgmqClient.delete(queue, msgId)
 │   │                   │
 │   │                   ▼
 │   │               Permanent removal (DLQ)
 │   │
 │   └─ Batch metrics:
 │       pgmq.worker.processed{status=success} += N
 │       pgmq.worker.processed{status=failed}  += N
 │       pgmq.worker.batch.latency             record(duration)
```

## 7. V4 vs V5 Comparison

```
 ╔══════════════════════════════════════════════════════════════════════╗
 ║                        V4 (Legacy Synchronous)                     ║
 ╠══════════════════════════════════════════════════════════════════════╣
 ║                                                                    ║
 ║  Client ──GET──> Controller ──> GlobalAdmissionControl             ║
 ║                                      │                             ║
 ║                                      ├── Semaphore(100)           ║
 ║                                      ├── Heavy Calc (sync)        ║
 ║                                      └── Return result (200 OK)   ║
 ║                                                                    ║
 ║  Characteristics:                                                  ║
 ║  • Synchronous (client waits)                                      ║
 ║  • No Task Receipt                                                 ║
 ║  • No status polling                                               ║
 ║  • Semaphore-based (VT pinning risk)                               ║
 ║  • No changes from ADR-355                                         ║
 ╚══════════════════════════════════════════════════════════════════════╝

 ╔══════════════════════════════════════════════════════════════════════╗
 ║                     V5 (Queue-Driven Pipeline)                     ║
 ╠══════════════════════════════════════════════════════════════════════╣
 ║                                                                    ║
 ║  Client ──GET──> Controller ──> PostgreSQL (Query Side)            ║
 ║                    │              │                                 ║
 ║                    │              ├── HIT  → 200 OK (1-10ms)       ║
 ║                    │              └── MISS → Queue + 202 Accepted  ║
 ║                    │                                               ║
 ║                    │         Command Side (async):                 ║
 ║                    │         PGMQ HIGH queue ──> Worker            ║
 ║                    │                              │                 ║
 ║                    │                              ├── ReentrantLock ║
 ║                    │                              ├── Heavy Calc    ║
 ║                    │                              └── Upsert result ║
 ║                    │                                               ║
 ║  Client ──GET──> TaskStatusController ──> TaskStatusService        ║
 ║         /task/{id}                          │                      ║
 ║                                             ├── PostgreSQL (COMPLETED)
 ║                                             ├── PGMQ archive (COMPLETED)
 ║                                             ├── read_ct > 0 (PROCESSING)
 ║                                             └── default (PENDING) ║
 ║                                                                    ║
 ║  Characteristics:                                                  ║
 ║  • Async (202 + Task Receipt)                                      ║
 ║  • X-Task-Id header for polling                                    ║
 ║  • Retry-After: 5 (PENDING/PROCESSING)                             ║
 ║  • ReentrantLock (VT safe)                                         ║
 ║  • Observability (QueueMetrics, Worker metrics)                    ║
 ╚══════════════════════════════════════════════════════════════════════╝
```

## 8. FanOut Pre-Warm Flow (Best-Effort)

```
 GameCharacterControllerV5 (MISS path)
 │
 ├── fanOutEnabled? ── YES:
 │       │
 │       ▼
 │   ocidPort.resolveOcid(userIgn)
 │       │
 │       ├── null → skip (no OCID found)
 │       │
 │       └── ocid found
 │               │
 │               ▼
 │           fanOutPort.preFetchByOcid(ocid)
 │               │
 │               ├── L1 Cache (Caffeine) HIT → instant (0ms)
 │               │
 │               ├── In-Flight Coalescing → wait existing request
 │               │
 │               ├── Fast Lane → EquipmentFetchProvider.fetchWithCache()
 │               │
 │               └── Batch Lane → NexonFanOutBatchLoader.load()
 │                       │
 │                       ▼
 │                   Virtual Thread Pool
 │                   (newVirtualThreadPerTaskExecutor)
 │                       │
 │                       ├── NexonRateLimiter.acquirePermit()
 │                       ├── nexonApiClient.getItemDataByOcid(ocid)
 │                       ├── NexonRateLimiter.releasePermit()
 │                       │
 │                       ├── SUCCESS → cache equipment data
 │                       └── 429 → FanOutQueuePort.enqueue(ocid)
 │                                   │
 │                                   ▼
 │                               PGMQ: nexon_fanout_queue
 │                                   │
 │                                   ▼
 │                               NexonFanOutWorker
 │                               (1~1.3s jitter retry)
 │
 ├── Best-effort: 실패해도 큐잉은 정상 수행
 │
 └── Queue HIGH task (main path)
```

## 9. Observability Stack

```
 Prometheus / Grafana
        │
        ├── pgmq.queue.depth{queue=expectation_high}     ← QueueMetrics (Gauge)
        ├── pgmq.queue.depth{queue=expectation_low}      ← QueueMetrics (Gauge)
        ├── pgmq.queue.depth{queue=fanout_retry}         ← QueueMetrics (Gauge)
        │
        ├── pgmq.worker.processed{status=success}        ← PgmqWorker (Counter)
        ├── pgmq.worker.processed{status=failed}         ← PgmqWorker (Counter)
        ├── pgmq.worker.batch.latency                    ← PgmqWorker (Timer)
        │
        └── nexon.rate-limit.permits.available           ← NexonRateLimiter (Gauge)
```

## 10. Data Flow Summary (Sequence Diagram)

```
 ┌──────┐  ┌──────────┐  ┌──────────────┐  ┌──────┐  ┌──────────┐  ┌─────┐
 │Client│  │V5 Ctrl   │  │Queue         │  │ PGMQ │  │Worker    │  │ PG  │
 └──┬───┘  └────┬─────┘  └──────┬───────┘  └───┬──┘  └────┬─────┘  └──┬──┘
    │           │               │               │          │            │
    │ GET /exp  │               │               │          │            │
    │──────────>│               │               │          │            │
    │           │ findByUserIgn │               │          │            │
    │           │───────────────────────────────────────────────────────>│
    │           │               │               │          │            │
    │           │ [HIT] 200 OK  │               │          │            │
    │<──────────│<──────────────────────────────────────────────────────│
    │           │               │               │          │            │
    │           │ [MISS]        │               │          │            │
    │           │ offerWith     │               │          │            │
    │           │ Receipt(HIGH) │               │          │            │
    │           │──────────────>│ send()        │          │            │
    │           │               │──────────────>│          │            │
    │           │               │  msgId=42     │          │            │
    │           │               │<──────────────│          │            │
    │           │               │               │          │            │
    │ 202       │               │               │          │            │
    │ X-Task-Id │               │               │          │            │
    │<──────────│               │               │          │            │
    │           │               │               │          │            │
    │           │               │               │          │            │
    │           │               │               │  poll    │            │
    │           │               │               │<─────────│            │
    │           │               │               │  READ    │            │
    │           │               │               │─────────>│            │
    │           │               │               │          │            │
    │           │               │               │  message │            │
    │           │               │               │<─────────│            │
    │           │               │               │          │            │
    │           │               │               │          │  calc +   │
    │           │               │               │          │  upsert   │
    │           │               │               │          │──────────>│
    │           │               │               │          │            │
    │           │               │               │ ARCHIVE  │            │
    │           │               │               │<─────────│            │
    │           │               │               │          │            │
    │           │               │               │          │            │
    │ GET /task │               │               │          │            │
    │ /42       │               │               │          │            │
    │──────────>│               │               │          │            │
    │           │ getStatus()   │               │          │            │
    │           │───────────────────────────────────────────────────────>│
    │           │               │               │          │  [HIT]     │
    │           │               │               │          │            │
    │ 200 OK    │               │               │          │            │
    │ COMPLETED │               │               │          │            │
    │<──────────│               │               │          │            │
```
