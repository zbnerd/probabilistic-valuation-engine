# ADR-749: Active ETL workers own their runtime resources

- Status: Accepted
- Date: 2026-07-19

## Context

After extracting artifact, messaging, valuation, and Nexon seams, active ETL workers still import module-infra for generic execution and lifecycle wiring. The central VT configuration also advertises unused or shadowed beans, so copying it would preserve configuration shape rather than effective behavior.

## Decision

Each executable owns only its active named executors and lifecycle adapters. External-api keeps local loop execution, owns internal/urgent VT executors, and owns scheduler start/stop. Synchronizer owns result/basic VT executors and a workload-named 8/16/200 OCID platform pool. LogicExecutor and TaskContext are not copied. Calculator and artifact resources stay with their already-extracted owners. A Gradle gate rejects source and transitive runtime module-infra dependencies in the four workers.

## Rejected alternatives

A generic worker-runtime module, copying every VtExecutorConfig bean, and changing all pools to one thread model are rejected because they recreate coupling or alter effective production behavior.

## Consequences

Resource ownership and shutdown become visible per executable. App/web continue through module-infra compatibility facades. New active-worker code cannot import or transitively resolve module-infra.

## Evidence

Verification is recorded in docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md.
