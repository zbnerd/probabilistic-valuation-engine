# Task 5 Report

## Status

DONE

- Task base: `a5df4558e414f8c1a3597b21a215941425e35df4`
- Commit message: `fix: retain source run until publication completes`
- Scope: publication-aware source-run lifecycle, required snapshot publication tracking, deterministic manifest replay, and typed phase marker wiring.

## Result

- `RunLifecycle` now owns source marker, manifest, success-marker, replay-read, and final marker-deletion storage operations on the owned artifact upload executor. The fixed order is manifest, `_SUCCESS`, all required publications, then topology-specific `_RUNNING` deletion.
- Ranking retains the legacy `runs/{runId}/_RUNNING` topology. Character-basic and item-equipment retain their endpoint markers. Marker creation completes before a sink is created or fetch work is submitted.
- Every chunk upload receipt feeds a tracked chunk-ready publication future. Run-completed publication waits for the full chunk-publication aggregate, and the source marker remains when any required send fails.
- Manifest writing moved from `SnapshotChunkManifestWriter` to `RunLifecycle`; the manifest and Kafka DTO fields remain unchanged. Initial events retain random IDs and `sha256=null`.
- Artifact-build failure cleanup deletes only the typed chunk, manifest, and failed-record objects and keeps lifecycle markers. Run-failed publication is awaited; its failure is suppressed under the original source failure rather than replacing it.
- `PendingPublicationRecovery` validates the complete manifest before sending, reconstructs stable UUIDv5 chunk/run events from manifest timestamps and typed keys, and deletes only the matching marker after every replay send succeeds.
- `RunMarkerWriter` and its test were removed. The scheduler passes a ranking run ID to `OcidLookupPhase`, which derives the unchanged ranking chunk prefix from `SourceArtifactLayout`.

## TDD Evidence

- Lifecycle RED: `RunLifecycleTest` failed compilation because `RunLifecycle` and `RunState` did not exist.
- Lifecycle GREEN: 6/6 tests passed, covering manifest failure, success-marker failure, required-publication failure, orphan marker state, replay, and legacy ranking topology.
- Publication/recovery RED: the focused external-api command failed on the old wildcard/Unit publisher returns and missing `PendingPublicationRecovery`.
- Publication/recovery GREEN: 19/19 tests passed, covering sync/async publisher failures, tracked chunk/run sends, original-error composition, deterministic replay, strict timestamp/path rejection, endpoint factory keys, and the dataflow contract.
- Phase wiring RED: the focused phase command failed because ranking had not yet received `RunLifecycle`.
- Phase wiring GREEN: 26/26 tests passed, covering exact marker keys and marker-failure no-submit behavior for all source phases, typed OCID input, and scheduler forwarding.

## Verification

- `./gradlew :module-pipeline-artifact:test --tests '*RunLifecycleTest'`: 6/6 passed, `BUILD SUCCESSFUL`.
- Planned snapshot/dataflow command: 19/19 passed, `BUILD SUCCESSFUL in 29s`.
- Planned phase/scheduler command: 26/26 passed, `BUILD SUCCESSFUL in 38s`.
- Final ordered compile of `module-pipeline-artifact` and `module-external-api`: 13 tasks, 4 executed and 9 up-to-date, `BUILD SUCCESSFUL in 47s`.
- Targeted Spotless IDE-hook formatting was applied to touched Kotlin files; the remaining per-file repetition was stopped at the explicit speed-first boundary.
- `git diff --check` passed.
- No database command, destructive reset, Testcontainers source set, external mutation, or deployment was performed.

## Verification Note

After the focused suites passed, the dataflow test's existing blocking wait was replaced with Awaitility-based future observation and unsafe test casts were removed. The final ordered compile covers those test-source changes; the suites were not rerun after that test-only cleanup because the speed-first stop explicitly directed immediate compile, report, and commit.

## Concerns

None in the production implementation.
