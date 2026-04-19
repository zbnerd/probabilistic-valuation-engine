# V5 High-Level Architecture

> V5 엔드포인트의 서브시스템 간 데이터 흐름 (상세 다이어그램은 [v5-endpoint-data-flow-architecture.md](v5-endpoint-data-flow-architecture.md) 참조)

## Architecture Overview

```mermaid
flowchart TB
    CLIENT["Client<br/>GET expectation / POST recalculate / GET task"]
    CONTROLLER["GameCharacterControllerV5<br/>(module-web)"]
    QUERY_SIDE["Query Side — PostgreSQL Read<br/>(CharacterViewQueryPort → JPA → character_valuation_views)"]
    PREWARM["PreWarm — Best-Effort Async<br/>(OCID Resolution → FanOut → L1/Coalescing/Fast-Batch Lane)"]
    COMMAND_SIDE["Command Side — PGMQ Queue<br/>(CalculationQueuePort → expectation_calc_high / expectation_calc_low)"]
    WORKER_POOL["Worker Pool<br/>(PriorityCalculationExecutor → HIGH/LOW Pools)"]
    CALC_PIPELINE["Calculation Pipeline<br/>(ExpectationV4Port → Nexon API → EquipmentExpectationServiceV4)"]
    VIEW_WRITE["Inline View Write — Same TX<br/>(ViewTransformer → PostgreSQL Upsert)"]
    EVENT_PUBLISH["Event Publishing — Same TX<br/>(CalculationCompletedEvent → PGMQ character-sync)"]
    TASK_POLLING["Task Status Polling<br/>(TaskStatusController → TaskStatusPort → PostgreSQL)"]
    PG_TABLE[("PostgreSQL<br/>character_valuation_views")]

    CLIENT -->|"HTTP Request"| CONTROLLER

    CONTROLLER -->|"HIT"| QUERY_SIDE
    QUERY_SIDE -->|"200 OK (1-10ms)"| CLIENT

    CONTROLLER -->|"MISS → PreWarm<br/>(async)"| PREWARM
    CONTROLLER -->|"MISS → Enqueue"| COMMAND_SIDE
    COMMAND_SIDE -->|"202 Accepted<br/>X-Task-Id"| CLIENT

    COMMAND_SIDE -->|"PGMQ Message"| WORKER_POOL
    WORKER_POOL -->|"preWarmBatch()"| PREWARM
    WORKER_POOL -->|"process()"| CALC_PIPELINE

    CALC_PIPELINE -->|"syncToViewTable()"| VIEW_WRITE
    CALC_PIPELINE -->|"Spring Event"| EVENT_PUBLISH

    VIEW_WRITE --> PG_TABLE
    QUERY_SIDE --> PG_TABLE

    CLIENT -->|"Poll taskId"| TASK_POLLING
    TASK_POLLING --> PG_TABLE

    style PG_TABLE fill:#336791,stroke:#fff,color:#fff
    style CLIENT fill:#264653,stroke:#fff,color:#fff
    style CONTROLLER fill:#2a9d8f,stroke:#fff,color:#fff
    style QUERY_SIDE fill:#e9c46a,stroke:#333,color:#333
    style COMMAND_SIDE fill:#e76f51,stroke:#fff,color:#fff
    style WORKER_POOL fill:#f4a261,stroke:#333,color:#333
    style CALC_PIPELINE fill:#e76f51,stroke:#fff,color:#fff
    style VIEW_WRITE fill:#336791,stroke:#fff,color:#fff
    style EVENT_PUBLISH fill:#e76f51,stroke:#fff,color:#fff
```

## Flow Summary

| Path | Flow | Latency |
|---|---|---|
| **Cache HIT** | Client → Controller → Query Side → PostgreSQL → 200 OK | 1-10ms |
| **Cache MISS** | Client → Controller → PreWarm(async) + Command Side(PGMQ) → 202 Accepted | ~5ms |
| **Worker Processing** | PGMQ → Worker Pool → PreWarm → Calculation Pipeline → Inline View Write(same TX) + Event Publish(same TX) → PostgreSQL | 수초 |
| **Task Polling** | Client → Task Status Polling → PostgreSQL → 200 Retry-After / 200 COMPLETED / 404 | 1-5ms |
