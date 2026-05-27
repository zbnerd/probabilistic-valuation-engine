# Documentation Index

Technical documentation for the Probabilistic Valuation Engine — a MapleStory item valuation data pipeline.

## Architecture & Design

| Document | Description |
|----------|-------------|
| [Architecture](architecture.md) | System architecture, Claim Check Pattern, chunk processing, idempotency, cleanup lifecycle |
| [ADR Directory](01_ADR/) | Architecture Decision Records |

## Operations

| Document | Description |
|----------|-------------|
| [Operations Manual](operations.md) | Startup, shutdown, health checks, troubleshooting, replay procedures |
| [Metrics Reference](metrics-summary.md) | Prometheus metric catalog, PromQL queries, typical values |

## Reports

| Document | Description |
|----------|-------------|
| [82h Endurance Report](endurance-report.md) | Stability test results: throughput, memory, disk, cron verification |
| [Incident History](incident-history.md) | Postmortems: disk exhaustion, semaphore leaks, executor misalignment |

## Technical Guides

| Topic | Location |
|-------|----------|
| Infrastructure (Cache, Security) | [docs/03_Technical_Guides/infrastructure.md](03_Technical_Guides/infrastructure.md) |
| Async & Concurrency | [docs/03_Technical_Guides/async-concurrency.md](03_Technical_Guides/async-concurrency.md) |
| Testing Guide | [docs/03_Technical_Guides/testing-guide.md](03_Technical_Guides/testing-guide.md) |
| Service Modules | [docs/03_Technical_Guides/service-modules.md](03_Technical_Guides/service-modules.md) |
| Performance Journey | [docs/06_Performance_Journey/](06_Performance_Journey/) |
| Deep Dive Textbook | [docs/07_Deep_Dive_Textbook/](07_Deep_Dive_Textbook/) |
| Scale-out Analysis | [docs/05_Reports/](05_Reports/) |
| Observability | [docs/11_Observability/](11_Observability/) |
| Event Schema | [docs/12_Events/](12_Events/) |
| Guardrails | [docs/16_Guardrails/](16_Guardrails/) |
| Operations Guide | [docs/21_Operations/](21_Operations/) |
