# ADR-745: Pipeline artifact identity and lifecycle ownership

- Status: Accepted
- Date: 2026-07-19

## 1. Context

Artifact keys, LocalFS/MinIO semantics, source finalization, retention, and cleanup inbox durability are split across module-infra and four executable modules. Backend checksums and caller-file ownership are inconsistent.

## 2. Decision

Create module-pipeline-artifact. Keep ObjectStorage in module-common, move its implementations and configuration to the new module, introduce typed layouts and ArtifactReceipt, retain existing object keys, keep required publication failures replayable with _SUCCESS + _RUNNING, and persist cleanup inbox records at cleanup/inbox/{eventId}.json using conditional create.

## 3. Trade-offs

The extraction adds one library module and temporary compatibility facades. It avoids a generic ETL runtime, prevents backend-specific ETag assumptions, makes cleanup restart-safe, and permits each active service to remove storage-related module-infra coupling independently.

## 4. Result and Evidence

Implementation evidence is recorded in docs/05_Reports/2026-07-19-pipeline-artifact-extraction-evidence.md. Existing object keys and event fixtures must remain byte-for-byte compatible.

## 5. Consequences

New ETL code must use typed artifact layouts. module-infra remains the compatibility boundary for app/web callers; it is not a permitted dependency for active ETL storage access.
