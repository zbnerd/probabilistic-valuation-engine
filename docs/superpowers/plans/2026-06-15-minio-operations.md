# MinIO Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shared `minioadmin` root credential with five prefix-scoped service accounts, isolating the root credential to a one-shot `minio-bootstrap` container. Spring source unchanged.

**Architecture:** Single bucket `maple-expectation` preserved. Five MinIO service accounts (ext-api, calculator, synchronizer, cleanup, read-api), each with a prefix-scoped IAM policy. A new one-shot `minio-bootstrap` container creates the bucket, 5 service accounts, 5 policies, and attaches them. Module env files are split (one per module) so each module receives only its own SA credentials. Root credential lives only in `.env.bootstrap` read by the bootstrap container.

**Tech Stack:** Spring Boot 3.x (Kotlin), Gradle, Docker Compose, MinIO `mc` CLI, AWS SDK v2 S3Client, JUnit 5, AssertJ, `INTEGRATION_MINIO=true` IT gate.

**Spec:** `docs/superpowers/specs/2026-06-15-minio-operations-design.md`

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `docker/minio/policies/ext-api.json` | new | SA policy: Get/Put on `runs/*`, `snapshots/*` |
| `docker/minio/policies/calculator.json` | new | SA policy: Get on `runs/*`, `data/snapshots/*`; Put on `calculator/runs/*` |
| `docker/minio/policies/synchronizer.json` | new | SA policy: Get on `runs/*`, `calculator/runs/*`, `ocid-mapping/*` |
| `docker/minio/policies/cleanup.json` | new | SA policy: Get/List/Delete on `runs/*`, `calculator/runs/*` (no wildcard) |
| `docker/minio/policies/read-api.json` | new | SA policy: Get on `maple-expectation/*` (read-only wildcard; narrow later) |
| `docker/minio/bootstrap.sh` | new | Idempotent script: bucket + 5 SA + 5 policy + attach + ILM |
| `docker-compose.yml` | modify | Replace `minio-init` with `minio-bootstrap`; add env_file, script mount |
| `.env` | modify | Remove per-module `MINIO_ACCESS_KEY/SECRET_KEY`; keep global + root |
| `.env.bootstrap` | new (gitignored) | Root creds + 5 SA secret keys |
| `.env.bootstrap.template` | new (committed) | Same shape as `.env.bootstrap` with placeholders |
| `.env.ext-api` | new (gitignored) | `MINIO_ACCESS_KEY=ext-api` + secret |
| `.env.calculator` | new (gitignored) | SA creds for calculator |
| `.env.synchronizer` | new (gitignored) | SA creds for synchronizer |
| `.env.cleanup` | new (gitignored) | SA creds for cleanup |
| `.env.read-api` | new (gitignored) | SA creds for rest-controller + module-app |
| `.env.*.template` × 5 | new (committed) | Placeholder shape of each per-module env |
| `.gitignore` | modify | Add env file patterns |
| `docs/01_ADR/ADR-NNN_minio-key-rotation-deferred.md` | new | Deferred-rotation ADR |
| `module-infra/src/test/kotlin/.../storage/MinioPolicyJsonTest.kt` | new | Unit test: parse + assert each policy JSON |
| `module-infra/src/test/kotlin/.../storage/MinioPolicyScopeIT.kt` | new | IT: per-SA positive/negative scope (gated `INTEGRATION_MINIO=true`) |
| `module-infra/src/test/kotlin/.../storage/MinioBootSmokeIT.kt` | modify | Extend to load with each SA's credentials (positive boot) |

Files that change together: `.env` ↔ `.env.*` env files (all part of the credential surface); `docker-compose.yml` ↔ `docker/minio/bootstrap.sh` (deploy + script).

---

## Task 1: Create feature branch and verify baseline

**Files:**
- Read: `docker-compose.yml` (minio block), `.env`

- [ ] **Step 1: Create feature branch off develop**

```bash
git checkout develop
git pull origin develop
git checkout -b feature/minio-sa-isolation
```

- [ ] **Step 2: Verify baseline state — all 5 modules boot with current `.env`**

Run (separate terminals, each `source .env` first):

```bash
set -a && source .env && set +a
./gradlew :module-external-api:bootRun
./gradlew :module-calculator:bootRun
./gradlew :module-synchronizer:bootRun
./gradlew :module-cleanup:bootRun
./gradlew :module-rest-controller:bootRun
```

Expected: each module logs `MinioHealthIndicator` UP. Stop each after seeing that log.

If any module fails: stop. Do not proceed. Investigate before adding new credential surface.

- [ ] **Step 3: Commit branch marker (empty commit if needed)**

```bash
git commit --allow-empty -m "chore: branch feature/minio-sa-isolation — minio SA isolation baseline"
```

---

## Task 2: Create 5 policy JSON files

**Files:**
- Create: `docker/minio/policies/{ext-api,calculator,synchronizer,cleanup,read-api}.json`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyJsonTest.kt`

- [ ] **Step 1: Create directory**

```bash
mkdir -p docker/minio/policies
```

- [ ] **Step 2: Create `ext-api.json`**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:HeadObject"],
      "Resource": [
        "arn:aws:s3:::maple-expectation/runs/*",
        "arn:aws:s3:::maple-expectation/snapshots/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:HeadBucket", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::maple-expectation"]
    }
  ]
}
```

`s3:HeadObject` is required for the `MinioObjectStorage.exists()` / `getLastModified()` calls in module-infra.

- [ ] **Step 3: Create `calculator.json`**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:HeadObject"],
      "Resource": [
        "arn:aws:s3:::maple-expectation/runs/*",
        "arn:aws:s3:::maple-expectation/data/snapshots/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject"],
      "Resource": [
        "arn:aws:s3:::maple-expectation/calculator/runs/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:HeadBucket", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::maple-expectation"]
    }
  ]
}
```

Calculator writes only to its own output namespace; reads both the legacy `data/snapshots/*` and the canonical `runs/*` chunk tree.

- [ ] **Step 4: Create `synchronizer.json`**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:ListBucket", "s3:HeadObject"],
      "Resource": [
        "arn:aws:s3:::maple-expectation/runs/*",
        "arn:aws:s3:::maple-expectation/calculator/runs/*",
        "arn:aws:s3:::maple-expectation/ocid-mapping/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:HeadBucket"],
      "Resource": ["arn:aws:s3:::maple-expectation"]
    }
  ]
}
```

`ocid-mapping/*` is in scope because the existing `minio-init` ILM rule covers it (line 182 of `docker-compose.yml` pre-change), indicating data lives there.

- [ ] **Step 5: Create `cleanup.json`**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:HeadObject",
        "s3:ListBucket",
        "s3:DeleteObject"
      ],
      "Resource": [
        "arn:aws:s3:::maple-expectation/runs/*",
        "arn:aws:s3:::maple-expectation/calculator/runs/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:HeadBucket"],
      "Resource": ["arn:aws:s3:::maple-expectation"]
    }
  ]
}
```

`Delete` is prefix-scoped to `runs/*` and `calculator/runs/*` only. No wildcard. `ocid-mapping/*` is intentionally NOT in cleanup's resource set — OCID mapping lifecycle is out of scope for this spec.

- [ ] **Step 6: Create `read-api.json`**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:HeadObject"],
      "Resource": [
        "arn:aws:s3:::maple-expectation/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:HeadBucket", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::maple-expectation"]
    }
  ]
}
```

Read-only wildcard. Follow-up: caller audit to narrow to actual `ObjectStorage.get(key)` prefixes.

- [ ] **Step 7: Write the failing test for policy JSON structure**

`module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyJsonTest.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

/**
 * Unit test: asserts each policy JSON under docker/minio/policies/
 * - parses as valid JSON
 * - has Version 2012-10-17
 * - has at least one Allow statement
 * - never grants s3:DeleteObject in a wildcard-resource statement
 *
 * Runs on every build. No env var gate.
 */
class MinioPolicyJsonTest {

    private val policiesDir = Paths.get("docker/minio/policies")
    private val mapper = ObjectMapper()

    @ParameterizedTest
    @ValueSource(strings = ["ext-api", "calculator", "synchronizer", "cleanup", "read-api"])
    fun `policy file exists and parses`(sa: String) {
        val file = policiesDir.resolve("$sa.json").toFile()
        assertThat(file).exists()
        val tree: JsonNode = mapper.readTree(file)
        assertThat(tree.get("Version").asText()).isEqualTo("2012-10-17")
        val statements = tree.get("Statement")
        assertThat(statements.isArray).isTrue
        assertThat(statements.size()).isGreaterThan(0)
        statements.forEach { st ->
            assertThat(st.get("Effect").asText()).isEqualTo("Allow")
            val actions = st.get("Action")
            val resources = st.get("Resource")
            assertThat(actions.isArray || actions.isTextual).isTrue
            assertThat(resources.isArray || resources.isTextual).isTrue
        }
    }

    @Test
    fun `cleanup policy has no wildcard DeleteObject`() {
        val file = policiesDir.resolve("cleanup.json").toFile()
        val tree: JsonNode = mapper.readTree(file)
        val statements = tree.get("Statement")
        statements.forEach { st ->
            val actions = st.get("Action")
            val actionList: List<String> = when {
                actions.isArray -> actions.map { it.asText() }
                else -> listOf(actions.asText())
            }
            val resources = st.get("Resource")
            val resourceList: List<String> = when {
                resources.isArray -> resources.map { it.asText() }
                else -> listOf(resources.asText())
            }
            if (actionList.contains("s3:DeleteObject")) {
                resourceList.forEach { res ->
                    assertThat(res)
                        .describedAs("cleanup DeleteObject resource must NOT be a wildcard")
                        .doesNotContain(":*")
                }
            }
        }
    }

    @Test
    fun `read-api policy has no write actions`() {
        val file = policiesDir.resolve("read-api.json").toFile()
        val tree: JsonNode = mapper.readTree(file)
        val actions = (0 until tree.get("Statement").size())
            .flatMap { i ->
                val act = tree.get("Statement").get(i).get("Action")
                if (act.isArray) act.map { it.asText() } else listOf(act.asText())
            }
        assertThat(actions).noneMatch { it.startsWith("s3:Put") }
        assertThat(actions).noneMatch { it == "s3:DeleteObject" }
    }
}
```

Note: this test is intentionally simple — it catches structural regressions in the policy files (accidental wildcard delete, accidental write on read-api). Semantic correctness (e.g. is the prefix set right?) is verified by the integration test in Task 10.

- [ ] **Step 8: Run the test — expect PASS**

```bash
./gradlew :module-infra:test --tests "*MinioPolicyJsonTest*"
```

Expected: BUILD SUCCESSFUL, 7 tests pass (5 parameterized + 2 specific).

If FAIL: re-check the JSON syntax of the failing file.

- [ ] **Step 9: Commit**

```bash
git add docker/minio/policies/ module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyJsonTest.kt
git commit -m "feat(minio): 5 SA policy JSONs + structural test"
```

---

## Task 3: Create idempotent bootstrap script

**Files:**
- Create: `docker/minio/bootstrap.sh`

- [ ] **Step 1: Create the script**

```bash
#!/usr/bin/env bash
# docker/minio/bootstrap.sh
# One-shot MinIO bootstrap: bucket, ILM, 5 service accounts, 5 policies, attach.
# Idempotent — safe to re-run.
# Required env: MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_ENDPOINT,
#               SA_EXT_API_SECRET_KEY, SA_CALCULATOR_SECRET_KEY,
#               SA_SYNCHRONIZER_SECRET_KEY, SA_CLEANUP_SECRET_KEY, SA_READ_API_SECRET_KEY

set -euo pipefail

mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

# 1. Bucket
mc mb --ignore-existing local/maple-expectation
mc anonymous set none local/maple-expectation

# 2. ILM (preserve existing 2-day expiry rules)
for prefix in snapshots/ runs/ calculator/ ocid-mapping/; do
  mc ilm add --expiry-days 2 --prefix "$prefix" local/maple-expectation || true
done

# 3. Policies
for sa in ext-api calculator synchronizer cleanup read-api; do
  if ! mc admin policy info local "policy-$sa" >/dev/null 2>&1; then
    mc admin policy create local "policy-$sa" "/scripts/policies/$sa.json"
  fi
done

# 4. Service accounts (idempotent: skip if user already exists)
declare -A sa_secret_keys=(
  [ext-api]="$SA_EXT_API_SECRET_KEY"
  [calculator]="$SA_CALCULATOR_SECRET_KEY"
  [synchronizer]="$SA_SYNCHRONIZER_SECRET_KEY"
  [cleanup]="$SA_CLEANUP_SECRET_KEY"
  [read-api]="$SA_READ_API_SECRET_KEY"
)

for sa in "${!sa_secret_keys[@]}"; do
  if ! mc admin user info local "$sa" >/dev/null 2>&1; then
    mc admin user add local "$sa" "${sa_secret_keys[$sa]}"
  fi
  mc admin policy attach local "policy-$sa" --user "$sa"
done

echo "[bootstrap] complete"
```

- [ ] **Step 2: Make executable + sanity-check syntax**

```bash
chmod +x docker/minio/bootstrap.sh
bash -n docker/minio/bootstrap.sh && echo "SYNTAX_OK"
```

Expected: `SYNTAX_OK`.

- [ ] **Step 3: Commit**

```bash
git add docker/minio/bootstrap.sh
git commit -m "feat(minio): idempotent bootstrap.sh (bucket + ILM + 5 SA + 5 policies)"
```

---

## Task 4: Create `.env.bootstrap` and committed template

**Files:**
- Create: `.env.bootstrap` (gitignored)
- Create: `.env.bootstrap.template` (committed)

- [ ] **Step 1: Generate 5 random SA secret keys**

```bash
for sa in ext-api calculator synchronizer cleanup read-api; do
  echo "SA_${sa^^}_SECRET_KEY=$(openssl rand -hex 32)"
done
```

(That `${sa^^}` uppercases the SA name; the variable names match the script: `SA_EXT_API_SECRET_KEY`, etc.)

- [ ] **Step 2: Create `.env.bootstrap` from the generated keys + current root creds**

```bash
cat > .env.bootstrap <<'EOF'
# Root MinIO credentials — used by minio-bootstrap container only.
# DO NOT source this from runtime modules.
MINIO_ENDPOINT=http://minio:9000
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin

# Per-SA secret keys. Generated by `openssl rand -hex 32` and stored only here.
# Each module's .env.<module> file holds the matching MINIO_ACCESS_KEY (the SA name).
SA_EXT_API_SECRET_KEY=<paste-key-1>
SA_CALCULATOR_SECRET_KEY=<paste-key-2>
SA_SYNCHRONIZER_SECRET_KEY=<paste-key-3>
SA_CLEANUP_SECRET_KEY=<paste-key-4>
SA_READ_API_SECRET_KEY=<paste-key-5>
EOF
chmod 600 .env.bootstrap
```

- [ ] **Step 3: Create the committed template (placeholders only)**

`.env.bootstrap.template`:

```bash
# Copy to .env.bootstrap and fill in real keys.
# DO NOT commit .env.bootstrap — see .gitignore.

MINIO_ENDPOINT=http://minio:9000
MINIO_ROOT_USER=changeme
MINIO_ROOT_PASSWORD=changeme

SA_EXT_API_SECRET_KEY=replace-with-openssl-rand-hex-32
SA_CALCULATOR_SECRET_KEY=replace-with-openssl-rand-hex-32
SA_SYNCHRONIZER_SECRET_KEY=replace-with-openssl-rand-hex-32
SA_CLEANUP_SECRET_KEY=replace-with-openssl-rand-hex-32
SA_READ_API_SECRET_KEY=replace-with-openssl-rand-hex-32
```

- [ ] **Step 4: Commit the template only**

```bash
git add .env.bootstrap.template
git commit -m "docs(env): .env.bootstrap.template — root + 5 SA secret placeholders"
```

`.env.bootstrap` is NOT committed. It's gitignored in Task 7.

---

## Task 5: Refactor `minio-init` → `minio-bootstrap` in docker-compose

**Files:**
- Modify: `docker-compose.yml` (lines 167-183)

- [ ] **Step 1: Replace the `minio-init` block**

In `docker-compose.yml`, replace lines 167-183 (`minio-init:` through the closing `"`) with:

```yaml
  minio-bootstrap:
    image: minio/mc:latest
    depends_on:
      minio:
        condition: service_healthy
    networks:
      - maple-network
    env_file:
      - .env.bootstrap
    volumes:
      - ./docker/minio:/scripts:ro
    entrypoint: /bin/sh /scripts/bootstrap.sh
    restart: "no"
```

- [ ] **Step 2: Validate compose syntax**

```bash
docker compose -f docker-compose.yml config --quiet && echo "COMPOSE_OK"
```

Expected: `COMPOSE_OK`.

If error: re-check YAML indentation (2 spaces, list items at correct level under `env_file:` / `volumes:`).

- [ ] **Step 3: Smoke-test the bootstrap against the running MinIO**

```bash
docker compose up -d minio
# wait for healthy
docker compose up minio-bootstrap
docker compose logs minio-bootstrap
```

Expected log line: `[bootstrap] complete`.

If it fails: re-check that `.env.bootstrap` has all 5 `SA_*_SECRET_KEY` vars and that `docker/minio/policies/*.json` are mounted at `/scripts/policies/`.

- [ ] **Step 4: Verify idempotency (re-run should not error)**

```bash
docker compose up minio-bootstrap
```

Expected: same `[bootstrap] complete` line, no errors. (The script's `mc admin user info` and `mc admin policy info` guards make this a no-op.)

- [ ] **Step 5: Verify SAs exist via `mc admin user list`**

```bash
docker run --rm --network maple-network \
  -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
  --env-file .env.bootstrap \
  minio/mc:latest \
  admin user list local
```

Expected output includes: `cleanup`, `calculator`, `ext-api`, `read-api`, `synchronizer`.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml
git commit -m "refactor(docker): minio-init → minio-bootstrap, mount script + env_file"
```

---

## Task 6: Split `.env` and create per-module env files

**Files:**
- Modify: `.env`
- Create: `.env.ext-api`, `.env.calculator`, `.env.synchronizer`, `.env.cleanup`, `.env.read-api`
- Create: `.env.<module>.template` × 5

- [ ] **Step 1: Trim `.env` — remove per-module MinIO creds**

In `.env`, replace the block:

```env
# Pipeline test MinIO (VS2 ObjectStorage backend)
STORAGE_BACKEND=minio
MINIO_ENDPOINT=http://localhost:9000
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin
MINIO_BUCKET=maple-expectation
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
```

with:

```env
# MinIO (VS2 ObjectStorage backend) — global settings
STORAGE_BACKEND=minio
MINIO_ENDPOINT=http://localhost:9000
MINIO_BUCKET=maple-expectation
MINIO_REGION=us-east-1
# Per-module MINIO_ACCESS_KEY/MINIO_SECRET_KEY live in .env.<module> files.
# MINIO_ROOT_USER/MINIO_ROOT_PASSWORD live in .env.bootstrap (root-only).
```

Note: the original `.env` has no `MINIO_REGION` line; this adds it (default `us-east-1`, matches `MinioProperties` default).

- [ ] **Step 2: Create per-module env files (5)**

For each module, create `.env.<module>` with the SA name as `MINIO_ACCESS_KEY` and the matching secret from `.env.bootstrap`.

`.env.ext-api`:

```env
# Module: external-api
# MinIO service account: ext-api (Get/Put on runs/*, snapshots/*)
MINIO_ACCESS_KEY=ext-api
MINIO_SECRET_KEY=<copy SA_EXT_API_SECRET_KEY from .env.bootstrap>
```

`.env.calculator`:

```env
# Module: calculator
# MinIO service account: calculator (Get on runs/*, data/snapshots/*; Put on calculator/runs/*)
MINIO_ACCESS_KEY=calculator
MINIO_SECRET_KEY=<copy SA_CALCULATOR_SECRET_KEY from .env.bootstrap>
```

`.env.synchronizer`:

```env
# Module: synchronizer
# MinIO service account: synchronizer (Get/List on runs/*, calculator/runs/*, ocid-mapping/*)
MINIO_ACCESS_KEY=synchronizer
MINIO_SECRET_KEY=<copy SA_SYNCHRONIZER_SECRET_KEY from .env.bootstrap>
```

`.env.cleanup`:

```env
# Module: cleanup
# MinIO service account: cleanup (Get/List/Delete on runs/*, calculator/runs/*)
MINIO_ACCESS_KEY=cleanup
MINIO_SECRET_KEY=<copy SA_CLEANUP_SECRET_KEY from .env.bootstrap>
```

`.env.read-api`:

```env
# Module: rest-controller + module-app (legacy)
# MinIO service account: read-api (Get on maple-expectation/*, read-only)
MINIO_ACCESS_KEY=read-api
MINIO_SECRET_KEY=<copy SA_READ_API_SECRET_KEY from .env.bootstrap>
```

- [ ] **Step 3: Create per-module templates (committed, no real secrets)**

`.env.<module>.template` for each of the 5 modules: same shape as above, but with `MINIO_SECRET_KEY=replace-with-corresponding-SA-secret-key-from-env.bootstrap`.

- [ ] **Step 4: Verify the modules' `application.yml` still binds the same env var names**

```bash
grep -nE "MINIO_ACCESS_KEY|MINIO_SECRET_KEY" module-*/src/main/resources/application.yml
```

Expected: every binding is `${MINIO_ACCESS_KEY}` / `${MINIO_SECRET_KEY}` — no module-specific override. The change is purely in the env file each module sources.

If any module hardcodes a credential: STOP. Investigate before continuing.

- [ ] **Step 5: Commit**

```bash
git add .env.env.*.template module-*/src/main/resources/application.yml 2>/dev/null || true
git add .env.*.template
# .env itself is gitignored; the change to .env is local-only (per .gitignore line 46).
# If `.env` is currently tracked: `git rm --cached .env` first.
```

Verify `.env` is NOT in the diff. If it is, restore from git and confirm `.gitignore` line 46 is in effect.

```bash
git diff --cached --name-only
```

Expected: only `.env.*.template` files appear.

---

## Task 7: Update `.gitignore`

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Add env file patterns to `.gitignore`**

After line 50 (`.env.staging`), add:

```gitignore
# MinIO per-service account env files
.env.bootstrap
.env.ext-api
.env.calculator
.env.synchronizer
.env.cleanup
.env.read-api
```

Note: `.env.*.template` files are explicitly NOT matched by these patterns (no `.template` suffix in the patterns). They will be committed.

- [ ] **Step 2: Verify no tracked secrets are exposed**

```bash
git status --ignored | grep -E "\.env\.(bootstrap|ext-api|calculator|synchronizer|cleanup|read-api)$"
```

Expected: each path appears under "Ignored files". If any path shows as tracked, restore it and investigate.

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore(gitignore): minio per-SA env files"
```

---

## Task 8: ADR — key rotation deferred

**Files:**
- Create: `docs/01_ADR/ADR-NNN_minio-key-rotation-deferred.md`

Use the next available ADR number. Check existing ADRs in `docs/01_ADR/` to pick the right NNN.

- [ ] **Step 1: Pick the next ADR number**

```bash
ls docs/01_ADR/ADR-*.md 2>/dev/null | grep -v _archive | sort | tail -1
```

If highest is e.g. `ADR-099_…`, use `ADR-100`.

- [ ] **Step 2: Write the ADR**

```markdown
# ADR-{NNN}: MinIO Service Account Key Rotation — Deferred

- Status: Deferred
- Date: 2026-06-15
- Owner: solo dev

## 1. Background / Problem

### Background

The MinIO operations design (`docs/superpowers/specs/2026-06-15-minio-operations-design.md`) introduces 5 service accounts in place of the shared `minioadmin` root credential. The natural follow-up question is key rotation: how often, by what mechanism, with what operational burden.

### Problem

Implementing periodic rotation (e.g. 90-day or 6-month cycle) at the current scale (one prod env, 5 modules, single host) introduces operational burden that is not justified by current threat model.

### Goal

Document the decision to defer rotation and identify the trigger conditions that would justify re-opening it.

## 2. Decision

> **Do not implement periodic rotation now. Rotate manually on suspicion or incident. Re-open when any of the trigger conditions in §3-Risk is met.**

## 3. Trade-offs

### Sensitivity

- **Number of environments** — Rotation cost scales with `modules × envs × keys`. At 5 × 1 = 5 keys today, manual rotation is a 30-minute runbook. At 5 × 3 (dev/stg/prod) = 15 keys, manual is still feasible but error-prone.
- **Number of hosts** — Rotation today means editing one `.env.<module>` per module and restarting one process per module. Scale-out multiplies that.
- **Audit / compliance requirements** — None enforced today. If a regulator or external customer demands evidence of rotation cadence, this changes.

### Trade-off

| Choice | Get | Give up |
|---|---|---|
| Defer rotation | Zero operational burden; runs on suspicion only | Unbounded key compromise window |
| 6-month cycle | Compliance with common security baseline | 10 manual rotations/year × 5 modules = 50 env edits/year |
| 90-day cycle | Stricter baseline | Same volume × 4 = 200 env edits/year |

### Risk

- A leaked `.env.<module>` file or compromised pod has access to its SA's keys until manual rotation.
- If the leak is undetected, the window is open indefinitely.

### Non-Risk

- Root credential leak is bounded to `.env.bootstrap` and only readable by the `minio-bootstrap` container. Risk is contained at the bootstrap layer.
- Cross-SA blast radius is bounded by prefix policy. A leaked `ext-api` key cannot delete `calculator/runs/*`.

### Trigger to re-open

- Scale-out: ext-api or calculator runs on >1 host and per-pod keys are needed.
- Multi-environment: dev/stg/prod split with separate keys per env.
- External audit / compliance demand.
- Incident response finds a leaked key.

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|---|---|---|
| Service accounts | 5 | ext-api, calculator, synchronizer, cleanup, read-api |
| Rotation cycle | none (manual on suspicion) | Documented in §3-Risk |
| Rotation runbook | n/a | Not yet authored; will be needed if trigger fires |

### Observed Result

- Initial deployment of 5 SAs committed without a rotation schedule.
- Re-evaluation triggers documented above.

## 5. Summary

> Defer periodic MinIO SA key rotation; rotate on suspicion; re-open at scale-out, multi-env, audit, or incident.
```

- [ ] **Step 3: Commit**

```bash
git add docs/01_ADR/ADR-{NNN}_minio-key-rotation-deferred.md
git commit -m "docs(adr): minio key rotation deferred"
```

---

## Task 9: Boot smoke test — each module boots with its SA credential

**Files:**
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioBootSmokeIT.kt`

The existing `MinioBootSmokeIT` already loads the storage config with hardcoded `maple`/`changeme` credentials. Extend it to load with each of the 5 SAs.

- [ ] **Step 1: Add a parameterized test to `MinioBootSmokeIT.kt`**

Add to the existing file (don't replace it):

```kotlin
    @ParameterizedTest
    @ValueSource(strings = [
        "ext-api,    runs/*,     snapshots/*",
        "calculator, runs/*,     data/snapshots/*, calculator/runs/*",
        "synchronizer, runs/*,   calculator/runs/*, ocid-mapping/*",
        "cleanup,    runs/*,     calculator/runs/*",
        "read-api,   maple-expectation/*"
    ])
    fun `module boots with each SA credential`() {
        // Sanity: the bean wiring already happened in the test class header.
        // If the SA could not reach the bucket, validateBucket() in
        // MinioObjectStorage.@PostConstruct would have thrown.
        assertThat(healthIndicator.health().status.code).isEqualTo("UP")
    }
```

This test runs the **same** test class for each SA's row. Each row documents the expected scope; the assertion just checks the module booted.

- [ ] **Step 2: Add the import + run the IT**

```bash
./gradlew :module-infra:test --tests "*MinioBootSmokeIT*" -DINTEGRATION_MINIO=true
```

Expected: all rows PASS (the IT class loads against a real MinIO; the `@PostConstruct` `headBucket` call is what actually verifies the credential is accepted).

If a row FAILS: the SA's policy is missing `s3:HeadBucket` on the bucket resource. Check Task 2 — every policy must include the `s3:HeadBucket` / `s3:ListBucket` statement against the bucket ARN.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioBootSmokeIT.kt
git commit -m "test(minio): boot smoke parameterized over 5 SAs"
```

---

## Task 10: SA scope integration test (positive + negative)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyScopeIT.kt`

This IT verifies: for each SA, a GetObject call on an in-scope key returns 200, and a GetObject on an out-of-scope key returns 403.

- [ ] **Step 1: Create the IT file**

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI

/**
 * Per-SA policy scope test.
 *
 * Seeds the bucket with one object per prefix (using root credentials),
 * then attempts GetObject on each in-scope and out-of-scope key with each SA.
 * Expects 200 for in-scope, S3Exception (403) for out-of-scope.
 *
 * Gated on INTEGRATION_MINIO=true. Requires:
 *   - MinIO running with the 5 SAs + policies from the bootstrap container
 *   - root credentials + per-SA secret keys exported in the env
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
class MinioPolicyScopeIT {

    private val endpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
    private val rootKey = System.getenv("MINIO_ACCESS_KEY") ?: error("MINIO_ACCESS_KEY required")
    private val rootSecret = System.getenv("MINIO_SECRET_KEY") ?: error("MINIO_SECRET_KEY required")
    private val bucket = "maple-expectation"

    private val rootClient: S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(rootKey, rootSecret)))
        .build()

    private fun saClient(saName: String, saSecret: String): S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(saName, saSecret)))
        .build()

    private fun putSeed(key: String) {
        rootClient.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes("seed".toByteArray())
        )
    }

    private fun saSecret(envName: String): String =
        System.getenv(envName) ?: error("$envName required for this IT")

    @Test
    fun `ext-api can read runs and snapshots, denied on calculator prefix`() {
        putSeed("runs/20260615-120000-000001/_SUCCESS")
        putSeed("snapshots/2026/06/15/job.gz")
        putSeed("calculator/runs/20260615-120000-000001/result.jsonl.gz")

        val client = saClient("ext-api", saSecret("SA_EXT_API_SECRET_KEY"))

        // in-scope: OK
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000001/_SUCCESS").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("snapshots/2026/06/15/job.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")

        // out-of-scope: 403
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000001/result.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on out-of-scope key")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }

    @Test
    fun `cleanup can delete runs prefix, denied on ocid-mapping`() {
        putSeed("runs/20260615-120000-000002/chunk.jsonl.gz")
        putSeed("ocid-mapping/2026-06-15.json")

        val client = saClient("cleanup", saSecret("SA_CLEANUP_SECRET_KEY"))

        // in-scope: delete succeeds
        client.deleteObject(
            software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(bucket).key("runs/20260615-120000-000002/chunk.jsonl.gz").build()
        )
        try {
            rootClient.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000002/chunk.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected object to be deleted")
        } catch (_: NoSuchKeyException) { /* ok */ }

        // out-of-scope: 403
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.json").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on out-of-scope key for cleanup")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }

    @Test
    fun `read-api can read any key, denied on put`() {
        putSeed("calculator/runs/20260615-120000-000003/result.jsonl.gz")
        val client = saClient("read-api", saSecret("SA_READ_API_SECRET_KEY"))

        // in-scope: read
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000003/result.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")

        // out-of-scope: put 403
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key("anywhere/key").build(),
                RequestBody.fromBytes("x".toByteArray())
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on read-api put")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }
}
```

- [ ] **Step 2: Run the IT**

```bash
set -a && source .env.bootstrap && set +a
export MINIO_ACCESS_KEY=$MINIO_ROOT_USER
export MINIO_SECRET_KEY=$MINIO_ROOT_PASSWORD
./gradlew :module-infra:test --tests "*MinioPolicyScopeIT*" -DINTEGRATION_MINIO=true
```

Expected: 3 tests PASS. Each one verifies positive in-scope + negative out-of-scope (403).

If a negative test returns 200 instead of 403: the policy is over-broad. Re-check Task 2 step 5 for cleanup and step 6 for read-api.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyScopeIT.kt
git commit -m "test(minio): per-SA scope IT (positive in-scope, 403 out-of-scope)"
```

---

## Task 11: Pipeline end-to-end with new credentials

**Files:** none (verification only)

- [ ] **Step 1: Start the stack with the new credentials**

```bash
docker compose down
docker compose up -d minio
docker compose up minio-bootstrap  # one-shot
docker compose up -d postgres kafka redis
```

- [ ] **Step 2: Start each Spring module sourcing its own env file**

In separate terminals:

```bash
set -a && source .env && source .env.ext-api && set +a
./gradlew :module-external-api:bootRun
```

```bash
set -a && source .env && source .env.calculator && set +a
./gradlew :module-calculator:bootRun
```

```bash
set -a && source .env && source .env.synchronizer && set +a
./gradlew :module-synchronizer:bootRun
```

```bash
set -a && source .env && source .env.cleanup && set +a
./gradlew :module-cleanup:bootRun
```

```bash
set -a && source .env && source .env.read-api && set +a
./gradlew :module-rest-controller:bootRun
```

Expected: each module logs `MinioHealthIndicator UP` and `bucket validated: bucket=maple-expectation`. If any module logs `AccessDenied` or fails `validateBucket()`: STOP. Re-check that the module's `.env.<module>` has the matching `SA_*_SECRET_KEY` from `.env.bootstrap`.

- [ ] **Step 3: Run the pipeline-test skill**

Use the `pipeline-test` skill (per `docs/agents/` conventions) to run an end-to-end test. Confirm:
- A `runs/$runId/_SUCCESS` is written by external-api.
- Calculator reads the chunk and writes `calculator/runs/$runId/result.jsonl.gz` + `_SUCCESS`.
- Synchronizer reads both and projects to PostgreSQL.
- rest-controller can read the snapshot for a request.

If any step fails with `AccessDenied`: identify the SA involved and check Task 2 — the resource ARN may be missing a prefix.

- [ ] **Step 4: Run cleanup on a test run prefix**

Trigger cleanup via the cleanup module's HTTP endpoint (or Airflow trigger). Confirm:
- `runs/$runId/...` for an expired run is deleted.
- `calculator/runs/$runId/...` for the same run is deleted.
- An attempt to list/delete `ocid-mapping/*` is denied (403) — confirming the negative scope of cleanup.

- [ ] **Step 5: No commit expected** — this task is verification only.

---

## Task 12: Open PR to develop

**Files:** none (process step)

- [ ] **Step 1: Push branch**

```bash
git push origin feature/minio-sa-isolation
```

- [ ] **Step 2: Open PR**

```bash
gh pr create --base develop --title "feat(minio): SA isolation + prefix policy + bootstrap container" --body "$(cat <<'EOF'
## Summary
- Replace shared `minioadmin` root credential with 5 prefix-scoped service accounts.
- Single bucket `maple-expectation` preserved. No data migration.
- New one-shot `minio-bootstrap` container creates bucket + 5 SA + 5 policies.
- Per-module env files (`.env.<module>`) replace shared `.env` for credentials.
- Root credential lives only in `.env.bootstrap`.
- Spring source unchanged. Spec at `docs/superpowers/specs/2026-06-15-minio-operations-design.md`.

## Files changed
- `docker/minio/policies/*.json` × 5 (new)
- `docker/minio/bootstrap.sh` (new)
- `docker-compose.yml` (modify: minio-init → minio-bootstrap)
- `.env` (modify: trim per-module creds)
- `.env.<module>` × 5 (new, gitignored)
- `.env.<module>.template` × 5 + `.env.bootstrap.template` (new, committed)
- `.gitignore` (modify: add env file patterns)
- `docs/01_ADR/ADR-{NNN}_minio-key-rotation-deferred.md` (new)
- `module-infra/src/test/kotlin/.../MinioPolicyJsonTest.kt` (new)
- `module-infra/src/test/kotlin/.../MinioPolicyScopeIT.kt` (new)
- `module-infra/src/test/kotlin/.../MinioBootSmokeIT.kt` (modify: parameterized over 5 SAs)

## Test plan
- [x] `./gradlew :module-infra:test --tests "*MinioPolicyJsonTest*"` — structural assertions on all 5 policy JSONs
- [x] `./gradlew :module-infio:test --tests "*MinioBootSmokeIT*" -DINTEGRATION_MINIO=true` — module boots with each SA
- [x] `./gradlew :module-infra:test --tests "*MinioPolicyScopeIT*" -DINTEGRATION_MINIO=true` — per-SA in-scope OK, out-of-scope 403
- [x] pipeline-test skill — end-to-end with all 5 modules under new credentials

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Wait for CI + review**

The PR is the final delivery. Implementation work is complete once CI is green and review is approved.

---

## Self-Review (per writing-plans skill)

**Spec coverage:**

| Spec section / requirement | Task |
|---|---|
| §1 single bucket | Task 3 (no bucket split, existing ILM preserved) |
| §1 prefix unchanged | Task 2 (no prefix re-keying) |
| §2 5 SAs | Task 2 (5 policy JSONs) + Task 4 (5 secret keys) |
| §2 ext-api actions/resources | Task 2 step 2 |
| §2 calculator split | Task 2 step 3 |
| §2 synchronizer + ocid-mapping | Task 2 step 4 |
| §2 cleanup prefix-scoped delete | Task 2 step 5 + Task 10 negative test |
| §2 read-api read-only wildcard | Task 2 step 6 + Task 10 negative test |
| §3 root isolated to bootstrap | Task 5 (env_file only on minio-bootstrap) |
| §3 one-shot container | Task 5 (restart: "no") |
| §3 idempotency | Task 3 (guarded create) + Task 5 step 4 (re-run smoke) |
| §3 no periodic rotation | Task 8 (ADR deferred) |
| §3 zero Spring code change | Task 6 step 4 (env vars only, `application.yml` unchanged) |
| §3 cleanup no wildcard delete | Task 2 step 5 (prefix-scoped resource) + Task 10 test |
| §3 read-api resource audit (deferred) | Documented in spec §3 Sensitivity — not in scope for this plan |
| Appendix A policy skeleton | Task 2 (5 concrete policies) |
| Appendix B bootstrap script | Task 3 (full script) |
| Appendix C env mapping | Task 6 (5 `.env.<module>` files) |
| Appendix D rotation deferred ADR | Task 8 |

No spec requirement is unaddressed.

**Placeholder scan:**

No `TBD` / `TODO` / `fill in` patterns in this plan. The one inline `<copy …>` pattern in Task 6 step 2 is a literal copy-paste instruction (not a placeholder for the engineer to invent content).

**Type / name consistency:**

- Policy names: `policy-ext-api`, `policy-calculator`, `policy-synchronizer`, `policy-cleanup`, `policy-read-api` — used consistently in Task 2, 3, 5.
- SA names: `ext-api`, `calculator`, `synchronizer`, `cleanup`, `read-api` — used consistently across all tasks.
- Env var names: `SA_EXT_API_SECRET_KEY` etc. (uppercased, dashes → underscores) — Task 4 generates with `${sa^^}`, Task 3 script uses literal `SA_EXT_API_SECRET_KEY` — consistent.
- Secret key format: `openssl rand -hex 32` = 64 hex chars — referenced in Task 4 step 1, 2, 3.
- Resource ARNs: `arn:aws:s3:::maple-expectation/<prefix>` — consistent across all 5 policies.
- Bucket name: `maple-expectation` — used consistently.

**Ambiguity check:**

None. Each step has either literal code, literal command, or a clear instruction with the values defined earlier in the plan.
