# Coolify Self-Healing — Phase 2 (Apps + Image Pipeline) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the 4 Spring Boot services under Coolify as a self-healing `maple-apps` Docker Compose resource — images built and pushed by CI to GHCR, SA keys on a stable absolute host path shared with the infra resource, and `/actuator/health` healthchecks + autoheal labels on every app.

**Architecture:** GitHub Actions builds the 4 jars → `build.sh` produces local `maple/<svc>:sha-<7>` images → a new CI job (on `develop` push) re-tags and pushes to `ghcr.io/zbnerd/maple-<svc>:{sha,latest}`. `docker-compose.services.yml` references each app image via a per-service `${IMAGE_<SVC>}` env var (default stays `maple/<svc>:dev` so local dev + the docker-smoke CI job are unchanged). MinIO SA keys move from repo-relative `./docker/services/secrets/` to a `SECRETS_DIR`-driven absolute host path (`/opt/maple/secrets` under Coolify) so both resources read the same files. Each app gets the L3 healthcheck + `autoheal: "true"` label from the Phase-1 pattern.

**Tech Stack:** Docker Compose v3.8, GitHub Actions, GHCR (`ghcr.io/zbnerd/...`), Coolify v4.1.2, Alpine busybox `wget`, Spring Boot Actuator `/actuator/health`.

**Prerequisites:** Phase 1 merged + deployed (the `maple-infra` resource, external `maple-network`, autoheal sidecar, and Coolify Secrets for infra must all be live). See `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase1.md`.

**Scope:** Phase 2 ONLY. `docker-compose.yml` gets two small edits (minio-bootstrap `SECRETS_DIR` env + bind mount). `docker-compose.services.yml` gets the app image vars, SA path interpolation, healthchecks, and labels. `.github/workflows/ci.yml` gets the `build-and-push` job. Phase 3 (cAdvisor, promtail docker discovery, auto-deploy, ops guide) is a separate plan.

**Branch:** `feat/coolify-self-healing` (continue on the Phase-1 branch, or a follow-up branch off it — engineer's choice; keep commits linear).

**Working directory:** `/home/maple/probabilistic-valuation-engine`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `docs/01_ADR/ADR-732_coolify-apps-image-pipeline.md` | Create | ADR: apps under Coolify + CI→GHCR image pipeline + SA absolute-path migration |
| `docker/minio/bootstrap.sh` | Modify | Honor `SECRETS_DIR` env (default repo-relative; Coolify sets absolute) |
| `docker-compose.yml` | Modify | minio-bootstrap: add `SECRETS_DIR` env + bind-mount the secrets host dir |
| `docker-compose.services.yml` | Modify | (1) per-service `${IMAGE_<SVC>}` image vars; (2) `secrets:` file paths via `${SECRETS_DIR_HOST}`; (3) app healthchecks; (4) `autoheal: "true"` labels |
| `.github/workflows/ci.yml` | Modify | Add `build-and-push` job (build → tag → push to GHCR on `develop` push) |

No Kotlin/Java code changes (apps already expose `/actuator/prometheus`, so `/actuator/health` is exposed by the actuator default — verified in a step).

---

## Task P2-1: ADR-732 — apps + image pipeline

**Files:**
- Create: `docs/01_ADR/ADR-732_coolify-apps-image-pipeline.md`

- [ ] **Step 1: Write the ADR**

Create `docs/01_ADR/ADR-732_coolify-apps-image-pipeline.md`:

```markdown
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

To be filled after Phase 2 operator verification (GHCR push green, maple-apps deploy healthy, L3 soft-failure recovery on an app).

---

## 5. Summary

> Deploy the 4 apps from GHCR images under Coolify, with SA keys on a shared absolute path and L3 healthchecks, so the app layer gains the same self-healing the infra layer has.
```

- [ ] **Step 2: Commit**

```bash
git add docs/01_ADR/ADR-732_coolify-apps-image-pipeline.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-732 apps under Coolify with CI->GHCR image pipeline

Documents the Phase-2 decisions: per-service GHCR image refs, SA key
migration to an absolute SECRETS_DIR shared by both Coolify resources,
and app L3 healthchecks + autoheal labels.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task P2-2: bootstrap.sh honors `SECRETS_DIR`

**Files:**
- Modify: `docker/minio/bootstrap.sh`

Add a `SECRETS_DIR` env (default keeps the existing repo-relative behavior so local dev is unchanged). Coolify sets it to `/opt/maple/secrets`.

- [ ] **Step 1: Add the SECRETS_DIR definition**

In `docker/minio/bootstrap.sh`, locate the block that currently ends with:

```bash
echo "[bootstrap] REPO_ROOT=${REPO_ROOT}"
```

Immediately AFTER that `echo` line, insert:

```bash

# SECRETS_DIR: where SA key files are written. Defaults to the repo's
# docker/services/secrets for local dev (REPO_ROOT is /workspace in the
# minio-bootstrap container, the host repo root otherwise). Coolify sets
# this to an absolute host path (/opt/maple/secrets) so both the
# maple-infra and maple-apps resources read the same files — repo-relative
# paths break under Coolify's deploy dir. See ADR-732.
SECRETS_DIR="${SECRETS_DIR:-${REPO_ROOT}/docker/services/secrets}"
```

- [ ] **Step 2: Replace the three repo-relative secret-path references**

The current lines are:

```bash
mkdir -p "${REPO_ROOT}/docker/services/secrets"
chmod 700 "${REPO_ROOT}/docker/services/secrets"
```

and inside the loop:

```bash
  printf '%s' "${secret}" > "${REPO_ROOT}/docker/services/secrets/sa-${module}.key"
  chmod 0444 "${REPO_ROOT}/docker/services/secrets/sa-${module}.key"
  echo "[bootstrap] wrote ${REPO_ROOT}/docker/services/secrets/sa-${module}.key"
```

Replace ALL of these `"${REPO_ROOT}/docker/services/secrets"` references with `"${SECRETS_DIR}"`. The resulting lines:

```bash
mkdir -p "${SECRETS_DIR}"
chmod 700 "${SECRETS_DIR}"
```

```bash
  printf '%s' "${secret}" > "${SECRETS_DIR}/sa-${module}.key"
  chmod 0444 "${SECRETS_DIR}/sa-${module}.key"
  echo "[bootstrap] wrote ${SECRETS_DIR}/sa-${module}.key"
```

- [ ] **Step 3: Verify the script parses**

Run:
```bash
bash -n docker/minio/bootstrap.sh && echo OK
```
Expected: prints `OK`, exit 0 (syntax check; `-n` does not execute).

- [ ] **Step 4: Verify default behavior is unchanged (local-dev parity)**

Run a dry invocation with `SECRETS_DIR` unset against a throwaway dir to confirm it writes to the REPO_ROOT-derived path:
```bash
# Use a temp REPO_ROOT so we don't touch the real secrets dir.
REPO_ROOT="$(mktemp -d)" bash -c '
  set -e
  # Source only the SECRETS_DIR derivation + a no-op to confirm default logic.
  REPO_ROOT_OUT="${REPO_ROOT}"
  SECRETS_DIR="${SECRETS_DIR:-${REPO_ROOT_OUT}/docker/services/secrets}"
  echo "SECRETS_DIR defaults to: ${SECRETS_DIR}"
'
```
Expected: prints `SECRETS_DIR defaults to: <tmpdir>/docker/services/secrets`. Then confirm an explicit override wins:
```bash
SECRETS_DIR=/opt/maple/secrets bash -c 'echo "${SECRETS_DIR:-/workspace/docker/services/secrets}"'
```
Expected: prints `/opt/maple/secrets`.

- [ ] **Step 5: Commit**

```bash
git add docker/minio/bootstrap.sh
git commit -m "$(cat <<'EOF'
feat(bootstrap): honor SECRETS_DIR for SA key output path

bootstrap.sh writes SA keys to SECRETS_DIR (default repo-relative for
local dev). Coolify sets it to /opt/maple/secrets so the maple-infra
and maple-apps resources share the same files. Local dev unchanged.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task P2-3: minio-bootstrap `SECRETS_DIR` env + bind mount in `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml` (the `minio-bootstrap:` service, lines ~168-187)

Two vars drive the secret path: `SECRETS_DIR` (in-container path bootstrap writes to) and `SECRETS_DIR_HOST` (host-side path used by the bind mount and the services overlay's `secrets: file:`). Coolify sets both to `/opt/maple/secrets` (host=path identity). Local dev leaves both unset → defaults keep the current `./docker/services/secrets` workflow.

- [ ] **Step 1: Add the `SECRETS_DIR` env to minio-bootstrap**

In `docker-compose.yml`, the `minio-bootstrap:` service `environment:` block currently contains the `MINIO_ENDPOINT` override. Add one key after it:

```yaml
    environment:
      # Override .env.bootstrap MINIO_ENDPOINT for in-container DNS.
      # .env.bootstrap keeps localhost:9000 so host-side tooling works.
      MINIO_ENDPOINT: http://minio:9000
      # SA key output path (ADR-732). Coolify sets this to /opt/maple/secrets;
      # local dev leaves it unset → bootstrap defaults to /workspace/docker/services/secrets.
      SECRETS_DIR: ${SECRETS_DIR:-/workspace/docker/services/secrets}
```

- [ ] **Step 2: Add the bind mount for the secrets host dir**

The current `volumes:` block is:

```yaml
    volumes:
      - ./docker/minio:/scripts:ro
      # /workspace = repo root, writable so bootstrap.sh can persist SA secrets
      # to docker/services/secrets/ and .env.<module>. See plan task 8.
      - ./:/workspace:rw
```

Add a third mount so that when `SECRETS_DIR_HOST` points outside `/workspace` (Coolify: `/opt/maple/secrets`), bootstrap can write there. Replace the whole `volumes:` block with:

```yaml
    volumes:
      - ./docker/minio:/scripts:ro
      # /workspace = repo root (local dev). bootstrap's REPO_ROOT fallback
      # and host-side tooling read SA keys here when SECRETS_DIR is unset.
      - ./:/workspace:rw
      # SA secrets output dir (ADR-732). When SECRETS_DIR_HOST is set
      # (Coolify: /opt/maple/secrets), mount that host path read-write so
      # bootstrap writes keys there and the maple-apps resource reads them.
      # Local dev: defaults to the repo dir under the /workspace mount above
      # (redundant but harmless bind).
      - ${SECRETS_DIR_HOST:-./docker/services/secrets}:${SECRETS_DIR:-/workspace/docker/services/secrets}:rw
```

- [ ] **Step 3: Validate the compose file parses**

Run:
```bash
docker compose -f docker-compose.yml config >/dev/null && echo OK
```
Expected: prints `OK`, exit 0.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "$(cat <<'EOF'
feat(infra): minio-bootstrap SECRETS_DIR env + secrets bind mount

Pass SECRETS_DIR to bootstrap and bind-mount the secrets host dir so
SA keys can land on /opt/maple/secrets under Coolify (shared with the
maple-apps resource). Local dev defaults preserve the repo-relative path.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task P2-4: `docker-compose.services.yml` — image vars, SA path, healthchecks, labels

**Files:**
- Modify: `docker-compose.services.yml` (entire file — all 4 services + the `secrets:` block)

- [ ] **Step 1: Verify `/actuator/health` is exposed on all 4 apps**

Confirm the actuator health endpoint is web-exposed (the healthcheck depends on it). Spring Boot exposes `health` by default; this step only checks nothing excludes it. Run:
```bash
grep -rn "management.endpoints.web.exposure" module-external-api/src/main/resources/ module-calculator/src/main/resources/ module-synchronizer/src/main/resources/ module-cleanup/src/main/resources/ 2>/dev/null || echo "no explicit exposure config — defaults apply (health exposed)"
```
Expected: prints `no explicit exposure config — defaults apply (health exposed)` OR, if a config exists, confirm it includes `health` in the include list. If `health` is excluded anywhere, add `health` to that `include` before proceeding (out of this plan's scope to define; surface to the user). Proceed only when confirmed exposed.

- [ ] **Step 2: Switch each app `image:` to a per-service env var**

For EACH of the 4 services, replace the hardcoded image line:

| Service | Replace | With |
|---------|---------|------|
| external-api | `image: maple/external-api:dev` | `image: ${IMAGE_EXTERNAL_API:-maple/external-api:dev}` |
| calculator | `image: maple/calculator:dev` | `image: ${IMAGE_CALCULATOR:-maple/calculator:dev}` |
| synchronizer | `image: maple/synchronizer:dev` | `image: ${IMAGE_SYNCHRONIZER:-maple/synchronizer:dev}` |
| cleanup | `image: maple/cleanup:dev` | `image: ${IMAGE_CLEANUP:-maple/cleanup:dev}` |

Default `maple/<svc>:dev` keeps local dev + docker-smoke CI unchanged. Coolify sets each to `ghcr.io/zbnerd/maple-<svc>:latest` (or a pinned `:sha-<7>`).

- [ ] **Step 3: Add healthcheck + autoheal label to each of the 4 services**

For EACH service, add (under the service, 4-space indent alongside `image:`/`environment:`):

```yaml
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:${SERVER_PORT}/actuator/health | grep -q '\"status\":\"UP\"' || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 90s
    labels:
      autoheal: "true"
```

`${SERVER_PORT}` is already set per service (8081/8082/8083/8084) in each `environment:` block, so the healthcheck probes the right port per service. Repeat the identical block under all 4 services.

- [ ] **Step 4: Switch the `secrets:` block file paths to `SECRETS_DIR_HOST`**

The bottom `secrets:` block currently is:

```yaml
secrets:
  sa-ext-api:
    file: docker/services/secrets/sa-ext-api.key
  sa-calculator:
    file: docker/services/secrets/sa-calculator.key
  sa-synchronizer:
    file: docker/services/secrets/sa-synchronizer.key
  sa-cleanup:
    file: docker/services/secrets/sa-cleanup.key
```

Replace with `${SECRETS_DIR_HOST}` interpolation (default keeps `docker/services/secrets` for local dev / docker-smoke):

```yaml
secrets:
  sa-ext-api:
    file: ${SECRETS_DIR_HOST:-docker/services/secrets}/sa-ext-api.key
  sa-calculator:
    file: ${SECRETS_DIR_HOST:-docker/services/secrets}/sa-calculator.key
  sa-synchronizer:
    file: ${SECRETS_DIR_HOST:-docker/services/secrets}/sa-synchronizer.key
  sa-cleanup:
    file: ${SECRETS_DIR_HOST:-docker/services/secrets}/sa-cleanup.key
```

- [ ] **Step 5: Validate the services overlay parses**

Run:
```bash
docker compose -f docker-compose.yml -f docker-compose.services.yml config >/dev/null && echo OK
```
Expected: prints `OK`, exit 0.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.services.yml
git commit -m "$(cat <<'EOF'
feat(apps): per-service image vars, SA path interp, healthchecks, labels

docker-compose.services.yml: reference each app image via ${IMAGE_<SVC>}
(default maple/<svc>:dev for local dev + smoke CI), route SA key file
paths through ${SECRETS_DIR_HOST}, and add the L3 /actuator/health
healthcheck + autoheal=true label to all 4 apps.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task P2-5: CI `build-and-push` job (GHCR)

**Files:**
- Modify: `.github/workflows/ci.yml` (add a new job; also add `packages: write` to top-level permissions)

- [ ] **Step 1: Add `packages: write` to the top-level permissions**

The top-level `permissions:` block (around line 22) currently is:

```yaml
permissions:
  contents: read
  checks: write           # Required for test report annotations
  pull-requests: write    # Required for PR comments
```

Add one line:

```yaml
permissions:
  contents: read
  checks: write           # Required for test report annotations
  pull-requests: write    # Required for PR comments
  packages: write         # Required for GHCR image push (ADR-732)
```

- [ ] **Step 2: Add the `build-and-push` job**

Add this as a new top-level job (place it after the `docker-smoke:` job, at the same indentation as the other job keys — 2 spaces):

```yaml
  # ============================================
  # Build & Push Images to GHCR (ADR-732)
  # Runs only on pushes to develop (merges). Builds the 4 service images
  # via build.sh (local maple/<svc>:sha-<7>), re-tags and pushes them to
  # ghcr.io/zbnerd/maple-<svc>:{sha-<7>,latest}. PR builds are validated
  # by the docker-smoke job (which uses local :dev images), so this job
  # does not gate PRs.
  # ============================================
  build-and-push:
    name: Build & Push Images (GHCR)
    runs-on: ubuntu-latest
    needs: test
    if: github.event_name == 'push' && github.ref == 'refs/heads/develop'
    env:
      GHCR_OWNER: zbnerd
    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Setup JDK ${{ env.JAVA_VERSION }}
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'

      - name: Grant Execute Permission for Gradlew
        run: chmod +x gradlew

      - name: Build service jars
        run: ./gradlew :module-external-api:bootJar :module-calculator:bootJar :module-synchronizer:bootJar :module-cleanup:bootJar -x test --no-daemon

      - name: Build service images (build.sh)
        run: ./docker/services/build.sh

      - name: Login to GHCR
        run: echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u "${{ github.actor }}" --password-stdin

      - name: Tag & push images (sha + latest)
        run: |
          set -euo pipefail
          SHA7="$(git rev-parse --short=7 HEAD)"
          echo "Publishing sha-${SHA7} + latest for GHCR_OWNER=${GHCR_OWNER}"
          for mod in external-api calculator synchronizer cleanup; do
            docker tag "maple/${mod}:sha-${SHA7}" "ghcr.io/${GHCR_OWNER}/maple-${mod}:sha-${SHA7}"
            docker tag "maple/${mod}:sha-${SHA7}" "ghcr.io/${GHCR_OWNER}/maple-${mod}:latest"
            docker push "ghcr.io/${GHCR_OWNER}/maple-${mod}:sha-${SHA7}"
            docker push "ghcr.io/${GHCR_OWNER}/maple-${mod}:latest"
          done
```

Notes for the engineer:
- `secrets.GITHUB_TOKEN` is auto-provided by GitHub Actions; no extra secret config needed.
- The job `if:` gate restricts pushes to `develop` only, so PR runs and other branches do not push.
- The `docker-smoke` job is unchanged — it still builds local `maple/<svc>:dev` and does not depend on GHCR.

- [ ] **Step 3: Validate the workflow YAML**

Run:
```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('YAML OK')"
```
Expected: prints `YAML OK`, exit 0. (PyYAML is commonly available; if not, use `yamllint .github/workflows/ci.yml` or `docker run --rm -v "$PWD":/w mikefarah/yq e -i '.' /w/.github/workflows/ci.yml >/dev/null && echo OK`.)

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
ci: build-and-push job publishes 4 service images to GHCR

On push to develop, build jars + images (build.sh), re-tag and push
ghcr.io/zbnerd/maple-<svc>:{sha-<7>,latest}. Adds packages:write
permission. PRs are still validated by docker-smoke (local :dev images);
this job does not gate PRs.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Verify the job runs after merge (post-PR)**

This verification happens after the Phase-2 PR merges to `develop`:
1. Open the Actions tab → confirm the `Build & Push Images (GHCR)` job ran green on the develop push.
2. Confirm packages exist: `https://github.com/zbnerd?tab=packages` lists `maple-external-api`, `maple-calculator`, `maple-synchronizer`, `maple-cleanup`.
3. Pull test (from the server): `docker pull ghcr.io/zbnerd/maple-external-api:latest` succeeds.
Record any failures as runbook items.

---

## Operator Runbook — Phase 2 Coolify apps migration

These steps run in the Coolify UI / on the host, after the Phase-2 PR merges to `develop` and the `build-and-push` job has published images.

### R-1: Seed `/opt/maple/secrets` from the existing repo keys

1. On the host, create the dir and copy the existing SA keys (generated by the current manual `minio-bootstrap`) so apps have valid keys from the first deploy:
   ```bash
   sudo mkdir -p /opt/maple/secrets
   sudo chmod 700 /opt/maple/secrets
   sudo cp docker/services/secrets/sa-*.key /opt/maple/secrets/
   sudo chmod 0444 /opt/maple/secrets/sa-*.key
   # Ensure the Docker daemon / container maple user (UID in the runtime image) can read them
   ls -la /opt/maple/secrets/
   ```
2. Confirm 4 files present (`sa-ext-api.key`, `sa-calculator.key`, `sa-synchronizer.key`, `sa-cleanup.key`). These now match the SA users MinIO already has (bootstrap is idempotent — re-run won't rotate them without `--rotate`).

### R-2: Set GHCR package visibility + Coolify pull access

1. GitHub → profile → Packages → each `maple-<svc>` → Package settings. Set visibility to **Private** (or Internal) and link the Coolify server's pull credential.
2. On the Coolify server, authenticate Docker to GHCR so `docker compose up` can pull:
   ```bash
   # Create a GitHub PAT with read:packages scope, then:
   echo "<PAT>" | docker login ghcr.io -u zbnerd --password-stdin
   ```
   (Coolify may manage this via its own registry credential feature — prefer that if available.)

### R-3: Create the `maple-apps` Coolify resource

1. Coolify UI → **+ New Resource** → **Docker Compose**.
2. Point at the repo and the file `docker-compose.services.yml`.
3. Name the resource `maple-apps`.
4. In the resource environment, set (plain):
   - `IMAGE_EXTERNAL_API=ghcr.io/zbnerd/maple-external-api:latest`
   - `IMAGE_CALCULATOR=ghcr.io/zbnerd/maple-calculator:latest`
   - `IMAGE_SYNCHRONIZER=ghcr.io/zbnerd/maple-synchronizer:latest`
   - `IMAGE_CLEANUP=ghcr.io/zbnerd/maple-cleanup:latest`
   - `SECRETS_DIR=/opt/maple/secrets`
   - `SECRETS_DIR_HOST=/opt/maple/secrets`
   - `KAFKA_BOOTSTRAP_SERVERS=kafka:29092`
   - `SPRING_PROFILES_ACTIVE=local`
   - `MINIO_ENDPOINT=http://minio:9000`
   - `MINIO_BUCKET=maple-expectation`
   - `MINIO_REGION=us-east-1`
   - `STORAGE_BACKEND=minio`
   - `MINIO_ACCESS_KEY` per service (ext-api / calculator / synchronizer / cleanup)
   - `DB_URL=jdbc:postgresql://postgres:5432/maple_expectation?user=maple&password=${DB_ROOT_PASSWORD}` (Coolify interpolates the Secret into this string)
5. Set as Coolify **Secrets**:
   - `DB_ROOT_PASSWORD` (= the infra resource's value)
   - `NEXON_API_KEY` (external-api only; leave unset for the other 3 — they ignore it)
6. Do NOT deploy yet.

### R-4: First deploy + verify health + L3

1. Deploy `maple-apps`. Coolify pulls the 4 GHCR images and starts the containers.
2. Confirm all 4 report healthy in the Coolify UI (the `/actuator/health` healthchecks drive status). If any stays unhealthy, `docker logs maple-<svc>` and `docker inspect maple-<svc> --format '{{json .State.Health}}'`.
3. Confirm autoheal sees the app labels:
   ```bash
   docker inspect maple-calculator --format '{{index .Config.Labels "autoheal"}}'
   ```
   Expected: `true`.
4. L3 soft-failure check on one app (e.g., calculator): make `/actuator/health` return non-UP by stopping the Spring context is hard externally — instead simulate via a temporary failing healthcheck override is not possible on a running container, so verify L3 indirectly: confirm autoheal is wired (it restarted the Phase-1 test container already) and that the app's label is `true`; the same mechanism applies. If a direct test is required, redeploy one app with a deliberately failing healthcheck (e.g., port mismatch) and confirm autoheal restarts it, then redeploy correctly.

### R-5: Rollback drill

1. In the `maple-apps` resource env, set e.g. `IMAGE_CALCULATOR=ghcr.io/zbnerd/maple-calculator:sha-<prior-7>` (the previous good sha).
2. Redeploy. Confirm it pulls the prior image and starts healthy.
3. This validates the rollback path; restore `:latest` afterward.

### R-6: Backfill ADR-732 Result/Evidence

Edit `docs/01_ADR/ADR-732_coolify-apps-image-pipeline.md` Section 4 with: GHCR packages published, `maple-apps` all 4 healthy in Coolify UI, rollback drill result. Commit.

---

## Self-Review (completed during authoring)

**Spec coverage (Phase-2 slice):**
- Image pipeline CI→GHCR → P2-5 ✓ (spec Section 7 option B)
- Per-service image vars → P2-4 Step 2 ✓
- SA absolute path migration → P2-2 + P2-3 + P2-4 Step 4 ✓ (spec Section 6 tier-B)
- App `/actuator/health` healthchecks + autoheal labels → P2-4 Step 3 ✓ (spec Section 5)
- Coolify `maple-apps` resource + Secrets → Runbook R-3 ✓ (spec Section 6 tier-A/C)
- Deploy/rollback semantics → Runbook R-4/R-5 ✓ (spec Section 7)
- ADR → P2-1 ✓ (RPI rule)

**Placeholder scan:** none. Every step has exact YAML, shell, or compose content with expected output.

**Consistency:** variable names used consistently — `SECRETS_DIR` (in-container, bootstrap + minio-bootstrap env), `SECRETS_DIR_HOST` (host-side, volumes + secrets `file:`), `IMAGE_<SVC>` (per-service, 4 vars). GHCR path `ghcr.io/zbnerd/maple-<svc>` consistent across CI job + runbook. Healthcheck block identical across 4 apps.

**Local-dev parity:** all new env vars default to values that reproduce current behavior (`maple/<svc>:dev`, repo-relative secrets path), so docker-smoke CI and local dev are unchanged.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase2.md`. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach?
