# Task 7 Report

## Status

DONE

- Task base: `cacce11e2190a3d7ee170b79a891d64876d4d003`
- Commit message: `feat: add durable cleanup inbox store`
- Scope: durable cleanup inbox envelope, atomic conditional persistence, semantic replay classification, restart-safe pagination, and explicit artifact-module wiring.

## Result

- `CleanupInboxEntry`, `InboxPutResult`, `CleanupInboxStore`, and `CleanupInboxPage` expose the planned envelope and page-based public contract.
- `ObjectStorageCleanupInboxStore` derives every write from `CleanupInboxLayout.entry(eventId)` before storage access and rejects invalid or mismatched event identities.
- Writes delegate uniqueness to `ConditionalObjectStorage.putIfAbsent`; one concurrent writer creates the object and later identical deliveries classify as replay without replacing the first envelope.
- Replay comparison normalizes and compares only the complete semantic `ChunkConsumedEvent` JSON. Kafka coordinates and `receivedAt` do not participate, while changed event content returns `IntegrityConflict` and preserves the stored object.
- Listing delegates to the typed cleanup prefix and exclusive storage cursor, reads each durable envelope, and preserves lexical page order. Delete and pending count operate entirely from durable storage, so a recreated store sees the same backlog.
- `ArtifactStorageAutoConfiguration` declares exactly one `CleanupInboxStore` bean backed by the selected `ConditionalObjectStorage` and the application `ObjectMapper`.
- Legacy `ConsumedChunkInbox` and `CleanupController` remain unchanged for the later atomic messaging activation task.

## TDD Evidence

- RED: `./gradlew :module-pipeline-artifact:test --tests '*ObjectStorageCleanupInboxStoreTest'` failed at `compileTestKotlin` because the planned inbox types and store were absent (`BUILD FAILED in 5s`).
- First GREEN boundary: production and tests compiled; 4/6 tests passed. The two identical-event replay cases exposed that Jackson's direct in-memory event tree and the event parsed from stored JSON used different numeric node representations for timestamps.
- Minimal correction: canonical incoming events now pass through the same serialize/read-tree boundary as stored events; no test or public-contract expansion was added.
- Final GREEN: 6/6 tests passed, covering concurrent atomic create, delivery-metadata-independent replay, conflict preservation, restart recovery, stable pagination, read/delete/re-list, durable pending count, invalid identity rejection before storage, and exactly one bean.

## Verification

- `./gradlew :module-pipeline-artifact:test --tests '*ObjectStorageCleanupInboxStoreTest'`: 6/6 passed, `BUILD SUCCESSFUL in 15s`.
- The focused test command compiled main and test Kotlin for `module-pipeline-artifact`; no separate module compile was necessary.
- `git diff --check` passed. Focused added-line scans found no blocking future retrieval, `runBlocking`, sleeps/delays, unsafe null assertions, raw `try-catch`, standard output, or legacy cleanup coupling.
- The scoped `/tmp` scan found no `gzip-chunk-*`, `calc-result-*`, or cleanup-inbox temporary files.
- No database command, destructive reset, Testcontainers/integration source set, external mutation, runtime server, or deployment was used.

## Concerns

No implementation concerns. Gradle continued to print the repository's existing non-blocking Java installation discovery warning while the focused Java 21 build and tests succeeded.
