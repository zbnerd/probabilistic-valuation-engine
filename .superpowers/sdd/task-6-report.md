# Task 6 Report

## Status

DONE

- Task base: `d440dcd975872896af2d45a4cfc4688a077c2ba4`
- Commit message: `refactor: classify artifact runs before cleanup`
- Scope: exhaustive typed run catalog, bounded typed retention, startup publication recovery discovery/metrics, and cleanup-service migration.

## Result

- `ArtifactRunCatalog` now exhausts the typed `runs/` or `calculator/runs/` page stream before grouping any candidates. It records total object size, parses run creation time, preserves endpoint manifest/state detail, associates the legacy root marker only with `ranking-overall`, recognizes endpoint and nested descendant markers, and derives the most protective aggregate state.
- Invalid run IDs or artifact topology remain classified and protected. Publication-pending and active runs never reach retention; stale incomplete and published runs continue through the existing `RunCleanupExecutor` safeguards.
- `ArtifactRetentionService` preserves `keepRecent`, `keepWithinHours`, `maxDeleteRunsPerCycle`, `maxDeleteBytesPerCycle`, and `maxRuntimeSeconds`. Deletion accepts only a catalog-produced source/calculator run root and sends an exact trailing-slash typed prefix to `deleteByPrefix`.
- `ArtifactStorageAutoConfiguration` explicitly registers one catalog and one retention bean.
- `PendingPublicationRecovery` performs source-only startup discovery once, offloads the full listing to the owned artifact executor, returns from readiness immediately, and composes every publication-pending endpoint through the existing deterministic manifest replay. Legacy ranking and endpoint markers are removed only after their matching replay succeeds.
- Startup recovery records untagged recovered-endpoint counts and pre-registers only `stage=list|replay` failure counters. Listing, publication, incomplete-manifest, and orphan-marker outcomes stay observable without preventing readiness or removing a pending marker.
- `RunCleanupService` no longer scans raw prefixes or checks only root markers. Both controller entrypoints select typed roots, delegate catalog classification and bounded retention, and emit scanned/protected/invalid counts.
- `module-cleanup` now declares its direct `module-pipeline-artifact` dependency for these artifact APIs.

## TDD Evidence

- Catalog/retention RED: the planned command failed compilation because `ArtifactRunCatalog`, `ArtifactRunInfo`, `ArtifactEndpointInfo`, and `ArtifactRetentionService` did not exist.
- Catalog/retention GREEN: 5/5 tests passed, covering both marker topologies, descendant activity, protective aggregation, invalid/incomplete states, 1,001-object pagination, typed-only roots, exact-prefix bounded deletion, calculator retention, and explicit beans.
- Recovery RED: the planned command failed compilation on the missing ready-event callback, catalog/executor/metrics injection, and `PendingPublicationRecoveryMetrics`.
- Recovery GREEN: 7/7 tests passed, covering existing deterministic replay validation plus nonblocking atomic startup, source-only catalog discovery, legacy/endpoint restart recovery, stable UUIDv5 identities, static metric tags, and marker retention on list/replay failure.
- Cleanup RED: the planned command failed on the missing direct artifact dependency and old raw-storage constructor/API.
- Cleanup GREEN: 4/4 tests passed, covering typed source/calculator entrypoints, dry-run behavior, protected/invalid count emission, and exclusion of descendant-active, publication-pending, and invalid runs from deletion.

## Verification

- `./gradlew :module-pipeline-artifact:test --tests '*ArtifactRunCatalogTest'`: 5/5 passed, `BUILD SUCCESSFUL in 17s`.
- `./gradlew :module-external-api:test --tests '*PendingPublicationRecoveryTest'`: 7/7 passed, `BUILD SUCCESSFUL in 26s`.
- `./gradlew :module-cleanup:test --tests '*RunCleanupServiceTest'`: 4/4 passed, `BUILD SUCCESSFUL in 17s`.
- Ordered compile for `module-pipeline-artifact`, `module-external-api`, and `module-cleanup`: 14 tasks, 5 executed and 9 up-to-date, `BUILD SUCCESSFUL in 11s`.
- `git diff --check` passed. Focused forbidden-API/raw-root scans returned no findings in the changed production files.
- Quick `/tmp` scan found no `gzip-chunk-*` or `calc-result-*` files; only pre-existing artifact evidence/log files were present.
- No database command, destructive reset, Testcontainers/integration source set, external mutation, runtime server, or deployment was used.

## Concerns

No implementation concerns. Gradle continued to print the repository's existing non-blocking Java installation discovery warning while all focused tests and the ordered Java 21 compile succeeded.
