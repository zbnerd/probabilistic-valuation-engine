# Coolify Self-Healing — Phase 1 (Infra) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ` `]`) syntax for tracking.

**Goal:** Bring the infra stack under Coolify as a self-healing Docker Compose resource — external shared network, healthchecks on every container, and an autoheal sidecar that restarts unhealthy containers (closing the soft-failure gap Docker restart policies and Coolify Sentinel cannot cover).

**Architecture:** `docker-compose.yml` becomes a Coolify-managed resource (`maple-infra`). The `maple-network` flips to `external` (shared with the future `maple-apps` resource). Every persistent container gets a Docker `healthcheck` + an `autoheal: "true"` label. A new `autoheal` container watches Docker `health_status` events over the socket and `docker restart`s any labeled container that goes unhealthy. Hard crashes still recover via the existing `restart: always` policy; Coolify Sentinel adds crash-restart on top; autoheal adds the missing unhealthy→restart path.

**Tech Stack:** Docker Compose v3.8, `willfarrell/autoheal`, Alpine busybox `wget` health probes, Coolify v4.1.2, GitHub Actions CI smoke job.

**Scope:** Phase 1 ONLY. The 4 app services (`docker-compose.services.yml`) are NOT touched — they stay on their current startup path. Phase 2 (apps under Coolify + CI→GHCR image pipeline) and Phase 3 (auto-deploy, cAdvisor/promtail observability, ops guide) are separate follow-up plans. See `docs/superpowers/specs/2026-06-22-coolify-self-healing-design.md`.

**Branch:** `feat/coolify-self-healing` (already created, tracks `origin/develop`).

**Working directory:** `/home/maple/probabilistic-valuation-engine`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `docs/01_ADR/ADR-731_coolify-self-healing-infra.md` | Create | ADR documenting the 3-layer self-healing + external-network + autoheal decision |
| `docker-compose.yml` | Modify | (1) `maple-network` → external; (2) add healthchecks to kafka/redis/grafana/prometheus/promtail; (3) add `start_period` to postgres/minio/loki healthchecks; (4) add `autoheal` service; (5) add `autoheal: "true"` labels to persistent services |
| `.github/workflows/ci.yml` | Modify | Add `docker network create maple-network` step to the `docker-smoke` job (the external-network change otherwise breaks smoke, which relies on compose owning the network) |

No Kotlin/Java code changes in Phase 1. No `docker-compose.services.yml` changes.

---

## Task 1: ADR-731 — self-healing infra under Coolify

**Files:**
- Create: `docs/01_ADR/ADR-731_coolify-self-healing-infra.md`

ADR is required by the repo RPI workflow before implementation. Condense the spec's Phase-1 decisions into the repo's 5-section ADR format (see `.claude/rules/adr-conventions.md`). Keep it short — the spec already holds the detail.

- [ ] **Step 1: Write the ADR**

Create `docs/01_ADR/ADR-731_coolify-self-healing-infra.md` with exactly this content (adapt the date if implementing on a different day):

```markdown
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
* `start_period` tuning per service (redis 10s … kafka 40s, apps 90s in Phase 2) — too short → boot-time restart loops.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| autoheal sidecar over pure Coolify-native | guaranteed unhealthy→restart (soft-failure coverage) | extra container + socket mount |
| external network over owned | shared with future apps resource; survives single-resource down | manual one-time `docker network create` |

### Risk

* autoheal itself dies → no L3. Mitigated by `restart: always` + Coolify UI visibility.

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

To be filled after Phase 1 operator verification (force-kill + force-unhealthy recovery tests in the runbook).

---

## 5. Summary

> Run infra under Coolify with healthchecks on every container and an autoheal sidecar, so both hard crashes and soft failures self-heal.
```

- [ ] **Step 2: Commit**

```bash
git add docs/01_ADR/ADR-731_coolify-self-healing-infra.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-731 Coolify-managed infra with 3-layer self-healing

Condenses the Phase-1 decisions from the design spec into the repo ADR
format: external network, healthchecks everywhere, autoheal sidecar
closing the unhealthy->restart gap that Docker restart policy and
Coolify Sentinel cannot cover.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Convert `maple-network` to external + CI smoke fix

**Files:**
- Modify: `docker-compose.yml` (networks block, lines ~189-194)
- Modify: `.github/workflows/ci.yml` (docker-smoke job, before the "Bring up full stack" step at line ~418)

**Why these ship together:** the `docker-smoke` CI job runs `docker compose up` relying on compose owning `maple-network`. The moment the network becomes `external`, that job fails unless the runner creates the network first. They must land in one commit to keep CI green.

- [ ] **Step 1: Edit the networks block in `docker-compose.yml`**

Replace this block (currently at the bottom of `docker-compose.yml`):

```yaml
networks:
  maple-network:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16
```

with:

```yaml
networks:
  maple-network:
    # External so both the maple-infra and the future maple-apps Coolify
    # resources share it. Created once before first deploy:
    #   docker network create --subnet=172.20.0.0/16 maple-network
    external: true
    name: maple-network
```

(The subnet is preserved on the create command so existing container IPs / any hardcoded assumptions are unaffected. DNS-based service discovery works regardless of subnet.)

- [ ] **Step 2: Add a network-create step to the ci.yml docker-smoke job**

In `.github/workflows/ci.yml`, immediately BEFORE the `- name: Bring up full stack` step (line ~418), insert this new step:

```yaml
      # maple-network is declared external in docker-compose.yml (shared
      # with the future maple-apps Coolify resource). Create it before
      # `docker compose up`, otherwise compose fails with
      # "network maple-network declared as external, but could not be found".
      - name: Create external maple-network
        run: docker network create --subnet=172.20.0.0/16 maple-network || docker network inspect maple-network >/dev/null
```

- [ ] **Step 3: Validate the compose file parses**

Run:
```bash
docker compose -f docker-compose.yml config >/dev/null
```
Expected: exit 0, no "network declared as external but not found" error (config does not require the network to exist, only `up` does). If it errors on syntax, fix indentation.

- [ ] **Step 4: Verify locally that external network + bring-up works**

Run:
```bash
docker network create --subnet=172.20.0.0/16 maple-network || docker network inspect maple-network >/dev/null
# Bring up infra only (no services overlay) to confirm infra parses + starts
docker compose -f docker-compose.yml up -d postgres minio kafka redis
```
Expected: 4 containers reach running. Then tear down:
```bash
docker compose -f docker-compose.yml down
```
Leave the network in place (do NOT delete it — infra expects it external now).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
feat(infra): maple-network external + CI smoke network-create step

Flip maple-network to external so the future maple-apps Coolify resource
shares it. Add a docker network create step to the docker-smoke CI job,
which previously relied on compose owning the network.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Healthchecks on all infra containers

**Files:**
- Modify: `docker-compose.yml`

Add healthchecks to the 5 services that lack them (kafka, redis, grafana, prometheus, promtail) and add `start_period` to the 3 that already have one (postgres, minio, loki). Alpine-based images ship busybox `wget` (no `curl`).

- [ ] **Step 1: Add healthcheck to `kafka`**

In `docker-compose.yml`, under the `kafka:` service (currently no `healthcheck:`), add (same 4-space indentation as the sibling `environment:`/`volumes:` keys):

```yaml
    healthcheck:
      test: ["CMD-SHELL", "kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1 || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s
```

- [ ] **Step 2: Add healthcheck to `redis`**

Under the `redis:` service, add:

```yaml
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 10s
```

- [ ] **Step 3: Add healthcheck to `grafana`**

Under the `grafana:` service, add (grafana listens on 3000 inside the container; the host port 3001→3000 mapping is irrelevant for the in-container probe):

```yaml
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:3000/api/health || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
```

- [ ] **Step 4: Add healthcheck to `prometheus`**

Under the `prometheus:` service (uses `network_mode: host`, so localhost:9090 is the host), add after the `volumes:` block:

```yaml
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:9090/-/healthy || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
```

- [ ] **Step 5: Add healthcheck to `promtail`**

Under the `promtail:` service, add:

```yaml
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:9080/ready || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
```

- [ ] **Step 6: Add `start_period` to the existing `postgres` healthcheck**

Under the `postgres:` service, the existing `healthcheck:` block currently has `test`/`interval`/`timeout`/`retries`. Add one line after `retries: 5`:

```yaml
      start_period: 30s
```

(Full postgres healthcheck becomes `test: ["CMD-SHELL", "pg_isready -U maple -d maple_expectation"]`, `interval: 10s`, `timeout: 5s`, `retries: 5`, `start_period: 30s`.)

- [ ] **Step 7: Add `start_period` to the existing `minio` healthcheck**

Under `minio:`, after `retries: 5`, add:

```yaml
      start_period: 30s
```

- [ ] **Step 8: Add `start_period` to the existing `loki` healthcheck**

Under `loki:`, after `retries: 3`, add:

```yaml
      start_period: 30s
```

- [ ] **Step 9: Validate the compose file parses**

Run:
```bash
docker compose -f docker-compose.yml config >/dev/null && echo OK
```
Expected: prints `OK`, exit 0.

- [ ] **Step 10: Verify each healthcheck reports healthy**

Bring up infra and poll each container's health status (run from the repo root; `maple-network` already exists from Task 2 Step 4):
```bash
docker compose -f docker-compose.yml up -d
# wait ~60s for start_periods to elapse, then:
for c in maple-postgres maple-minio maple-kafka maple-redis maple-grafana maple-prometheus maple-loki maple-promtail; do
  printf '%-22s %s\n' "$c" "$(docker inspect --format '{{.State.Health.Status}}' "$c" 2>/dev/null || echo 'no-healthcheck')"
done
```
Expected: every line shows `healthy` (not `starting`, not `no-healthcheck`, not `unhealthy`). If any shows `starting`, wait longer and re-run. If any shows `unhealthy`, run `docker inspect --format '{{json .State.Health}}' <name>` and read the failing probe's stderr in the `.Log` array.

- [ ] **Step 11: Tear down**

```bash
docker compose -f docker-compose.yml down
```

- [ ] **Step 12: Commit**

```bash
git add docker-compose.yml
git commit -m "$(cat <<'EOF'
feat(infra): healthchecks on all infra containers

Add healthchecks to kafka/redis/grafana/prometheus/promtail (Alpine
wget-based; redis-cli ping) and start_period to the existing
postgres/minio/loki healthchecks. Every persistent infra container now
reports a health state for depends_on chains and the autoheal sidecar.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: autoheal sidecar + labels

**Files:**
- Modify: `docker-compose.yml`

Add the `autoheal` service and an `autoheal: "true"` label to every persistent container. Labels are EXCLUDED on `minio-bootstrap` (one-shot job) and `autoheal` itself (cannot restart itself).

- [ ] **Step 1: Add the `autoheal` service**

In `docker-compose.yml`, add this as a new top-level service (place it right before `minio-bootstrap:` so the one-shot job stays last):

```yaml
  # autoheal — watches Docker health_status events and restarts any
  # container carrying the `autoheal=true` label that goes unhealthy.
  # Closes the soft-failure gap (process alive, /health DOWN) that the
  # restart: always policy and Coolify Sentinel cannot cover.
  # See ADR-731.
  autoheal:
    image: willfarrell/autoheal:latest
    container_name: maple-autoheal
    restart: always
    environment:
      AUTOHEAL_CONTAINER_LABEL: autoheal
      AUTOHEAL_INTERVAL: 5
      AUTOHEAL_DEFAULT_STOP_TIMEOUT: 30
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - maple-network
```

Note: socket is mounted read-write. autoheal needs the `restart` API. If a later autoheal version supports read-only socket operation, switch to `:ro`; until verified, rw is required (Coolify itself uses socket rw — same trust level).

- [ ] **Step 2: Add the `autoheal: "true"` label to the 8 persistent services**

For EACH of: `postgres`, `minio`, `kafka`, `redis`, `loki`, `grafana`, `prometheus`, `promtail` — add this block under the service (4-space indent, alongside `restart:`/`environment:`):

```yaml
    labels:
      autoheal: "true"
```

Do NOT add the label to `minio-bootstrap` or `autoheal`.

- [ ] **Step 3: Validate the compose file parses**

Run:
```bash
docker compose -f docker-compose.yml config >/dev/null && echo OK
```
Expected: prints `OK`, exit 0.

- [ ] **Step 4: Bring up infra including autoheal**

```bash
docker compose -f docker-compose.yml up -d
```
Expected: `maple-autoheal` reaches running; on first start its logs show it registered the Docker event stream. Confirm:
```bash
docker logs maple-autoheal 2>&1 | tail -20
```
Expected output includes a line like `AUTOHEAL_CONTAINER_LABEL=autoheal` and (after a few seconds) references to monitoring, with no errors.

- [ ] **Step 5: Verify autoheal restarts an unhealthy container (the core guarantee)**

Run a throwaway test container that always fails its healthcheck and carries the autoheal label:
```bash
docker run -d --name autoheal-test \
  --network maple-network \
  --label autoheal=true \
  --restart=no \
  -e AUTOHEAL_CONTAINER_LABEL=autoheal \
  alpine:latest sh -c 'apk add --no-cache wget >/dev/null 2>&1; sleep 3600'
# Attach a failing healthcheck to the running test container is not possible
# post-create, so instead recreate it with the failing check baked in:
docker rm -f autoheal-test
docker run -d --name autoheal-test \
  --network maple-network \
  --label autoheal=true \
  --restart=no \
  --health-cmd 'exit 1' \
  --health-interval=5s \
  --health-retries=2 \
  --health-timeout=2s \
  alpine:latest sleep 3600
```
Now poll the test container's restart count — autoheal should restart it once it goes unhealthy (within ~`AUTOHEAL_INTERVAL` + probe retries ≈ 15–25s):
```bash
for i in $(seq 1 12); do
  rc=$(docker inspect --format '{{.RestartCount}}' autoheal-test)
  hs=$(docker inspect --format '{{.State.Health.Status}}' autoheal-test)
  echo "attempt=$i health=$hs restarts=$rc"
  [ "$rc" -ge 1 ] && { echo "AUTOHEAL RESTARTED THE UNHEALTHY CONTAINER"; break; }
  sleep 3
done
```
Expected: the loop prints `AUTOHEAL RESTARTED THE UNHEALTHY CONTAINER` with `restarts >= 1`. If it never restarts, check `docker logs maple-autoheal` — it must show a restart event for `autoheal-test`. If autoheal never saw the label, confirm `AUTOHEAL_CONTAINER_LABEL=autoheal` is set on the autoheal container and the test container's label key matches.

- [ ] **Step 6: Clean up the test container**

```bash
docker rm -f autoheal-test
```

- [ ] **Step 7: Verify a real infra container's label is visible to autoheal**

Confirm autoheal sees a real labeled container (postgres) so we know the labels from Step 2 are effective:
```bash
docker inspect maple-postgres --format '{{index .Config.Labels "autoheal"}}'
```
Expected: prints `true`. (autoheal only acts on unhealthy state; this just confirms the label is set.)

- [ ] **Step 8: Tear down**

```bash
docker compose -f docker-compose.yml down
```
(The `maple-network` external network is NOT removed by `down` — leave it.)

- [ ] **Step 9: Commit**

```bash
git add docker-compose.yml
git commit -m "$(cat <<'EOF'
feat(infra): autoheal sidecar + autoheal labels

Add the autoheal container (watches Docker health_status events over the
socket, restarts unhealthy labeled containers) and the autoheal=true label
on every persistent infra service. Closes the soft-failure self-healing
gap. Verified: autoheal restarts a force-unhealthy test container.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Operator Runbook — Phase 1 Coolify migration

These steps are NOT code; they run in the Coolify UI / on the host. They are the gate between "code merged" and "infra self-healing in production." Perform after the Phase 1 PR merges to `develop`.

### R-1: Enable Coolify Sentinel auto-restart (L2)

1. Coolify UI → **Server settings** (the server running the maple stack).
2. Enable **"Auto-restart stopped/unsual containers"** (or the equivalently named setting in 4.1.2). If already enabled, skip.
3. This is L2 in the 3-layer model — covers stopped-container cases L1 might miss.

### R-2: Create the `maple-infra` Coolify resource

1. Coolify UI → **+ New Resource** → **Docker Compose** (not "Application").
2. Point it at the repo (`probabilistic-valuation-engine`) and the compose file `docker-compose.yml`.
3. Name the resource `maple-infra`.
4. In the resource's **Environment variables / Secrets**, set the values that the compose file currently interpolates from the operator `.env` (these become tier-A Coolify Secrets — see spec Section 6):
   - `DB_ROOT_PASSWORD` (secret)
   - `MINIO_ROOT_USER` (secret)
   - `MINIO_ROOT_PASSWORD` (secret)
   - `GRAFANA_ADMIN_USER` (secret)
   - `GRAFANA_ADMIN_PASSWORD` (secret)
   - `TZ` (plain)
   - Plus the `.env.bootstrap` values `minio-bootstrap` consumes:
     - `SA_EXT_API_SECRET_KEY`, `SA_CALCULATOR_SECRET_KEY`, `SA_SYNCHRONIZER_SECRET_KEY`, `SA_CLEANUP_SECRET_KEY` (secrets)
     - `MINIO_ENDPOINT` = `http://minio:9000` (plain)
5. Do NOT deploy yet.

### R-3: Take down the manual stack (preserve volumes)

The existing `maple-*` containers were started by manual `docker compose up`. Coolify must take over the same container names / volumes.

1. On the host, from the repo root:
   ```bash
   docker compose -f docker-compose.yml down
   ```
   This stops the manual infra containers. **Named volumes** (`postgres_data`, `minio_data`, `kafka_data`, `redis_data`, `loki_data`, `grafana_data`, `prometheus_data`) are NOT removed by `down` (only `down -v` removes them) — data is preserved.
2. Confirm the network still exists (Coolify needs it):
   ```bash
   docker network inspect maple-network >/dev/null && echo "network present" || docker network create --subnet=172.20.0.0/16 maple-network
   ```

### R-4: First Coolify deploy + verify data preserved

1. Coolify UI → `maple-infra` resource → **Deploy**.
2. Wait for all containers to report healthy in the Coolify UI (healthchecks now drive status).
3. Verify data survived the takeover:
   ```bash
   # Postgres row count sanity (replace with a known table if preferred)
   docker exec maple-postgres psql -U maple -d maple_expectation -c "SELECT count(*) FROM pg_stat_user_tables;"
   # MinIO bucket + object presence
   docker exec maple-minio mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
   docker exec maple-minio mc ls local/maple-expectation | head
   ```
   Expected: pg_stat_user_tables shows the expected table count (non-zero if data existed); MinIO lists the `maple-expectation` bucket with objects. If empty, STOP — the volume mapping diverged; investigate before proceeding.

### R-5: Verify hard-crash recovery (L1/L2)

1. Force-kill a container:
   ```bash
   docker kill maple-redis
   ```
2. Within seconds, the container should restart (Docker `restart: always`). Confirm:
   ```bash
   docker ps --filter name=maple-redis --format '{{.Status}}'
   ```
   Expected: `Up X seconds (health: starting)` or `(healthy)` — i.e., it came back.

### R-6: Verify soft-failure recovery (L3 autoheal) on a real container

1. Make a real container unhealthy by breaking its probe target. For `grafana`, stop the internal API health route is hard; use `redis` instead — pause the redis process inside the container so `redis-cli ping` fails:
   ```bash
   docker exec maple-redis sh -c 'kill -STOP 1' 2>/dev/null || true
   ```
   (If SIGSTOP on PID 1 is blocked by the image, fall back to the throwaway `autoheal-test` container method from Task 4 Step 5 — the guarantee is identical.)
2. Wait for the healthcheck to flip unhealthy (~`interval`×`retries`, ≤90s) and watch autoheal:
   ```bash
   docker logs -f maple-autoheal
   ```
   Expected: autoheal logs a `health_status=unhealthy` event for `maple-redis` and issues a restart.
3. Confirm recovery:
   ```bash
   docker inspect maple-redis --format '{{.RestartCount}}'
   ```
   Expected: `>= 1` and the container returns to `healthy`.

### R-7: Backfill ADR-731 Result/Evidence

After R-5 and R-6 succeed, edit `docs/01_ADR/ADR-731_coolify-self-healing-infra.md` Section 4 "Observed Result" with the actual outcomes (force-kill recovered in Ns; autoheal restarted the unhealthy container in Ns; all 8 infra containers healthy in Coolify UI). Commit on a follow-up.

---

## Self-Review (completed during authoring)

**Spec coverage (Phase 1 slice):**
- Network external conversion → Task 2 ✓
- Healthchecks (5 new + 3 start_period) → Task 3 ✓
- autoheal sidecar + labels → Task 4 ✓
- ADR → Task 1 ✓
- CI smoke parity (network-create) → Task 2 ✓
- Coolify resource creation + Secrets → Runbook R-2 ✓
- Sentinel L2 enablement → Runbook R-1 ✓
- Data preservation → Runbook R-3/R-4 ✓
- Hard-crash + soft-failure verification → Runbook R-5/R-6 ✓
- Phase 2 (apps, image pipeline) and Phase 3 (cAdvisor/promtail/auto-deploy/ops guide) → explicitly out of scope, follow-up plans ✓

**Placeholder scan:** none. Every step has exact YAML or exact commands with expected output.

**Type/label consistency:** the label key is `autoheal` with value `"true"` everywhere (autoheal env `AUTOHEAL_CONTAINER_LABEL: autoheal`, compose `labels: autoheal: "true"`, test container `--label autoheal=true`). Container names use the `maple-` prefix consistently.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase1.md`. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
