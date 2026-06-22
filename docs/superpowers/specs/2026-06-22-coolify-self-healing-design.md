# Coolify-aware Self-Healing & Service Management Design

- Status: Proposed
- Date: 2026-06-22
- Owner: zbnerd
- Related: ADR-720 (Airflow control plane adoption — names Coolify + Docker Compose as deployment layer), ADR-730 (calculator writer temp-file upload), PR #1324 (single-source MinIO SA secrets), `docs/superpowers/specs/2026-06-15-minio-operations-design.md` (Coolify per-application secret model roadmap)

---

## 1. Background / Problem

### Background

The 4 Spring Boot services (external-api, calculator, synchronizer, cleanup) and 8 infra containers (postgres, minio, kafka, redis, prometheus, grafana, loki, promtail) were dockerized in PR #1324. ADR-720 designates **Coolify + Docker Compose** as the deployment layer (K8s complexity avoidance). Coolify v4.1.2 is installed and running on the host.

### Problem

Investigation (2026-06-22) revealed the gap behind the original concern — *"if a container dies, restart/self-healing isn't implemented yet, right?"*:

1. **The maple stack is NOT under Coolify management.** The `maple-*` containers carry no Coolify labels; they were started by manual `docker compose up`. Because Coolify does not own them, **Sentinel (Coolify's container monitor), the Coolify UI, healthcheck reporting, git auto-deploy, and crash-restart all ignore them.**
2. **Crash recovery (process exit) IS handled** by Docker `restart` policies — `always` on infra, `unless-stopped` on apps. OOM kill, JVM panic, segfault → daemon auto-restarts.
3. **Soft-failure recovery (process alive, `/health` DOWN) is NOT handled.** Docker `restart` policy fires only on container exit, not on `unhealthy`. Coolify v4.x lacks K8s-style liveness probes that restart unhealthy containers (ADR-720 Risk line acknowledges this: *"Coolify 미성숙 — Docker Compose fallback 유지"*).
4. **4 app services are not currently running in Docker** (`docker ps` shows only infra). They run via `nohup java -jar` or are stopped.
5. **4 infra containers have no healthcheck** (kafka, redis, grafana, prometheus, promtail), blocking `depends_on: service_healthy` chains and visibility.

### Goal

Bring the full maple stack (infra + 4 apps) under Coolify management as Docker Compose resources, and close the self-healing gap so that **both hard crashes and soft failures auto-recover**, with operational visibility.

### Non-Goals

- Migrating off Docker Compose to K8s/Swarm (ADR-720 explicitly defers this).
- Redis reintroduction policy debate (ADR-022 settled — Redis allowed).
- Readiness/liveness probe separation (deferred to a future K8s migration; current design uses a single healthcheck per service).
- Changing StorageConfig's file-based MinIO SA secret model (PR #1324's deliberate decision is preserved).

---

## 2. Decision Summary

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Migrate full stack to Coolify as **2 Docker Compose resources** (`maple-infra`, `maple-apps`) | Matches existing 2-file compose split; separates deploy cadence (infra rare, apps frequent); isolates blast radius |
| 2 | repo compose = **single source of truth**; Coolify imports, does not fork | Keeps config version-controlled, reviewable, local-dev parity |
| 3 | `maple-network` → **external network** (created once, shared by both resources) | Cross-resource container references; survives single-resource down |
| 4 | **3-layer self-healing**: Docker restart policy (L1) + Coolify Sentinel crash-restart (L2) + autoheal sidecar for unhealthy→restart (L3) | L3 closes the soft-failure gap that L1/L2 cannot cover |
| 5 | **autoheal sidecar** (`willfarrell/autoheal`) in `maple-infra`, watches all containers via Docker socket | Guaranteed unhealthy→restart, K8s-liveness-probe equivalent, works under Coolify |
| 6 | Secrets model: **Coolify Secrets (encrypted)** for passwords + **absolute-path file bind mount** for MinIO SA keys + Coolify env for plain config | Tier-matched to secret nature; preserves PR #1324 file-based SA model; removes repo-relative bind-mount fragility under Coolify |
| 7 | Image pipeline: **CI → GHCR → Coolify pull** (option B); infra auto-deploy OFF, apps auto-deploy ON | Server stays thin; sha-pinned rollback; infra changes deliberate |
| 8 | **Phased rollout**: Phase 1 infra → Phase 2 apps → Phase 3 automation + ops guide | Risk reduction; validate health/autoheal/network before app migration |

---

## 3. Architecture / Topology

### Two Coolify Docker Compose resources

| Resource | Compose file | Containers | Deploy cadence |
|----------|--------------|------------|----------------|
| `maple-infra` | `docker-compose.yml` | postgres, minio, kafka, redis, prometheus, grafana, loki, promtail, minio-bootstrap, **autoheal** | Rare (infra stable) |
| `maple-apps` | `docker-compose.services.yml` | external-api, calculator, synchronizer, cleanup | Frequent (git push) |

### Network: external conversion

```yaml
# docker-compose.yml — remove owned network definition, reference external
networks:
  maple-network:
    external: true
    name: maple-network
```
One-time creation before first Coolify deploy:
```bash
docker network create maple-network
```

### Dependency flow (cross-resource)

```
[maple-infra resource]                    [maple-apps resource]
  postgres (healthy) ─────────────────────► external-api
  minio (healthy) ───────────────────────► calculator
  kafka ──────────────────────────────────► synchronizer
  redis ──────────────────────────────────► cleanup
  autoheal (watches all containers via socket, both resources)
```

Cross-resource `depends_on` is not supported by Compose. Startup ordering is handled by:
1. Infra resource deployed/healthy first.
2. Apps crash→restart loop naturally waits for infra readiness (L1 restart policy + L3 autoheal). This is the standard pattern for split-resource Compose deployments.

### autoheal placement

Lives in `maple-infra`, starts first. Mounts Docker socket → monitors host-wide containers regardless of resource boundary.

### Ownership transfer (data preservation)

Manual `docker compose up` containers are taken down; Coolify brings up same-named containers. Named volumes (`postgres_data`, `minio_data`, `kafka_data`, `redis_data`, `loki_data`, `grafana_data`, `prometheus_data`) are reused → **data preserved** across the transfer.

---

## 4. Self-Healing Mechanism (3-layer)

| Layer | Owner | Trigger | Latency | Covers |
|-------|-------|---------|---------|--------|
| **L1 Docker restart policy** | Docker daemon | container exit (non-zero) | instant (~seconds) | hard crash: OOM kill, JVM panic, segfault |
| **L2 Coolify Sentinel** | Coolify 4.1.2 | stopped / unexpected-exit container (server setting "Auto-restart stopped/unsual containers") | tens of seconds–minutes | cases L1 misses (daemon restart gaps, exit-0 abnormal stops) |
| **L3 autoheal sidecar** | autoheal container | `health_status=unhealthy` Docker event | ~`interval`×`retries` (typically 90–150s) | **soft failure: process alive, unresponsive** (connection-pool exhaustion, thread-pool hang, GC storm, liveness DOWN) |

L3 alone covers soft failure. L1/L2 require process death. The user's original concern — *"alive but dead"* — is the L3 domain.

### Soft-failure recovery flow (example)

```
calculator: connection pool exhausted → /actuator/health DOWN (process alive)
  ↓ Docker healthcheck (wget /actuator/health, interval 30s × retries 3)
  ↓ ~90s → health_status=unhealthy event
  ↓ autoheal detects label autoheal=true
  ↓ docker restart maple-calculator
  ↓ container restart, start_period → health starting → healthy
  ↓ recovered
```

### L2 verification item

Coolify 4.1.2's "auto-restart stopped containers" server setting must be confirmed enabled (Coolify UI → Server settings). Design assumes enabled; the plan includes enabling it as an ops step if off.

### autoheal safety

- `start_period` generous (infra 10–40s per Section 5 table — redis fast-boot 10s, kafka slow-boot 40s, others 30s; apps 90s) to prevent boot-time false-unhealthy restart loops.
- `autoheal` container itself: label excluded (cannot restart itself), `restart: always`.
- `minio-bootstrap`: label excluded (one-shot, unhealthy meaningless).
- Docker socket mount required read-write for `restart` API; Coolify already uses socket rw (same trust level on the node). Verify autoheal version's read-only socket support; fall back to rw if needed.

### Thrash prevention

autoheal has built-in backoff. Spring Boot liveness/readiness separation is deferred to a future K8s migration (see Non-Goals) — current design intentionally restarts on any `/health` DOWN via the single healthcheck. Readiness-only failures (drain traffic without restart) are a K8s-era concern.

---

## 5. Healthcheck Specs (per service)

Runtime base: Alpine (prometheus, grafana, loki, promtail, eclipse-temurin runtime) → `wget` available, `curl` absent. All checks use `wget`.

### New healthchecks (5 services currently without)

| Service | test | interval | start_period | timeout | retries |
|---------|------|----------|--------------|---------|---------|
| kafka | `kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1` | 30s | 40s | 10s | 3 |
| redis | `redis-cli ping` (expects `PONG`) | 10s | 10s | 3s | 3 |
| grafana | `wget -q --spider http://localhost:3000/api/health` | 30s | 30s | 5s | 3 |
| prometheus | `wget -q --spider http://localhost:9090/-/healthy` (host network) | 30s | 30s | 5s | 3 |
| promtail | `wget -q --spider http://localhost:9080/ready` | 30s | 30s | 5s | 3 |

### Existing healthchecks (3) — keep, add start_period

| Service | test (unchanged) | added start_period |
|---------|------------------|--------------------|
| postgres | `pg_isready -U maple -d maple_expectation` | 30s |
| minio | `mc ready local` | 30s |
| loki | `wget -q --spider http://localhost:3100/ready` | 30s |

### Apps (4) — common spec, port branches per service

```yaml
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:${SERVER_PORT}/actuator/health | grep -q '\"status\":\"UP\"' || exit 1"]
  interval: 30s
  timeout: 5s
  retries: 3
  start_period: 90s   # JVM boot + Spring context
labels:
  autoheal: "true"
```

- `SERVER_PORT` env (8081–8084) drives dynamic port — independent of Dockerfile's generic `EXPOSE 8080`.
- **Verification item:** `/actuator/health` web exposure confirmed. Spring Boot exposes `health` by default; plan confirms `management.endpoints.web.exposure.include` does not exclude it.

### autoheal label mapping

| Container | `autoheal: "true"` | Notes |
|-----------|--------------------|-------|
| postgres, minio, kafka, redis, loki, grafana | ✅ | persistent infra |
| prometheus, promtail | ✅ | observability (restart on death) |
| external-api, calculator, synchronizer, cleanup | ✅ | apps |
| **minio-bootstrap** | ❌ | one-shot provisioning job |
| **autoheal (self)** | ❌ | cannot self-restart; `restart: always` only |

### autoheal container spec

```yaml
autoheal:
  image: willfarrell/autoheal:latest
  container_name: maple-autoheal
  restart: always
  environment:
    AUTOHEAL_CONTAINER_LABEL: autoheal
    AUTOHEAL_INTERVAL: 5              # unhealthy poll (seconds)
    AUTOHEAL_DEFAULT_STOP_TIMEOUT: 30
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock:ro
  networks: [maple-network]
```

---

## 6. Secrets & Env Model (Coolify)

Current state (PR #1324): MinIO SA keys = file-based docker secrets (`/run/secrets/sa-<module>`, read via `Files.readString` in StorageConfig). Passwords (API key, DB root) = operator `.env` compose interpolation.

**Problem:** Coolify copies the compose file to its own working dir (`/data/coolify/applications/<uuid>/`) before `docker compose up`. Repo-relative bind mounts (`./docker/services/secrets/sa-ext-api.key`) **break** — the path resolves differently per resource, and `minio-bootstrap` + the apps must see the same file.

### 3-tier model

| Tier | Kind | Mechanism | Examples |
|------|------|-----------|----------|
| **A. Coolify Secrets (encrypted)** | true secrets | Coolify UI "Secrets" (encrypted at rest, masked, resource-scoped) → env injection | `DB_ROOT_PASSWORD`, `NEXON_API_KEY`, `MINIO_ROOT_PASSWORD`, `MINIO_ROOT_USER`, `GRAFANA_ADMIN_PASSWORD` |
| **B. File secret (SA keys)** | MinIO SA | **absolute host-path** bind mount (Coolify-persistent path), `MINIO_SECRET_KEY_FILE` retained | `sa-ext-api.key`, `sa-calculator.key`, `sa-synchronizer.key`, `sa-cleanup.key` |
| **C. Plain config** | non-secret | Coolify per-resource env (plaintext) | `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `MINIO_ENDPOINT`, `SPRING_PROFILES_ACTIVE`, `SERVER_PORT` |

### Tier-B core change: absolute host path

```yaml
# secrets path pinned to a stable absolute host location shared by both resources
secrets:
  sa-ext-api:
    file: /opt/maple/secrets/sa-ext-api.key   # repo-relative → absolute
```

- `/opt/maple/secrets/` (or Coolify-recommended persistent path) = single source. `minio-bootstrap` writes; apps read.
- Permissions: `chmod 700`; owner readable by the container's `maple` user.
- Explicitly named as a backup target in the ops guide.

### StorageConfig: no change

`MINIO_SECRET_KEY_FILE` retained. PR #1324's decision (single-source SA, least-privilege per module) is preserved. The Coolify per-application secret model is the documented future (minio-operations spec) but SA keys are deliberately file-based; tier-mixing is justified because each secret's nature maps to its mechanism.

### Tier-A migration (`.env` → Coolify Secrets)

Operator `.env` secrets move to Coolify Secrets. `.env` remains for local dev / nohup fallback (critical-rules: `.env` is READ-ONLY, never modified). Coolify resources become self-contained — no `.env` dependency at deploy time.

Dual-source compatibility: same variable names, local `docker compose` uses `.env` interpolation, Coolify uses its own env/secret injection.

---

## 7. Deploy / Rollback / Image Pipeline

### Image build (decision B: CI → registry → Coolify pull)

| Option | Flow | Pros | Cons |
|--------|------|------|------|
| A. Coolify pre-deploy hook build | Coolify clones repo → hook runs `build.sh` → up | single system, fewer deps | server needs JDK21+gradle, slow deploys (minutes), server build load |
| **B. CI → GHCR → Coolify pull (chosen)** | GitHub Actions (merge to develop) builds → GHCR push `ghcr.io/.../maple-<svc>:sha-<7>` → Coolify pulls tag → up | thin server, build/deploy split, sha pinning + audit, easy rollback | registry dependency, CI build job added |
| C. dev-machine build → server load | local build → `docker save \| ssh load` | no infra | manual, no auto-deploy |

`build.sh` already produces dual tags `:dev` + `:sha-<7char>` (commit b27328824). CI pushes these to GHCR. Coolify app resources reference `ghcr.io/.../maple-<svc>:sha-<7>` (or track `:latest`).

### Deploy triggers

| Resource | git auto-deploy | Reason |
|----------|-----------------|--------|
| `maple-infra` | **OFF** (manual) | infra changes deliberate; bad version bump risks data loss; strict version pinning |
| `maple-apps` | **ON** (push to develop → CI → image → Coolify webhook redeploy) | apps change frequently; automation value high |

### Deploy order (initial transfer + ongoing)

1. `docker network create maple-network` (one-time)
2. Deploy `maple-infra` resource → postgres/minio/kafka/redis/autoheal up, wait healthy
3. Deploy `maple-apps` resource → 4 apps up. If infra not ready, apps crash→restart loop naturally waits (Section 3 pattern)

### Rollback

- **Apps:** Coolify "Rollback" UI (prior deployment containers preserved) or change image tag to the previous sha and redeploy. Sha tags enable exact-version return.
- **Infra:** rollback ≠ simple image swap. postgres/kafka version downgrade risks data migration. **Infra is not a rollback target** — on failure, fix-forward (new version) or restore from volume backup. Documented in the ops guide.

---

## 8. Observability (existing assets + self-heal delta)

Already running: prometheus (metrics), grafana (dashboard), loki+promtail (logs). Self-healing does not rebuild the observability stack — only deltas.

| Item | Method | Status |
|------|--------|--------|
| autoheal restart events | `docker logs maple-autoheal` + Coolify UI deployment history | built-in |
| container unhealthy transition | Docker `health_status` event → `docker events --filter event=health_status` | manual query |
| restart-rate metric | container restart counter in prometheus | **gap** |
| autoheal logs → loki | promtail currently tails module logs (`./logs`, `./data`) only; container stdout not collected | **gap** |

### 2 small additions (plan-included, lower priority — Phase 3)

1. **autoheal log collection** — add Docker socket discovery to promtail config (container stdout collection). Filter to `logging=promtail`-labeled containers to avoid duplicate of file-tailed module logs. Or minimum: scrape autoheal only.
2. **Restart-rate alerting** — option (a) `cAdvisor` container (per-container `restart_counter`) → grafana panel + alertmanager threshold (recommended, lightweight, infra-only); option (b) Spring Boot `app_started_time` gauge (requires distinguishing container restart vs app restart). Recommend (a).

### Success criteria (operational verification)

- Force-kill a container → L1/L2 auto-recovers (instant–minutes).
- Force an app `/actuator/health` DOWN (e.g., simulate connection-pool exhaustion) → L3 autoheal restarts within ~90–150s → recovers.
- Full recovery traceable via `docker events` + autoheal logs.
- Both resources show healthy in Coolify UI.

---

## 9. Phased Rollout

| Phase | Scope | Exit criteria |
|-------|-------|---------------|
| **1. Infra under Coolify** | `maple-infra` resource only (apps stay nohup/compose). network external conversion, healthchecks added, autoheal deployed, Sentinel setting verified | All infra containers managed by Coolify, healthy in UI, autoheal restarts a force-killed container |
| **2. Apps under Coolify** | `maple-apps` resource. image pipeline B (CI→GHCR) built. SA key absolute-path migration. `/actuator/health` healthchecks | 4 apps managed by Coolify, L3 soft-failure recovery verified, data volumes preserved |
| **3. Automation + ops guide** | apps git auto-deploy ON, promtail/cAdvisor observability deltas, `docs/21_Operations/coolify-setup-guide.md` written | Push to develop → auto redeploy; restart-rate alerting live |

---

## 10. Risks / Trade-offs

### Sensitivity

- Docker socket rw mount (autoheal) = host-Docker root-equivalent. Trust boundary = the Coolify node (Coolify itself uses socket rw).
- `start_period` tuning — too short → boot-time restart loops; too long → slow soft-failure detection.
- Infra version bumps (postgres/kafka) — irreversible data-migration risk; mitigated by infra auto-deploy OFF + manual version pinning.
- Cross-resource startup ordering — apps must tolerate infra-not-ready via crash→restart (standard pattern, but adds startup latency on cold boot).

### Trade-offs

| Choice | Gain | Forego |
|--------|------|--------|
| autoheal sidecar over pure Coolify-native | guaranteed unhealthy→restart (soft-failure coverage) | extra container + socket mount |
| 2 resources over 1 | deploy cadence split, blast-radius isolation | 2 resources to manage + external network |
| CI→GHCR over Coolify-internal build | thin server, sha audit, clean rollback | registry dependency + CI job |
| File-based SA keys (tier-B) preserved | PR #1324 consistency, least-privilege SA | two secret mechanisms (transitional, tier-justified) |

### Risk (residual)

- autoheal itself dies → no L3. Mitigated by `restart: always` + Coolify UI visibility. No autoheal-of-autoheal (acceptable; autoheal is trivially stateless).
- Single-node Coolify — no multi-node HA (ADR-720 scope; multi-node is a later phase).

### Non-Risk

- Data loss on Coolify takeover — named volumes reused, data preserved.
- `.env` regression — `.env` untouched (READ-ONLY rule), only superseded at Coolify deploy time by Coolify Secrets.

---

## 11. Verification Items (for the implementation plan)

- [ ] Coolify 4.1.2 "Auto-restart stopped containers" server setting enabled.
- [ ] `/actuator/health` exposed on all 4 apps (`management.endpoints.web.exposure.include`).
- [ ] autoheal image version supports read-only Docker socket (else rw fallback).
- [ ] prometheus/grafana/loki/promtail images include `wget` (busybox) — spot-check.
- [ ] `/opt/maple/secrets/` path writable by minio-bootstrap, readable by app containers' `maple` user.
- [ ] GHCR registry + GitHub Actions CI build job wired (Phase 2).
- [ ] Named volumes intact after Coolify takeover (dry-run: `docker volume ls` before/after).

---

## 12. Summary

> Bring the maple stack under Coolify as two Docker Compose resources (`maple-infra`, `maple-apps`) with an external shared network, and close the self-healing gap with a 3-layer model — Docker restart policy + Coolify Sentinel + autoheal sidecar — so both hard crashes and soft failures (process-alive-but-unhealthy) auto-recover, with SA keys on a stable absolute path and apps deployed via CI→GHCR image pipeline.
