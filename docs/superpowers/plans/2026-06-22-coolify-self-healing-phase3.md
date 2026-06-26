# Coolify Self-Healing — Phase 3 (Automation + Observability + Ops Guide) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close out the Coolify self-healing rollout — add a restart-rate alert backed by cAdvisor, collect container stdout logs (opt-in) via promtail Docker discovery, enable apps git auto-deploy in Coolify, and write the operator setup guide that ties Phases 1–3 together.

**Architecture:** cAdvisor runs on the infra resource and exposes per-container metrics; prometheus (host-network) scrapes it via the host port and evaluates a `ContainerRestartThrashing` rule on `container_start_time_seconds`. promtail gains a `docker_sd_configs` job that tails stdout of containers labeled `logging=promtail` (opt-in, avoids duplicating the file-tailed module logs). The `maple-apps` Coolify resource gets auto-deploy ON (push to `develop` → CI image → redeploy). A single ops guide documents the whole 3-resource topology + recovery procedures.

**Tech Stack:** cAdvisor (`gcr.io/cadvisor/cadvisor`), prometheus + alert_rules.yml, promtail `docker_sd_configs`, Coolify v4.1.2 auto-deploy.

**Prerequisites:** Phase 1 (infra under Coolify + autoheal) AND Phase 2 (apps under Coolify + GHCR images) merged and deployed. See `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase1.md` and `-phase2.md`.

**Scope:** Phase 3 ONLY. Edits touch `docker-compose.yml` (cAdvisor + promtail socket mount), `docker/prometheus/prometheus.yml` (cAdvisor scrape), `docker/prometheus/rules/alert_rules.yml` (restart alert), `docker/promtail/config.yml` (docker_sd job), and a new ops guide. **Pre-existing gaps noted but NOT fixed here:** alertmanager is referenced by `prometheus.yml` but only defined in the legacy `docker-compose.observability.yml` overlay (not running); node-exporter likewise. Those are a separate cleanup.

**Branch:** `feat/coolify-self-healing` (continue on the Phase-1/2 branch).

**Working directory:** `/home/maple/probabilistic-valuation-engine`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `docs/01_ADR/ADR-733_coolify-observability-autodeploy.md` | Create | ADR: cAdvisor restart-rate alert, promtail docker discovery, auto-deploy, ops guide; records the alertmanager/node-exporter pre-existing gaps |
| `docker-compose.yml` | Modify | (1) add `cadvisor` service; (2) add docker-socket mount to `promtail`; (3) add `logging: promtail` label to `autoheal` + `cadvisor` (opt-in stdout logging) |
| `docker/prometheus/prometheus.yml` | Modify | Add `cadvisor` scrape job |
| `docker/prometheus/rules/alert_rules.yml` | Modify | Add `ContainerRestartThrashing` rule |
| `docker/promtail/config.yml` | Modify | Add `docker_containers` `docker_sd_configs` job (opt-in label) |
| `docs/21_Operations/coolify-setup-guide.md` | Create | Operator guide for the full 3-resource Coolify topology + recovery |

---

## Task P3-1: ADR-733 — observability + auto-deploy

**Files:**
- Create: `docs/01_ADR/ADR-733_coolify-observability-autodeploy.md`

- [ ] **Step 1: Write the ADR**

Create `docs/01_ADR/ADR-733_coolify-observability-autodeploy.md`:

```markdown
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

To be filled after Phase 3 verification (cAdvisor scrape up, alert fires on a forced-restart test, promtail ships container stdout to Loki).

---

## 5. Summary

> Add cAdvisor-based restart alerting, opt-in container stdout logging, apps auto-deploy, and a setup guide to complete Coolify self-healing observability.
```

- [ ] **Step 2: Commit**

```bash
git add docs/01_ADR/ADR-733_coolify-observability-autodeploy.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-733 observability (cAdvisor restart alert) + auto-deploy

Records Phase-3 decisions: cAdvisor + ContainerRestartThrashing on
container_start_time_seconds changes(), opt-in promtail docker_sd,
maple-apps auto-deploy, and the ops guide. Notes the alertmanager /
node-exporter pre-existing gaps (legacy overlay, not running).

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task P3-2: cAdvisor service + prometheus scrape + restart alert

**Files:**
- Modify: `docker-compose.yml` (add `cadvisor` service)
- Modify: `docker/prometheus/prometheus.yml` (add scrape job)
- Modify: `docker/prometheus/rules/alert_rules.yml` (add alert rule)

- [ ] **Step 1: Add the `cadvisor` service to `docker-compose.yml`**

Add this as a new top-level service (place it right after `prometheus:` so the observability exporters sit together, before `postgres:`):

```yaml
  # cAdvisor — per-container metrics for restart-rate alerting (ADR-733).
  # Exposes container_start_time_seconds (a gauge that jumps on each restart);
  # prometheus evaluates ContainerRestartThrashing via changes() on it.
  # Host port 8086 (container internal 8080) to avoid app-port conflicts.
  cadvisor:
    image: gcr.io/cadvisor/cadvisor:latest
    container_name: maple-cadvisor
    restart: always
    volumes:
      - /:/rootfs:ro
      - /var/run:/var/run:ro
      - /sys:/sys:ro
      - /var/lib/docker/:/var/lib/docker:ro
      - /dev/disk/:/dev/disk:ro
    devices:
      - /dev/kmsg
    ports:
      - "8086:8080"
    # No healthcheck (grill-me fix B): cAdvisor is observability infra, not
    # business-critical — crash recovery is restart: always below. A healthcheck
    # would risk a false restart-loop on images whose busybox lacks the probe
    # binary, and autoheal only acts on unhealthy state anyway. See ADR-733.
    labels:
      logging: "promtail"
    networks:
      - maple-network
```

(`logging: promtail` opts cAdvisor's stdout into the promtail docker job added in P3-3.)

- [ ] **Step 2: Add the cAdvisor scrape job to prometheus.yml**

In `docker/prometheus/prometheus.yml`, the `scrape_configs:` block ends after the `node-exporter` job (around the `# Node Exporter` section). Add a new job after it. prometheus runs with `network_mode: host`, so it reaches cAdvisor via the host port `localhost:8086` (same pattern as the module scrapes):

```yaml
  # ============================================
  # cAdvisor - per-container metrics (restart-rate alerting, ADR-733)
  # Reached via the host port (prometheus uses network_mode: host).
  # ============================================
  - job_name: 'cadvisor'
    scrape_interval: 15s
    scrape_timeout: 10s
    metrics_path: '/metrics'
    static_configs:
      - targets: ['localhost:8086']
        labels:
          component: 'containers'
```

- [ ] **Step 3: Add the `ContainerRestartThrashing` alert rule**

In `docker/prometheus/rules/alert_rules.yml`, the `rules:` list (under `groups: - name: maple-expectation-alerts`) contains entries like `HighCpuUsage`, `HighMemoryUsage`, etc. Append one more rule to that SAME `rules:` list (match the existing 4-space indent for the `- alert:` key, 6-space for fields):

```yaml
      # ============================================
      # Container Restart Thrashing (ADR-733)
      # cAdvisor's container_start_time_seconds jumps on each container
      # restart; changes() counts those jumps over the window.
      # ============================================
      - alert: ContainerRestartThrashing
        expr: changes(container_start_time_seconds{container!="",container!="POD"}[5m]) > 2
        for: 1m
        labels:
          severity: warning
          category: system
        annotations:
          summary: "Container {{ $labels.name }} is restarting repeatedly"
          description: "{{ $labels.name }} restarted {{ $value }} times in the last 5m"
```

- [ ] **Step 4: Validate compose + YAML parse**

Run:
```bash
docker compose -f docker-compose.yml config >/dev/null && echo "COMPOSE OK"
python3 -c "import yaml; yaml.safe_load(open('docker/prometheus/prometheus.yml')); yaml.safe_load(open('docker/prometheus/rules/alert_rules.yml')); print('PROM YAML OK')"
```
Expected: prints `COMPOSE OK` then `PROM YAML OK`.

- [ ] **Step 5: Verify cAdvisor is scraped + the alert rule loads**

Bring up infra (Phase 1 already made `maple-network` external):
```bash
docker compose -f docker-compose.yml up -d cadvisor prometheus
# wait ~30s, then:
curl -s http://localhost:8086/metrics | grep -m1 container_start_time_seconds
curl -s http://localhost:9090/api/v1/targets | grep -o '"cadvisor"[^}]*"health":"up"' | head -1
curl -s "http://localhost:9090/api/v1/rules" | grep -o 'ContainerRestartThrashing'
```
Expected: the first curl prints a `container_start_time_seconds ...` line; the targets query shows cadvisor `health: up`; the rules query prints `ContainerRestartThrashing`. Tear down:
```bash
docker compose -f docker-compose.yml down
```

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml docker/prometheus/prometheus.yml docker/prometheus/rules/alert_rules.yml
git commit -m "$(cat <<'EOF'
feat(observability): cAdvisor + ContainerRestartThrashing alert

Add cAdvisor (host port 8086) exposing container_start_time_seconds,
a prometheus scrape job, and a ContainerRestartThrashing rule
(changes() > 2 in 5m) so restart loops are visible/alerted. cAdvisor
carries the autoheal + logging=promtail labels.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task P3-3: promtail Docker discovery (opt-in container stdout)

**Files:**
- Modify: `docker/promtail/config.yml` (add `docker_containers` scrape job)
- Modify: `docker-compose.yml` (promtail: add docker-socket mount + `logging: promtail` label to autoheal)

- [ ] **Step 1: Add the `docker_containers` job to promtail config**

In `docker/promtail/config.yml`, the `scrape_configs:` list currently has `module_logs` and `artifact_logs`. Append a new job at the end of `scrape_configs:` (same indent as the existing `- job_name:` entries):

```yaml
  # Container stdout collection (ADR-733). opt-in: only containers carrying
  # the label logging=promtail are scraped, so the file-tailed module logs
  # above are NOT duplicated. Targets the docker socket.
  - job_name: docker_containers
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 30s
        filters:
          - name: label
            values: ["logging=promtail"]
    relabel_configs:
      - source_labels: ["__meta_docker_container_name"]
        regex: '/(.*)'
        target_label: container
      - source_labels: ["__meta_docker_container_label_com_docker_compose_service"]
        target_label: service
    pipeline_stages:
      - json:
          expressions:
            level: level
            message: message
            timestamp: timestamp
          on_error: continue
      - labels:
          level:
      - timestamp:
          source: timestamp
          format: '2006-01-02T15:04:05.999999999Z07:00'
          on_failure: continue
```

- [ ] **Step 2: Mount the Docker socket into promtail**

In `docker-compose.yml`, the `promtail:` service `volumes:` block currently is:

```yaml
    volumes:
      - ./docker/promtail/config.yml:/etc/promtail/config.yml:ro
      - ./:/var/log/app:ro
      - ./data:/var/log/data:ro
```

Add one mount (read-only socket):

```yaml
    volumes:
      - ./docker/promtail/config.yml:/etc/promtail/config.yml:ro
      - ./:/var/log/app:ro
      - ./data:/var/log/data:ro
      # Docker socket for docker_sd_configs container-stdout discovery (ADR-733).
      - /var/run/docker.sock:/var/run/docker.sock:ro
```

- [ ] **Step 3: Add the `logging: promtail` label to `autoheal`**

In `docker-compose.yml`, the `autoheal:` service currently has `labels: autoheal: "true"` (added in Phase 1 Task 4). Extend that `labels:` block so autoheal's stdout is also collected:

```yaml
    labels:
      autoheal: "true"
      logging: "promtail"
```

(cAdvisor already got `logging: "promtail"` in P3-2 Step 1. The 4 apps stay UNLABELED — their logs are already file-tailed by `module_logs`.)

- [ ] **Step 4: Validate parse**

Run:
```bash
docker compose -f docker-compose.yml config >/dev/null && echo "COMPOSE OK"
python3 -c "import yaml; yaml.safe_load(open('docker/promtail/config.yml')); print('PROMTAIL YAML OK')"
```
Expected: `COMPOSE OK` then `PROMTAIL YAML OK`.

- [ ] **Step 5: Verify promtail discovers the labeled containers**

Bring up the relevant containers + promtail:
```bash
docker compose -f docker-compose.yml up -d autoheal cadvisor loki promtail
# wait ~30s for discovery refresh, then check promtail targets:
curl -s http://localhost:9080/targets 2>/dev/null | grep -oE 'container=[a-z-]+' | sort -u
# Or check Loki received a container-labeled stream:
curl -s -G "http://localhost:3100/loki/api/v1/labels" 2>/dev/null | head
```
Expected: the targets output includes `container=maple-autoheal` and `container=maple-cadvisor`. (If `curl :9080/targets` returns no parseable output, check `docker logs maple-promtail` for `docker_containers` discovery lines.) Tear down:
```bash
docker compose -f docker-compose.yml down
```

- [ ] **Step 6: Commit**

```bash
git add docker/promtail/config.yml docker-compose.yml
git commit -m "$(cat <<'EOF'
feat(observability): promtail docker_sd for opt-in container stdout

Add a docker_containers docker_sd_configs job (label logging=promtail)
and mount the Docker socket read-only into promtail. Opt-in: only
autoheal + cadvisor stdout is collected; module logs stay file-tailed
(unduplicated).

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task P3-4: Operator setup guide

**Files:**
- Create: `docs/21_Operations/coolify-setup-guide.md`

- [ ] **Step 1: Write the guide**

Create `docs/21_Operations/coolify-setup-guide.md`:

````markdown
# Coolify Setup Guide — Self-Healing Maple Stack

How the maple stack runs under Coolify (3-layer self-healing) and how to operate it. Covers Phases 1–3 (ADRs 731, 732, 733).

## Topology

Two Coolify **Docker Compose** resources, one shared external network, one autoheal sidecar.

| Resource | Compose file | Containers | Auto-deploy |
|----------|--------------|------------|-------------|
| `maple-infra` | `docker-compose.yml` | postgres, minio, kafka, redis, prometheus, grafana, loki, promtail, cadvisor, autoheal, minio-bootstrap | OFF (manual) |
| `maple-apps` | `docker-compose.services.yml` | external-api, calculator, synchronizer, cleanup | ON (push to `develop`) |

Network: `maple-network` is **external** — create once before first deploy:
```bash
docker network create --subnet=172.20.0.0/16 maple-network
```

## 3-Layer self-healing

| Layer | Owner | Fires on | Action |
|-------|-------|----------|--------|
| L1 Docker restart policy | Docker daemon | container exit | instant restart |
| L2 Coolify Sentinel | Coolify server setting | stopped/abnormal-exit container | restart |
| L3 autoheal | autoheal sidecar | `health_status=unhealthy` (labeled containers) | `docker restart` |

Every persistent container has a `healthcheck` + the `autoheal: "true"` label. Excluded: `minio-bootstrap` (one-shot), `autoheal` (cannot restart itself).

## Secrets

| Tier | Mechanism | Examples |
|------|-----------|----------|
| A. Coolify Secrets (encrypted) | Coolify UI → env | `DB_ROOT_PASSWORD`, `NEXON_API_KEY`, `MINIO_ROOT_USER/PASSWORD`, `GRAFANA_ADMIN_*`, `SA_*_SECRET_KEY` |
| B. File secret (SA keys) | bind mount at `SECRETS_DIR_HOST` (`/opt/maple/secrets`) | `sa-<module>.key` (4 files) |
| C. Plain config | Coolify env | `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, image refs, `SECRETS_DIR` |

`/opt/maple/secrets/` is written by `minio-bootstrap` (infra resource) and read by the apps (apps resource) via the compose `secrets:` directive. **Back it up** alongside the volumes.

## Deploy order

1. `docker network create --subnet=172.20.0.0/16 maple-network` (one-time)
2. Deploy `maple-infra` → wait all healthy.
3. Deploy `maple-apps` → wait all healthy. If infra not ready, apps crash→restart until it is.

## Image pipeline (apps)

```
push to develop
  → GitHub Actions: bootJar → build.sh → tag/push ghcr.io/zbnerd/maple-<svc>:{sha,latest}
Coolify maple-apps (auto-deploy) → docker compose pull + up
```

Image refs in compose use `${IMAGE_<SVC>}` (default `maple/<svc>:dev` for local dev).

## Rollback

- **Apps:** set `IMAGE_<SVC>=ghcr.io/zbnerd/maple-<svc>:sha-<prior>` in the `maple-apps` resource env, redeploy. Or use Coolify's Rollback UI.
- **Infra:** NOT a rollback target — postgres/kafka version downgrades risk data. Fix-forward or restore from volume backup.

## Recovery verification

- **Hard crash:** `docker kill maple-redis` → it restarts within seconds (L1/L2).
- **Soft failure (unhealthy):** break a probe target or pause the service; within ~90–150s autoheal restarts it (L3). Watch `docker logs -f maple-autoheal`.
- **Restart-rate alert:** `ContainerRestartThrashing` (cAdvisor `container_start_time_seconds`, `changes() > 2` in 5m) in the prometheus UI.

## Observability gaps (pre-existing, separate cleanup)

- **alertmanager:** referenced by `prometheus.yml` (`alertmanager:9093`) but only defined in the legacy `docker-compose.observability.yml` overlay; not running. Alerts evaluate in prometheus but are not delivered until alertmanager is wired into the active `docker-compose.yml`.
- **node-exporter:** scraped by `prometheus.yml` but only defined in the legacy overlay; not running. System metrics alerts (`HighCpuUsage`, etc.) have no data until it is wired in.

## Backup checklist

- Named volumes: `postgres_data`, `minio_data`, `kafka_data`, `redis_data`, `loki_data`, `grafana_data`, `prometheus_data`.
- `/opt/maple/secrets/` (SA keys).
- Coolify resource configs (export from UI).
````

- [ ] **Step 2: Commit**

```bash
git add docs/21_Operations/coolify-setup-guide.md
git commit -m "$(cat <<'EOF'
docs(ops): Coolify setup guide for the self-healing maple stack

Single operator reference for the 3-resource topology, 3-layer
self-healing, secrets tiers, deploy order, image pipeline, rollback,
and recovery verification. Records the pre-existing alertmanager /
node-exporter gaps and the backup checklist.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Operator Runbook — Phase 3 activation

After the Phase-3 PR merges to `develop` and the infra/apps resources redeploy with the new compose.

### R-1: Enable apps auto-deploy

1. Coolify UI → `maple-apps` resource → **Auto Deploy** → enable, branch `develop`.
2. Trigger a test: push a trivial commit to `develop` → confirm GHCR image builds (CI) and `maple-apps` auto-redeploys to healthy.

### R-2: Verify cAdvisor metrics + restart alert

1. Confirm the cadvisor target is up: `curl -s http://localhost:9090/api/v1/targets | grep cadvisor`.
2. Force a test restart loop (throwaway container labeled so it's scraped by cAdvisor):
   ```bash
   docker run -d --name thrash-test --restart=always alpine sh -c 'exit 1'
   ```
   cAdvisor tracks it; `container_start_time_seconds{name="thrash-test"}` changes each restart. Watch the alert:
   ```bash
   # In the prometheus UI (http://localhost:9090/alerts) watch ContainerRestartThrashing go pending → firing
   ```
3. Clean up: `docker rm -f thrash-test`.

### R-3: Verify promtail ships container stdout

1. In Grafana (Loki datasource), query `{container="maple-autoheal"}` — autoheal restart-event logs should appear.

### R-4: Backfill ADR-733 Result/Evidence

Edit `docs/01_ADR/ADR-733_coolify-observability-autodeploy.md` Section 4: cAdvisor target up, alert fired on the thrash test, promtail shipped container stdout. Commit.

---

## Self-Review (completed during authoring)

**Spec coverage (Phase-3 slice):**
- cAdvisor restart-rate → P3-2 ✓ (spec Section 8 option (a))
- Restart alert rule → P3-2 ✓
- promtail docker_sd container stdout → P3-3 ✓ (spec Section 8 gap #2)
- Apps auto-deploy ON → Runbook R-1 ✓ (spec Section 7)
- Ops guide → P3-4 ✓ (spec Section 9 Phase 3 exit)
- ADR → P3-1 ✓ (RPI rule)
- Pre-existing gaps (alertmanager, node-exporter) documented, not fixed ✓

**Placeholder scan:** none. Every step has exact YAML or commands with expected output.

**Consistency:** cAdvisor host port 8086 consistent across compose + prometheus scrape. Alert name `ContainerRestartThrashing` consistent across rule + runbook. `logging=promtail` label consistent across cadvisor/autoheal + promtail filter. prometheus reaches cAdvisor via `localhost:8086` (host-network pattern, matching module scrapes).

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase3.md`. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach?
