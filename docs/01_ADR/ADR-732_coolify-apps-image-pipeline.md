# ADR-732: Apps under Coolify with CI→GHCR image pipeline

- Status: Accepted
- Date: 2026-06-22
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- Phase 1 (ADR-731) put infra under Coolify with 3-layer self-healing. The 4 app services still run via `nohup java -jar` or manual `docker compose up`, not under Coolify.
- `build.sh` produces local-only images (`maple/<svc>:dev` + `:sha-<7>`). There is no registry, no server-side image source.

### Problem

- Apps are not Coolify-managed → no Sentinel restart, no UI, no auto-deploy, no L3 self-healing.
- MinIO SA keys live at repo-relative `./docker/services/secrets/`. Coolify copies compose to its own deploy dir, so this path resolves differently per resource and the apps cannot reliably read the keys the infra resource's `minio-bootstrap` wrote.

### Goal

- Apps deploy from a registry image via Coolify; SA keys sit on a stable absolute host path shared by both resources; every app has the L3 healthcheck + autoheal label.

---

## 2. Decision

> Build images in CI, push to GHCR; reference them in compose via per-service image env vars; move SA keys to a `SECRETS_DIR`-driven absolute path.

```text
push to develop
  → GitHub Actions: bootJar → build.sh (local maple/<svc>:sha-<7>)
  → re-tag + push ghcr.io/zbnerd/maple-<svc>:{sha-<7>,latest}
Coolify maple-apps resource (image env → ghcr path) → docker compose up
SA keys: bootstrap writes $SECRETS_DIR; apps read $SECRETS_DIR_HOST via secrets: file:
```

---

## 3. Trade-offs

### Sensitivity

* GHCR availability — Coolify deploy blocks if registry unreachable (imagePullBackOff). Mitigation: `:latest` + `:sha` both pushed; pin to sha for audit, latest for auto-deploy.
* `/opt/maple/secrets` must exist + be backed up — losing it = no app SA access.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| CI→GHCR over Coolify-internal build | thin server, sha audit, clean rollback | registry dependency + CI build minutes |
| Per-service `${IMAGE_<SVC>}` env var | full image control, no path-separator bugs | 4 env vars instead of 1 prefix |
| Absolute SA path over repo-relative | shared by both Coolify resources | local dev needs SECRETS_DIR unset (default preserves old behavior) |

### Risk

* GHCR package visibility defaults to inherited/private — Coolify must be authorized to pull. Mitigation: set package visibility + Coolify pull token in ops runbook.

### Non-Risk

* docker-smoke CI job — uses local `maple/<svc>:dev` (IMAGE_* unset); unaffected.
* Local dev — SECRETS_DIR/SECRETS_DIR_HOST unset → defaults to repo-relative path; unchanged workflow.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| apps under Coolify (before) | 0 | nohup/manual compose |
| image source (before) | local only | no registry |

### Observed Result

Code changes merged (Phase 2). Operator live-migration (GHCR package publish on first develop push, `maple-apps` Coolify resource deploy + L3 recovery verification) pending on the Coolify host (runbook R-1..R-6 in the Phase 2 plan).

---

## 5. Summary

> Deploy the 4 apps from GHCR images under Coolify, with SA keys on a shared absolute path and L3 healthchecks, so the app layer gains the same self-healing the infra layer has.
