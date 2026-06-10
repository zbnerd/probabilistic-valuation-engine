# VS3 Validation Report — TEMPLATE

This file is the human-readable template. The validation wrapper script
generates a per-run report at `vs3-validation-{timestamp}.md` from this
template + the runtime JSON.

## Acceptance Criteria Checklist (issue #1218)

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | STORAGE_BACKEND=minio set; 5 modules restart cleanly | ☐ |  |
| 2 | `mc ls local/maple-expectation/` confirms bucket; 4 lifecycle rules | ☐ |  |
| 3 | `curl :9000/minio/health/ready` returns 200 | ☐ |  |
| 4 | All 5 modules' `/actuator/health` UP including `MinioHealthIndicator` | ☐ |  |
| 5 | E2E: 202 → `Calculation completed with result saved` → 0 ERROR | ☐ |  |
| 6 | MinIO console shows objects under expected prefixes | ☐ |  |
| 7 | Load-test: RPS + p99 recorded (no baseline comparison) | ☐ |  |
| 8 | No `ObjectStorage` errors beyond normal noise | ☐ |  |
| 9 | Cleanup dry-run via `POST /api/internal/cleanup/runs?dry-run=true` returns 2xx | ☐ |  |
| 10 | Snapshot resume path verified via `<SNAPSHOT_RESUME_CMD>` | ☐ |  |
| 11 | Chaos: stop MinIO → DOWN → start → UP within 2m | ☐ |  |

## Per-step timing

(Filled by script — see runtime report.)

## VS4 entry criteria

- [ ] All 11 criteria above marked ☐→☑
- [ ] JSON report committed at `docs/reports/vs3-validation-{ts}.json`
- [ ] ADR-725 supersede note merged
