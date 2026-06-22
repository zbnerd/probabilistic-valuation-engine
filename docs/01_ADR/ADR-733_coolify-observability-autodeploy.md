# ADR-733: Observability (cAdvisor restart alert) + apps auto-deploy

- Status: Accepted
- Date: 2026-06-22
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- Phases 1–2 (ADR-731/732) gave infra + apps 3-layer self-healing under Coolify. Visibility into restarts was a noted gap (spec Section 8).

### Problem

- No metric tells us when containers are thrashing (restart loops). The autoheal/restart policies work silently.
- Container stdout (e.g., autoheal restart events) is not collected — promtail only tails module log files.
- App deploys are still manual; push-to-develop should redeploy.
- No single ops document for the Coolify topology.

### Goal

- A restart-rate alert; opt-in container stdout logging; apps auto-deploy; a setup guide.

---

## 2. Decision

> Add cAdvisor for per-container metrics + a `ContainerRestartThrashing` alert on `container_start_time_seconds`; add a promtail `docker_sd_configs` job opt-in via label; enable Coolify auto-deploy for `maple-apps`; write `docs/21_Operations/coolify-setup-guide.md`.

```text
cAdvisor (container_start_time_seconds) → prometheus → ContainerRestartThrashing rule
container stdout (label logging=promtail) → promtail docker_sd → Loki
push to develop → GHCR image → Coolify maple-apps auto-deploy
```

Restart detection: cAdvisor exposes `container_start_time_seconds` (a gauge that jumps on each restart). `changes(...[5m])` counts those jumps; >2 in 5m fires the alert.

---

## 3. Trade-offs

### Sensitivity

* cAdvisor mounts `/` (rootfs) read-only + docker socket paths — broad host read access (standard cAdvisor requirement; read-only).
* promtail now reads the Docker socket (ro) for discovery — same trust class as autoheal.
* `changes()` on a gauge can count non-restart value fluctuations in edge cases; `for: 1m` + threshold >2 dampens false positives.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| cAdvisor `container_start_time_seconds` + `changes()` | per-container restart visibility, no extra exporter | gauge-based counting (not a true restart_counter) |
| opt-in `logging=promtail` label | avoids duplicating file-tailed module logs | only labeled containers' stdout is collected |
| auto-deploy ON for apps | push-to-deploy | bad commit auto-redeploys; mitigated by sha rollback |

### Risk

* alertmanager is referenced by prometheus but NOT running (only in the legacy `docker-compose.observability.yml` overlay). The restart alert will be evaluated and visible in the prometheus UI, but delivery (Slack/email) requires wiring alertmanager — deferred.

### Non-Risk

* cAdvisor itself — covered by `restart: always` (no healthcheck; avoids a false restart-loop on images lacking the probe binary).

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| restart-rate metric (before) | none | silent thrashing |
| container stdout collection (before) | none | file-tailed module logs only |

### Observed Result

Code changes merged (Phase 3). Operator verification (cAdvisor target up, alert fires on a forced-restart test, promtail ships container stdout to Loki) pending on the Coolify host (runbook R-2/R-3 in the Phase 3 plan).

---

## 5. Summary

> Add cAdvisor-based restart alerting, opt-in container stdout logging, apps auto-deploy, and a setup guide to complete Coolify self-healing observability.
