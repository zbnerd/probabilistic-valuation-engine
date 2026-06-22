# ADR-731: Coolify-managed infra with 3-layer self-healing

- Status: Accepted
- Date: 2026-06-22
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- PR #1324 dockerized the 4 services + 8 infra containers. ADR-720 names Coolify + Docker Compose as the deployment layer.
- Investigation 2026-06-22: the `maple-*` containers run as plain `docker compose up`, NOT under Coolify (no Coolify labels). So Sentinel, the Coolify UI, and git auto-deploy ignore them.

### Problem

- Hard crashes (OOM, JVM panic) recover via Docker `restart: always` — handled.
- **Soft failures (process alive, `/health` DOWN) do not recover** — Docker restart fires only on exit, and Coolify v4.x has no K8s-style liveness probe. This is the gap.
- 5 infra containers (kafka, redis, grafana, prometheus, promtail) have no healthcheck.

### Goal

- Every infra container self-heals on both hard crash and soft failure, under Coolify management.

---

## 2. Decision

> Move infra under Coolify as a Docker Compose resource; add healthchecks everywhere; add an `autoheal` sidecar that restarts unhealthy containers.

```text
L1 Docker restart: always       → process exit → instant restart
L2 Coolify Sentinel             → stopped container → restart (server setting)
L3 autoheal sidecar             → health_status=unhealthy → docker restart
```

`maple-network` becomes `external` so the future `maple-apps` resource shares it.

---

## 3. Trade-offs

### Sensitivity

* autoheal mounts `/var/run/docker.sock` rw — host-Docker root-equivalent (same trust level Coolify already has).
* `start_period` tuning per service (redis 10s … kafka 60s, apps 90s in Phase 2) — too short → boot-time restart loops.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| autoheal sidecar over pure Coolify-native | guaranteed unhealthy→restart (soft-failure coverage) | extra container + socket mount |
| external network over owned | shared with future apps resource; survives single-resource down | manual one-time `docker network create` |

### Risk

* autoheal itself dies → no L3. Mitigated by `restart: always` + Coolify UI visibility (a socket-presence healthcheck surfaces autoheal's state).
* prometheus runs `network_mode: host` (to reach host Spring Boot ports via localhost); its healthcheck probes the host's `:9090/-/healthy` (a prometheus-specific path). It is intentionally NOT a member of `maple-network`, so it does not conflict with the future `maple-apps` resource. L3 coverage for prometheus is weaker (host-namespace probe) — accepted trade-off vs re-networking prometheus out of Phase 1 scope.
* external `maple-network` survives resource deletion — manual lifecycle (create on first deploy, `docker network rm` on full teardown). Documented in the Phase 3 ops guide.

### Non-Risk

* Data loss on Coolify takeover — named volumes reused, data preserved.
* minio-bootstrap re-run — idempotent (SA created only if missing or `--rotate`), key files rewritten with same values.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| containers without healthcheck (before) | 5 | kafka, redis, grafana, prometheus, promtail |
| soft-failure auto-recovery (before) | 0 | Docker restart ignores unhealthy |
| soft-failure auto-recovery (target) | all persistent containers | via autoheal |

### Observed Result

Code changes merged (Phase 1). Operator live-migration + force-kill/force-unhealthy recovery verification pending on the Coolify host (runbook R-5/R-6 in the Phase 1 plan).

---

## 5. Summary

> Run infra under Coolify with healthchecks on every container and an autoheal sidecar, so both hard crashes and soft failures self-heal.
