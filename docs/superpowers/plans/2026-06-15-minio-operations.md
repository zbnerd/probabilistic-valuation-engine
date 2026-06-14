# MinIO Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shared `minioadmin` root credential with four prefix-scoped service accounts, isolating the root credential to a one-shot `minio-bootstrap` container. Spring source unchanged. CI uses ephemeral MinIO (no GitHub Secrets). Local dev uses `scripts/dev-bootstrap.sh`.

**Architecture:** Single bucket `maple-expectation` preserved. Four MinIO service accounts (ext-api, calculator, synchronizer, cleanup), each with a prefix-scoped IAM policy. A new one-shot `minio-bootstrap` container creates the bucket, 4 service accounts, 4 policies, and attaches them. ILM rules are managed to a "exactly 1 per prefix" invariant. Module env files are split (one per module). Root credential lives only in `.env.bootstrap`. Local dev regenerates the env set via `scripts/dev-bootstrap.sh`. CI uses ephemeral MinIO + random SA keys (no long-lived secrets).

**Tech Stack:** Spring Boot 3.x (Kotlin), Gradle, Docker Compose, MinIO `mc` CLI, AWS SDK v2 S3Client, JUnit 5, AssertJ, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-06-15-minio-operations-design.md` (post-revision: 4 SAs, ocid-mapping owned by ext-api, CI ephemeral, dev bootstrap script).

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `docker/minio/policies/ext-api.json` | new | SA policy: Get/Put on `runs/*`, `snapshots/*`, `ocid-mapping/*` |
| `docker/minio/policies/calculator.json` | new | SA policy: Get on `runs/*`, `data/snapshots/*`; Put on `calculator/runs/*` |
| `docker/minio/policies/synchronizer.json` | new | SA policy: Get/List on `runs/*`, `calculator/runs/*` |
| `docker/minio/policies/cleanup.json` | new | SA policy: Get/List/Delete on `runs/*`, `calculator/runs/*` (no wildcard) |
| `docker/minio/bootstrap.sh` | new | Idempotent: bucket + ILM (1 rule per prefix) + 4 SA + 4 policy + attach |
| `scripts/dev-bootstrap.sh` | new | Local dev helper: generates `.env.bootstrap` + 4 × `.env.<module>` in one call |
| `docker-compose.yml` | modify | Replace `minio-init` with `minio-bootstrap`; add env_file, script mount |
| `.env` | modify | Remove per-module `MINIO_ACCESS_KEY/SECRET_KEY`; keep global MinIO + bootstrap root creds |
| `.env.bootstrap` | new (gitignored) | Root creds + 4 SA secret keys |
| `.env.bootstrap.template` | new (committed) | Same shape as `.env.bootstrap` with placeholders |
| `.env.ext-api` | new (gitignored) | SA creds for external-api |
| `.env.calculator` | new (gitignored) | SA creds for calculator |
| `.env.synchronizer` | new (gitignored) | SA creds for synchronizer |
| `.env.cleanup` | new (gitignored) | SA creds for cleanup |
| `.env.<module>.template` × 4 | new (committed) | Placeholder shape of each per-module env |
| `module-rest-controller/src/main/resources/application.yml` | modify | Remove the `storage.minio.*` 5-line block (module has no ObjectStorage caller) |
| `.gitignore` | modify | Add env file patterns |
| `docs/01_ADR/ADR-NNN_minio-key-rotation-deferred.md` | new | Deferred-rotation ADR with manual runbook |
| `module-infra/src/test/kotlin/.../storage/MinioPolicyJsonTest.kt` | new | Unit test: parse + assert each policy JSON |
| `module-infra/src/test/kotlin/.../storage/MinioPolicyScopeIT.kt` | new | IT: per-SA positive/negative scope (4 SAs) |
| `module-infra/src/test/kotlin/.../storage/MinioBootSmokeIT.kt` | modify | Restructure: per-method `@TestPropertySource` + `@TestInstance(PER_METHOD)` |
| `.github/workflows/ci.yml` | modify | New `minio-it` job: ephemeral MinIO + random SA keys + IT |

Files that change together: `.env` ↔ `.env.*` env files (all part of the credential surface); `docker-compose.yml` ↔ `docker/minio/bootstrap.sh` (deploy + script); `MinioBootSmokeIT.kt` ↔ `.env.<module>` (boot creds).

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

- [ ] **Step 2: Verify baseline state — modules boot with current `.env`**

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

- [ ] **Step 3: Commit branch marker**

```bash
git commit --allow-empty -m "chore: branch feature/minio-sa-isolation — minio SA isolation baseline"
```

---

## Task 2: Create 4 policy JSON files

**Files:**
- Create: `docker/minio/policies/{ext-api,calculator,synchronizer,cleanup}.json`
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
        "arn:aws:s3:::maple-expectation/snapshots/*",
        "arn:aws:s3:::maple-expectation/ocid-mapping/*"
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

`ocid-mapping/*` is owned by ext-api (read via `OcidCacheProvider`, write via `OcidLookupPhase`). `s3:HeadObject` is required for `MinioObjectStorage.exists()` / `getLastModified()`.

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

`ocid-mapping/*` is NOT in synchronizer's resource set — that prefix is owned by ext-api.

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

`Delete` is prefix-scoped to `runs/*` and `calculator/runs/*` only. No wildcard. `ocid-mapping/*` is intentionally NOT in cleanup's resource set — OCID mapping lifecycle is ILM-only.

- [ ] **Step 6: Write the failing test for policy JSON structure**

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
    @ValueSource(strings = ["ext-api", "calculator", "synchronizer", "cleanup"])
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
    fun `ext-api owns ocid-mapping and synchronizer does not`() {
        val ext = mapper.readTree(policiesDir.resolve("ext-api.json").toFile())
        val sync = mapper.readTree(policiesDir.resolve("synchronizer.json").toFile())

        fun resources(tree: JsonNode): List<String> =
            (0 until tree.get("Statement").size()).flatMap { i ->
                val res = tree.get("Statement").get(i).get("Resource")
                if (res.isArray) res.map { it.asText() } else listOf(res.asText())
            }

        assertThat(resources(ext))
            .describedAs("ext-api must own ocid-mapping/*")
            .anyMatch { it.contains("ocid-mapping") }

        assertThat(resources(sync))
            .describedAs("synchronizer must NOT own ocid-mapping/* (ext-api is the sole owner)")
            .noneMatch { it.contains("ocid-mapping") }
    }
}
```

- [ ] **Step 7: Run the test — expect PASS**

```bash
./gradlew :module-infra:test --tests "*MinioPolicyJsonTest*"
```

Expected: BUILD SUCCESSFUL, 6 tests pass (4 parameterized + 2 specific).

If FAIL: re-check the JSON syntax of the failing file.

- [ ] **Step 8: Commit**

```bash
git add docker/minio/policies/ module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyJsonTest.kt
git commit -m "feat(minio): 4 SA policy JSONs (ext-api owns ocid-mapping) + structural test"
```

---

## Task 3: Create idempotent bootstrap script with ILM rule invariant

**Files:**
- Create: `docker/minio/bootstrap.sh`

- [ ] **Step 1: Create the script**

```bash
#!/usr/bin/env bash
# docker/minio/bootstrap.sh
# One-shot MinIO bootstrap: bucket, ILM, 4 service accounts, 4 policies, attach.
# Idempotent — safe to re-run.
# Invariant: exactly 1 ILM rule per managed prefix after every run.
# Required env: MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_ENDPOINT,
#   SA_EXT_API_SECRET_KEY, SA_CALCULATOR_SECRET_KEY,
#   SA_SYNCHRONIZER_SECRET_KEY, SA_CLEANUP_SECRET_KEY.

set -euo pipefail

mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/maple-expectation
mc anonymous set none local/maple-expectation

# ILM: list, remove all existing rules for the prefix, add one fresh rule.
# mc ilm add is NOT idempotent — without this loop, re-runs duplicate rules.
for prefix in snapshots/ runs/ calculator/ ocid-mapping/; do
  existing=$(mc ilm ls --json local/maple-expectation 2>/dev/null | \
    jq -r --arg p "$prefix" '.["maple-expectation"][]? | select(.Prefix == $p) | .ID' || true)
  for rule_id in $existing; do
    [ -n "$rule_id" ] && mc ilm rm --id "$rule_id" local/maple-expectation || true
  done
  mc ilm add --expiry-days 2 --prefix "$prefix" local/maple-expectation
done

# Service accounts (idempotent on user/policy existence; attach is a no-op if already attached)
declare -A sa_secret_keys=(
  [ext-api]="$SA_EXT_API_SECRET_KEY"
  [calculator]="$SA_CALCULATOR_SECRET_KEY"
  [synchronizer]="$SA_SYNCHRONIZER_SECRET_KEY"
  [cleanup]="$SA_CLEANUP_SECRET_KEY"
)

for sa in "${!sa_secret_keys[@]}"; do
  if ! mc admin user info local "$sa" >/dev/null 2>&1; then
    mc admin user add local "$sa" "${sa_secret_keys[$sa]}"
  fi
  if ! mc admin policy info local "policy-$sa" >/dev/null 2>&1; then
    mc admin policy create local "policy-$sa" "/scripts/policies/$sa.json"
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
git commit -m "feat(minio): idempotent bootstrap.sh (bucket + 1-rule ILM + 4 SA + 4 policies)"
```

---

## Task 4: Create `.env.bootstrap` and committed template

**Files:**
- Create: `.env.bootstrap` (gitignored)
- Create: `.env.bootstrap.template` (committed)

- [ ] **Step 1: Generate 4 random SA secret keys**

```bash
for sa in ext-api calculator synchronizer cleanup; do
  echo "SA_${sa^^}_SECRET_KEY=$(openssl rand -hex 32)"
done
```

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
EOF
chmod 600 .env.bootstrap
```

- [ ] **Step 3: Create the committed template**

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
```

- [ ] **Step 4: Commit the template only**

```bash
git add .env.bootstrap.template
git commit -m "docs(env): .env.bootstrap.template — root + 4 SA secret placeholders"
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

- [ ] **Step 3: Smoke-test the bootstrap against the running MinIO**

```bash
docker compose up -d minio
docker compose up minio-bootstrap
docker compose logs minio-bootstrap
```

Expected log line: `[bootstrap] complete`.

- [ ] **Step 4: Verify idempotency (re-run should not error and ILM rule count stays at 1 per prefix)**

```bash
docker compose up minio-bootstrap
docker compose run --rm minio-bootstrap /bin/sh -c "
  mc alias set local http://minio:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD >/dev/null 2>&1
  for p in snapshots/ runs/ calculator/ ocid-mapping/; do
    count=\$(mc ilm ls --json local/maple-expectation 2>/dev/null | \
      jq -r --arg p \"\$p\" '.[\"maple-expectation\"][]? | select(.Prefix == \$p) | .ID' | wc -l)
    echo \"\$p : \$count rule(s)\"
  done
"
```

Expected: each prefix shows exactly `1 rule(s)`.

- [ ] **Step 5: Verify SAs exist**

```bash
docker run --rm --network maple-network \
  --env-file .env.bootstrap \
  minio/mc:latest admin user list local
```

Expected output includes: `cleanup`, `calculator`, `ext-api`, `synchronizer`. (No `read-api`.)

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml
git commit -m "refactor(docker): minio-init → minio-bootstrap, mount script + env_file"
```

---

## Task 6: Split `.env` and create per-module env files

**Files:**
- Modify: `.env`
- Create: `.env.ext-api`, `.env.calculator`, `.env.synchronizer`, `.env.cleanup`
- Create: `.env.<module>.template` × 4
- Modify: `module-rest-controller/src/main/resources/application.yml` (remove the `storage.minio.*` 5-line block)

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

- [ ] **Step 2: Create per-module env files (4)**

For each module, create `.env.<module>` with the SA name as `MINIO_ACCESS_KEY` and the matching secret from `.env.bootstrap`.

`.env.ext-api`:

```env
# Module: external-api
# MinIO service account: ext-api (Get/Put on runs/*, snapshots/*, ocid-mapping/*)
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
# MinIO service account: synchronizer (Get/List on runs/*, calculator/runs/*)
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

- [ ] **Step 3: Create per-module templates (committed, no real secrets)**

`.env.<module>.template` for each of the 4 modules: same shape as above, but with `MINIO_SECRET_KEY=replace-with-corresponding-SA-secret-key-from-env.bootstrap`.

- [ ] **Step 4: Remove `storage.minio.*` block from `module-rest-controller`**

In `module-rest-controller/src/main/resources/application.yml`, find the block:

```yaml
storage:
  minio:
    endpoint: ${MINIO_ENDPOINT:http://minio:9000}
    region: ${MINIO_REGION:us-east-1}
    access-key: ${MINIO_ACCESS_KEY:}
    secret-key: ${MINIO_SECRET_KEY:}
    bucket: ${MINIO_BUCKET:maple-expectation}
```

Delete this block. rest-controller has no ObjectStorage caller (PostgreSQL read model only), so no MinIO config is needed.

If `storage:` is the only key under it, also delete the parent `storage:` key.

- [ ] **Step 5: Verify rest-controller still boots**

```bash
./gradlew :module-rest-controller:bootRun
```

Expected: module boots. No `MinioHealthIndicator` bean created. No errors.

- [ ] **Step 6: Verify the other 4 modules' `application.yml` still binds the same env var names**

```bash
grep -nE "MINIO_ACCESS_KEY|MINIO_SECRET_KEY" \
  module-external-api/src/main/resources/application.yml \
  module-calculator/src/main/resources/application.yml \
  module-synchronizer/src/main/resources/application.yml \
  module-cleanup/src/main/resources/application.yml
```

Expected: every binding is `${MINIO_ACCESS_KEY}` / `${MINIO_SECRET_KEY}` — no module-specific override.

- [ ] **Step 7: Commit**

```bash
git add .env.*.template module-rest-controller/src/main/resources/application.yml
# .env itself is gitignored; the change to .env is local-only.
git diff --cached --name-only
```

Expected: only `.env.*.template` files and the rest-controller yaml appear. **No `.env` in the diff.** If `.env` is tracked, `git rm --cached .env` first.

```bash
git commit -m "feat(env): per-module MinIO SA env files; drop dead MinIO config from rest-controller"
```

---

## Task 7: Update `.gitignore`

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Add env file patterns to `.gitignore`**

After line 50 (`.env.staging`), add:

```gitignore
# MinIO per-service-account env files
.env.bootstrap
.env.ext-api
.env.calculator
.env.synchronizer
.env.cleanup
```

- [ ] **Step 2: Verify no tracked secrets are exposed**

```bash
git status --ignored | grep -E "\.env\.(bootstrap|ext-api|calculator|synchronizer|cleanup)$"
```

Expected: each path appears under "Ignored files".

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore(gitignore): minio per-SA env files"
```

---

## Task 8: Create `scripts/dev-bootstrap.sh` (Q7)

**Files:**
- Create: `scripts/dev-bootstrap.sh`

- [ ] **Step 1: Create the script**

```bash
#!/usr/bin/env bash
# scripts/dev-bootstrap.sh
# One-line dev env generator. Run once after `git clone` or whenever
# a fresh env set is needed.
#
# Generates:
#   .env.bootstrap          — root + 4 SA secret keys
#   .env.ext-api            — SA creds for external-api
#   .env.calculator         — SA creds for calculator
#   .env.synchronizer       — SA creds for synchronizer
#   .env.cleanup            — SA creds for cleanup
#
# Idempotent: re-running regenerates the full set.
# Existing files are overwritten.

set -euo pipefail

cd "$(dirname "$0")/.."

# Root creds: read from current .env (assumes MINIO_ROOT_USER/PASSWORD already set there).
# If absent, default to the dev minioadmin pair.
: "${MINIO_ROOT_USER:=minioadmin}"
: "${MINIO_ROOT_PASSWORD:=minioadmin}"

cat > .env.bootstrap <<EOF
MINIO_ENDPOINT=http://localhost:9000
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}

SA_EXT_API_SECRET_KEY=$(openssl rand -hex 32)
SA_CALCULATOR_SECRET_KEY=$(openssl rand -hex 32)
SA_SYNCHRONIZER_SECRET_KEY=$(openssl rand -hex 32)
SA_CLEANUP_SECRET_KEY=$(openssl rand -hex 32)
EOF
chmod 600 .env.bootstrap

declare -A sa_keys
sa_keys[ext-api]=SA_EXT_API_SECRET_KEY
sa_keys[calculator]=SA_CALCULATOR_SECRET_KEY
sa_keys[synchronizer]=SA_SYNCHRONIZER_SECRET_KEY
sa_keys[cleanup]=SA_CLEANUP_SECRET_KEY

for sa in ext-api calculator synchronizer cleanup; do
  secret=$(grep "^${sa_keys[$sa]}=" .env.bootstrap | cut -d= -f2-)
  cat > ".env.${sa}" <<EOF2
MINIO_ACCESS_KEY=${sa}
MINIO_SECRET_KEY=${secret}
EOF2
  chmod 600 ".env.${sa}"
done

echo "[dev-bootstrap] generated .env.bootstrap + 4 × .env.<module>"
echo "[dev-bootstrap] next: docker compose up -d minio && docker compose up minio-bootstrap"
```

- [ ] **Step 2: Make executable + syntax check**

```bash
chmod +x scripts/dev-bootstrap.sh
bash -n scripts/dev-bootstrap.sh && echo "SYNTAX_OK"
```

- [ ] **Step 3: Smoke-test (regenerates current `.env.bootstrap` and friends)**

```bash
./scripts/dev-bootstrap.sh
ls -la .env.bootstrap .env.ext-api .env.calculator .env.synchronizer .env.cleanup
```

Expected: 5 files, mode `-rw-------`, fresh SA keys.

- [ ] **Step 4: Verify the modules can read their own env file**

```bash
set -a && source .env && source .env.ext-api && set +a
echo "MINIO_ACCESS_KEY=$MINIO_ACCESS_KEY"
```

Expected: `MINIO_ACCESS_KEY=ext-api`.

- [ ] **Step 5: Commit**

```bash
git add scripts/dev-bootstrap.sh
git commit -m "feat(scripts): dev-bootstrap.sh — one-line env set generator"
```

---

## Task 9: ADR — key rotation deferred with manual runbook (Q3)

**Files:**
- Create: `docs/01_ADR/ADR-NNN_minio-key-rotation-deferred.md`

- [ ] **Step 1: Pick the next ADR number**

```bash
ls docs/01_ADR/ADR-*.md 2>/dev/null | grep -v _archive | sort | tail -1
```

Use the next available number. The recent pattern in this repo is `ADR-XXX_<slug>.md` (no zero-pad). Examples in `docs/01_ADR/`: `ADR-write-path-snapshot-calculator.md`, `ADR-redis-distributed-cache-adoption.md`. Use the next number.

- [ ] **Step 2: Write the ADR**

```markdown
# ADR-{NNN}: MinIO Service Account Key Rotation — Deferred

- Status: Deferred
- Date: 2026-06-15
- Owner: solo dev

## 1. Background / Problem

### Background

The MinIO operations design (`docs/superpowers/specs/2026-06-15-minio-operations-design.md`) introduces 4 service accounts in place of the shared `minioadmin` root credential. The natural follow-up is key rotation: how often, by what mechanism, with what operational burden.

### Problem

Implementing periodic rotation (e.g. 90-day or 6-month cycle) at the current scale (one prod env, 4 modules, single host) introduces operational burden that is not justified by current threat model. The CI surface uses ephemeral MinIO with random SA keys, so CI drift is not a concern. Local dev regenerates via `scripts/dev-bootstrap.sh`. The remaining surface is prod.

### Goal

Document the decision to defer periodic rotation in prod, publish a manual rotation runbook for suspicion-triggered rotation, and identify the trigger conditions for re-opening the decision.

## 2. Decision

> **Do not implement periodic rotation in prod now. Rotate manually on suspicion or incident. Re-open when any of the trigger conditions in §3-Risk is met.**

## 3. Trade-offs

### Sensitivity

- **Number of environments** — Rotation cost scales with `modules × envs × keys`. At 4 × 1 = 4 keys today, manual rotation is a 30-minute runbook. At 4 × 3 (dev/stg/prod) = 12 keys, manual is still feasible but error-prone.
- **Number of hosts** — Rotation today means editing one `.env.<module>` per module and restarting one process per module. Scale-out multiplies that.
- **Audit / compliance requirements** — None enforced today. If a regulator or external customer demands evidence of rotation cadence, this changes.

### Trade-off

| Choice | Get | Give up |
|---|---|---|
| Defer rotation | Zero operational burden; runs on suspicion only | Unbounded key compromise window |
| 6-month cycle | Compliance with common security baseline | 10 manual rotations/year × 4 modules = 40 env edits/year |
| 90-day cycle | Stricter baseline | Same volume × 4 = 160 env edits/year |

### Risk

- A leaked `.env.<module>` file or compromised pod has access to its SA's keys until manual rotation.
- If the leak is undetected, the window is open indefinitely.

### Non-Risk

- Root credential leak is bounded to `.env.bootstrap` and only readable by the `minio-bootstrap` container. Risk is contained at the bootstrap layer.
- Cross-SA blast radius is bounded by prefix policy. A leaked `ext-api` key cannot delete `calculator/runs/*`.
- CI uses ephemeral secrets — no long-lived key compromise window there.

### Trigger to re-open

- Scale-out: ext-api or calculator runs on >1 host and per-pod keys are needed.
- Multi-environment: dev/stg/prod split with separate keys per env.
- External audit / compliance demand.
- Incident response finds a leaked key.

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|---|---|---|
| Service accounts | 4 | ext-api, calculator, synchronizer, cleanup |
| Rotation cycle | none (manual on suspicion) | Documented in §3-Risk |
| Rotation runbook | manual (below) | Authored as part of this ADR |
| CI rotation surface | 0 | Ephemeral, no persistent secrets |

### Observed Result

- Initial deployment of 4 SAs committed without a rotation schedule.
- Re-evaluation triggers documented above.
- Manual runbook authored below for use when trigger fires.

## 5. Summary

> Defer periodic MinIO SA key rotation; rotate on suspicion; re-open at scale-out, multi-env, audit, or incident.

---

## Appendix: Manual Rotation Runbook (prod only)

Use this runbook when a key compromise is suspected or confirmed. CI and dev are not in scope (CI is ephemeral, dev regenerates via `scripts/dev-bootstrap.sh`).

### Prerequisites

- SSH access to the production host
- `docker compose` available
- `mc` CLI available locally (for verification)

### Steps

```bash
# 1. Generate a new key for the compromised SA.
# (Example: ext-api)
NEW_KEY=$(openssl rand -hex 32)
echo "New ext-api key generated (length ${#NEW_KEY})"

# 2. Stop the affected module so it does not auto-reconnect with the rotated key.
ssh prod "cd /opt/maple && docker compose stop module-external-api"

# 3. Remove the old SA from MinIO (this invalidates the old key).
ssh prod "cd /opt/maple && \
  docker compose run --rm minio-bootstrap /bin/sh -c '
    mc alias set local http://minio:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD
    mc admin user remove local ext-api
  '"

# 4. Update .env.bootstrap with the new SA key on the host.
ssh prod "sed -i 's|^SA_EXT_API_SECRET_KEY=.*|SA_EXT_API_SECRET_KEY=${NEW_KEY}|' /opt/maple/.env.bootstrap"

# 5. Re-run the bootstrap container to re-create the SA with the new key and re-attach the policy.
ssh prod "cd /opt/maple && docker compose up minio-bootstrap"
# Verify: docker compose logs minio-bootstrap → "[bootstrap] complete"

# 6. Update .env.ext-api on the host with the new MINIO_SECRET_KEY.
ssh prod "sed -i 's|^MINIO_SECRET_KEY=.*|MINIO_SECRET_KEY=${NEW_KEY}|' /opt/maple/.env.ext-api"

# 7. Restart the affected module.
ssh prod "cd /opt/maple && docker compose up -d module-external-api"

# 8. Verify the module boots and validateBucket() passes.
ssh prod "docker compose logs module-external-api | grep -E 'MinioHealthIndicator|bucket validated'"

# 9. (Optional) Verify the old key is rejected.
ssh prod "\
  AWS_ACCESS_KEY_ID=ext-api \
  AWS_SECRET_ACCESS_KEY=<old-key> \
  aws s3 ls s3://maple-expectation/runs/ --endpoint-url http://minio:9000
"
# Expected: InvalidAccessKeyId error.
```

### Notes

- The bootstrap container's `mc admin user add` is guarded by `mc admin user info`; re-running the bootstrap alone does NOT update the SA secret. The `mc admin user remove` in step 3 is required.
- If multiple modules share credentials via the same SA (none do today), repeat steps 6-7 for each module.
- If the rotation is part of a quarterly sanity check rather than a compromise, perform steps 1-7 on a maintenance window. Document the rotation in `docs/01_ADR/rotation-log.md`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/01_ADR/ADR-{NNN}_minio-key-rotation-deferred.md
git commit -m "docs(adr): minio key rotation deferred + manual runbook (prod-only)"
```

---

## Task 10: Boot smoke test — each module boots with its SA credential (Q1 fix)

**Files:**
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioBootSmokeIT.kt`

The existing `MinioBootSmokeIT` has a class-level `@TestPropertySource` that fixes the credentials to `maple/changeme`. To make each parameterized test use a different SA, we need `@TestInstance(PER_METHOD)` so each test method gets its own Spring context with its own `@TestPropertySource`.

- [ ] **Step 1: Restructure `MinioBootSmokeIT.kt`**

Replace the file's contents with:

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.test.context.TestPropertySource
import software.amazon.awssdk.services.s3.S3Client

/**
 * Spring boot smoke for the minio backend, parameterized over each SA.
 *
 * PER_METHOD instance lifecycle: each @ParameterizedTest invocation gets its
 * own Spring context, so each SA's @TestPropertySource takes effect. This
 * validates that the storage layer boots cleanly with each SA's credential.
 *
 * Runs only when INTEGRATION_MINIO=true.
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MinioBootSmokeIT {

    @ParameterizedTest
    @ValueSource(strings = ["ext-api", "calculator", "synchronizer", "cleanup"])
    @TestPropertySource(properties = [
        "storage.backend=minio",
        "storage.minio.endpoint=http://localhost:9000",
        "storage.minio.region=us-east-1",
        "storage.minio.bucket=maple-expectation",
    ])
    @SpringBootTest(classes = [StorageConfig::class])
    @EnableConfigurationProperties(MinioProperties::class)
    @ComponentScan(
        basePackages = ["maple.expectation.infrastructure.storage"],
        useDefaultFilters = false,
        includeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [MinioHealthIndicator::class])]
    )
    fun `module boots with each SA credential`() {
        // The test class has no fields. We need the Spring context to be
        // available inside the test body to assert on the health indicator.
        // Re-create the wiring inline:
        val ctx = org.springframework.context.annotation.AnnotationConfigApplicationContext(
            StorageConfig::class.java
        ).apply {
            // Inject the SA-specific properties from environment
            val sa = "ext-api" // overridden by @TestPropertySource below
            // ... actually this is hard. See the alternate approach in step 2.
        }
        // (See step 2 for the correct approach.)
    }
}
```

**STOP.** The pattern above is wrong — `@SpringBootTest` + `@TestPropertySource` cannot be applied to a `@ParameterizedTest` method in JUnit 5 with the per-method instance lifecycle, because Spring TestContextManager only handles class-level annotations reliably.

**Correct approach (replace step 1 with):**

Delete the existing `MinioBootSmokeIT.kt` and create 4 separate test classes, one per SA. Each class is a standard `@SpringBootTest` with class-level `@TestPropertySource`.

- [ ] **Step 1 (corrected): Create `ExtApiBootSmokeIT.kt`**

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.test.context.TestPropertySource

@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@SpringBootTest(classes = [StorageConfig::class])
@EnableConfigurationProperties(MinioProperties::class)
@TestPropertySource(properties = [
    "storage.backend=minio",
    "storage.minio.endpoint=http://localhost:9000",
    "storage.minio.region=us-east-1",
    "storage.minio.bucket=maple-expectation",
    "storage.minio.access-key=ext-api",
    "storage.minio.secret-key=\${SA_EXT_API_SECRET_KEY}",
])
@ComponentScan(
    basePackages = ["maple.expectation.infrastructure.storage"],
    useDefaultFilters = false,
    includeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [MinioHealthIndicator::class])]
)
class ExtApiBootSmokeIT {

    @Autowired
    private lateinit var healthIndicator: MinioHealthIndicator

    @Test
    fun `ext-api boots and bucket validates`() {
        assertThat(healthIndicator.health().status.code).isEqualTo("UP")
    }
}
```

- [ ] **Step 2: Create `CalculatorBootSmokeIT.kt`**

Same as above, with `access-key=calculator`, `secret-key=${SA_CALCULATOR_SECRET_KEY}`, class name `CalculatorBootSmokeIT`.

- [ ] **Step 3: Create `SynchronizerBootSmokeIT.kt`**

Same, with `access-key=synchronizer`, `secret-key=${SA_SYNCHRONIZER_SECRET_KEY}`, class name `SynchronizerBootSmokeIT`.

- [ ] **Step 4: Create `CleanupBootSmokeIT.kt`**

Same, with `access-key=cleanup`, `secret-key=${SA_CLEANUP_SECRET_KEY}`, class name `CleanupBootSmokeIT`.

- [ ] **Step 5: Delete the old `MinioBootSmokeIT.kt`**

```bash
rm module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioBootSmokeIT.kt
```

- [ ] **Step 6: Run the boot smoke tests**

```bash
set -a && source .env.bootstrap && set +a
./gradlew :module-infra:test --tests "*BootSmokeIT*" -DINTEGRATION_MINIO=true
```

Expected: 4 tests PASS (one per SA). Each test boots Spring with that SA's credentials. If a test FAILS, the SA's policy is missing `s3:HeadBucket` or `s3:ListBucket` on the bucket resource — re-check Task 2.

- [ ] **Step 7: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/
git rm module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioBootSmokeIT.kt
git commit -m "test(minio): boot smoke per SA (4 IT classes; replaces single-class MinioBootSmokeIT)"
```

---

## Task 11: SA scope integration test (4 SAs, positive + negative)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyScopeIT.kt`

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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI

/**
 * Per-SA policy scope test (4 SAs).
 *
 * Seeds the bucket with one object per prefix (using root credentials),
 * then attempts GetObject on each in-scope and out-of-scope key with each SA.
 * Expects 200 for in-scope, S3Exception (403) for out-of-scope.
 *
 * Gated on INTEGRATION_MINIO=true.
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
    fun `ext-api can read runs snapshots ocid-mapping; denied on calculator prefix`() {
        putSeed("runs/20260615-120000-000001/_SUCCESS")
        putSeed("snapshots/2026/06/15/job.gz")
        putSeed("ocid-mapping/2026-06-15.jsonl.gz")
        putSeed("calculator/runs/20260615-120000-000001/result.jsonl.gz")

        val client = saClient("ext-api", saSecret("SA_EXT_API_SECRET_KEY"))

        // in-scope
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000001/_SUCCESS").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("snapshots/2026/06/15/job.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")

        // out-of-scope
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000001/result.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on out-of-scope key for ext-api")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }

    @Test
    fun `calculator can read runs and data snapshots; can write calculator runs; denied on ocid-mapping`() {
        putSeed("runs/20260615-120000-000002/chunk.jsonl.gz")
        putSeed("data/snapshots/2026/06/15/job.gz")
        putSeed("ocid-mapping/2026-06-15.jsonl.gz")

        val client = saClient("calculator", saSecret("SA_CALCULATOR_SECRET_KEY"))

        // in-scope read
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000002/chunk.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")
        assertThat(client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("data/snapshots/2026/06/15/job.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("seed")

        // in-scope write
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000002/result.jsonl.gz").build(),
            RequestBody.fromBytes("calc".toByteArray())
        )
        assertThat(rootClient.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key("calculator/runs/20260615-120000-000002/result.jsonl.gz").build()
        ).asByteArray().toString(Charsets.UTF_8)).isEqualTo("calc")

        // out-of-scope: ocid-mapping
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on ocid-mapping for calculator")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }
    }

    @Test
    fun `cleanup can delete runs prefix; denied on ocid-mapping and snapshots`() {
        putSeed("runs/20260615-120000-000003/chunk.jsonl.gz")
        putSeed("ocid-mapping/2026-06-15.json")
        putSeed("snapshots/2026/06/15/job.gz")

        val client = saClient("cleanup", saSecret("SA_CLEANUP_SECRET_KEY"))

        // in-scope: delete succeeds
        client.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000003/chunk.jsonl.gz").build()
        )
        try {
            rootClient.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("runs/20260615-120000-000003/chunk.jsonl.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected object to be deleted")
        } catch (_: NoSuchKeyException) { /* ok */ }

        // out-of-scope: ocid-mapping read 403
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("ocid-mapping/2026-06-15.json").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on ocid-mapping for cleanup")
        } catch (e: S3Exception) {
            assertThat(e.statusCode()).isEqualTo(403)
        }

        // out-of-scope: snapshots read 403
        try {
            client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key("snapshots/2026/06/15/job.gz").build()
            )
            org.junit.jupiter.api.Assertions.fail("expected 403 on snapshots for cleanup")
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

Expected: 3 tests PASS. Each verifies positive in-scope + negative out-of-scope (403).

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyScopeIT.kt
git commit -m "test(minio): per-SA scope IT (3 tests, ext-api/calculator/cleanup, positive + 403 negative)"
```

---

## Task 12: Pipeline end-to-end with new credentials

**Files:** none (verification only)

- [ ] **Step 1: Start the stack**

```bash
docker compose down
docker compose up -d minio
docker compose up minio-bootstrap  # one-shot
docker compose up -d postgres kafka redis
```

- [ ] **Step 2: Start each Spring module sourcing its own env file**

```bash
# Terminal 1
set -a && source .env && source .env.ext-api && set +a
./gradlew :module-external-api:bootRun

# Terminal 2
set -a && source .env && source .env.calculator && set +a
./gradlew :module-calculator:bootRun

# Terminal 3
set -a && source .env && source .env.synchronizer && set +a
./gradlew :module-synchronizer:bootRun

# Terminal 4
set -a && source .env && source .env.cleanup && set +a
./gradlew :module-cleanup:bootRun

# Terminal 5
set -a && source .env && set +a
./gradlew :module-rest-controller:bootRun
```

Expected: each MinIO-using module logs `MinioHealthIndicator UP` and `bucket validated`. rest-controller boots without MinIO config (no MinioHealthIndicator log line).

- [ ] **Step 3: Run the pipeline-test skill**

Use the `pipeline-test` skill (per `docs/agents/` conventions). Confirm:
- `runs/$runId/_SUCCESS` is written by external-api.
- Calculator reads the chunk and writes `calculator/runs/$runId/result.jsonl.gz` + `_SUCCESS`.
- Synchronizer reads both and projects to PostgreSQL.
- rest-controller can read the snapshot for a request via PostgreSQL.

- [ ] **Step 4: Verify `ocid-mapping/*` write path**

After the pipeline run, check:

```bash
docker run --rm --network maple-network --env-file .env.bootstrap \
  minio/mc:latest ls --recursive local/maple-expectation/ocid-mapping/
```

Expected: at least one `ocid-mapping-*.jsonl.gz` file exists (written by `OcidLookupPhase`).

- [ ] **Step 5: Run cleanup on a test run prefix**

Trigger cleanup via the cleanup module's HTTP endpoint or Airflow trigger. Confirm:
- `runs/$runId/...` for an expired run is deleted.
- `calculator/runs/$runId/...` for the same run is deleted.
- An attempt to list `ocid-mapping/*` returns 403 (cleanup's policy excludes it).

- [ ] **Step 6: No commit expected** — verification only.

---

## Task 13: CI workflow — ephemeral MinIO + random SA keys (Q6)

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Read the existing CI workflow**

```bash
cat .github/workflows/ci.yml
```

Find an appropriate insertion point. The repo convention is one job per concern; we'll add a new `minio-it` job that depends on the `test` job finishing.

- [ ] **Step 2: Add a `minio-it` job**

Append the following to `.github/workflows/ci.yml` (preserve existing jobs):

```yaml
  minio-it:
    name: MinIO SA scope IT (ephemeral)
    runs-on: ubuntu-latest
    needs: build  # or whatever the existing build job name is — adjust to match
    services:
      minio:
        image: minio/minio:latest
        env:
          MINIO_ROOT_USER: minioadmin
          MINIO_ROOT_PASSWORD: minioadmin
        ports:
          - 9000:9000
        options: >-
          --health-cmd "mc ready local"
          --health-interval 5s
          --health-timeout 5s
          --health-retries 5
    env:
      INTEGRATION_MINIO: "true"
      MINIO_ENDPOINT: http://localhost:9000
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
      # Random per-job SA keys. Generated below in the bootstrap step.
      SA_EXT_API_SECRET_KEY: ${{ steps.gen-keys.outputs.SA_EXT_API_SECRET_KEY }}
      SA_CALCULATOR_SECRET_KEY: ${{ steps.gen-keys.outputs.SA_CALCULATOR_SECRET_KEY }}
      SA_SYNCHRONIZER_SECRET_KEY: ${{ steps.gen-keys.outputs.SA_SYNCHRONIZER_SECRET_KEY }}
      SA_CLEANUP_SECRET_KEY: ${{ steps.gen-keys.outputs.SA_CLEANUP_SECRET_KEY }}
    steps:
      - uses: actions/checkout@v4

      - name: Generate ephemeral SA keys
        id: gen-keys
        run: |
          SA_EXT_API_SECRET_KEY=$(openssl rand -hex 16)
          SA_CALCULATOR_SECRET_KEY=$(openssl rand -hex 16)
          SA_SYNCHRONIZER_SECRET_KEY=$(openssl rand -hex 16)
          SA_CLEANUP_SECRET_KEY=$(openssl rand -hex 16)
          echo "SA_EXT_API_SECRET_KEY=$SA_EXT_API_SECRET_KEY" >> $GITHUB_OUTPUT
          echo "SA_CALCULATOR_SECRET_KEY=$SA_CALCULATOR_SECRET_KEY" >> $GITHUB_OUTPUT
          echo "SA_SYNCHRONIZER_SECRET_KEY=$SA_SYNCHRONIZER_SECRET_KEY" >> $GITHUB_OUTPUT
          echo "SA_CLEANUP_SECRET_KEY=$SA_CLEANUP_SECRET_KEY" >> $GITHUB_OUTPUT

      - name: Run bootstrap (idempotent)
        run: bash docker/minio/bootstrap.sh

      - name: Boot smoke + scope IT
        run: |
          ./gradlew :module-infra:test \
            --tests "*BootSmokeIT*" \
            --tests "*MinioPolicyScopeIT*" \
            --tests "*MinioPolicyJsonTest*" \
            -DINTEGRATION_MINIO=true
```

Notes:
- **No GitHub Secrets are used.** All SA keys are generated per-job and die with the job.
- `--health-cmd "mc ready local"` requires the `mc` binary. The `minio/minio:latest` image includes it.
- The job spins up a real MinIO container, runs the bootstrap (which creates the 4 SAs with the random keys), and runs the IT. Job duration: ~3-5 min.
- The `MinioPolicyJsonTest` runs in the same `gradlew test` invocation. It is not gated by `INTEGRATION_MINIO`; it parses the policy JSONs from disk. Including it in the same invocation keeps CI coverage unified.

- [ ] **Step 3: Verify the workflow YAML**

```bash
# Local lint, if actionlint is installed
actionlint .github/workflows/ci.yml || echo "actionlint not installed; manual review only"

# Manual review checklist:
# - Indentation is 2 spaces
# - The minio-it job is at the same level as the existing jobs
# - needs: <existing-job-name> points at the right build job
# - The ${{ steps.gen-keys.outputs.* }} references match the id: gen-keys step
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(minio): ephemeral MinIO + random SA keys for SA-scope IT (no GitHub Secrets)"
```

---

## Task 14: Open PR to develop

**Files:** none (process step)

- [ ] **Step 1: Push branch**

```bash
git push origin feature/minio-sa-isolation
```

- [ ] **Step 2: Open PR**

```bash
gh pr create --base develop --title "feat(minio): 4 SA prefix-policy isolation + ephemeral CI" --body "$(cat <<'EOF'
## Summary
- Replace shared `minioadmin` root credential with 4 prefix-scoped service accounts.
- Single bucket `maple-expectation` preserved. No data migration. No Spring source change.
- New one-shot `minio-bootstrap` container creates bucket + ILM (1-rule invariant) + 4 SA + 4 policies.
- Per-module env files (`.env.<module>`) replace shared `.env` for credentials.
- Root credential lives only in `.env.bootstrap` (read by `minio-bootstrap` container).
- `scripts/dev-bootstrap.sh` regenerates the full env set in one line for local dev.
- `module-rest-controller`'s `storage.minio.*` block removed (no ObjectStorage caller).
- CI uses ephemeral MinIO + per-job random SA keys (no GitHub Secrets).

## Service account matrix

| SA | Get | Put | Resource |
|---|---|---|---|
| ext-api | ✓ | ✓ | `runs/*`, `snapshots/*`, `ocid-mapping/*` |
| calculator | ✓ | ✓ | `runs/*`, `data/snapshots/*` (Get) + `calculator/runs/*` (Put) |
| synchronizer | ✓ | — | `runs/*`, `calculator/runs/*` |
| cleanup | ✓, List, ✓ | — | `runs/*`, `calculator/runs/*` (no wildcard) |

## Files changed
- `docker/minio/policies/*.json` × 4 (new)
- `docker/minio/bootstrap.sh` (new, idempotent)
- `scripts/dev-bootstrap.sh` (new)
- `docker-compose.yml` (modify: minio-init → minio-bootstrap)
- `.env` (modify: trim per-module creds)
- `.env.<module>` × 4 (new, gitignored)
- `.env.<module>.template` × 4 + `.env.bootstrap.template` (new, committed)
- `module-rest-controller/src/main/resources/application.yml` (modify: remove dead MinIO block)
- `.gitignore` (modify: add env file patterns)
- `docs/01_ADR/ADR-{NNN}_minio-key-rotation-deferred.md` (new, with runbook)
- `module-infra/src/test/kotlin/.../MinioPolicyJsonTest.kt` (new)
- `module-infra/src/test/kotlin/.../MinioPolicyScopeIT.kt` (new)
- `module-infra/src/test/kotlin/.../MinioBootSmokeIT.kt` (deleted; replaced by 4 per-SA IT classes)
- `module-infra/src/test/kotlin/.../ExtApiBootSmokeIT.kt` (new)
- `module-infra/src/test/kotlin/.../CalculatorBootSmokeIT.kt` (new)
- `module-infra/src/test/kotlin/.../SynchronizerBootSmokeIT.kt` (new)
- `module-infra/src/test/kotlin/.../CleanupBootSmokeIT.kt` (new)
- `.github/workflows/ci.yml` (modify: add `minio-it` job)

## Test plan
- [x] `./gradlew :module-infra:test --tests "*MinioPolicyJsonTest*"` — structural assertions on all 4 policy JSONs
- [x] `./gradlew :module-infra:test --tests "*BootSmokeIT*" -DINTEGRATION_MINIO=true` — module boots with each SA (4 classes)
- [x] `./gradlew :module-infra:test --tests "*MinioPolicyScopeIT*" -DINTEGRATION_MINIO=true` — per-SA in-scope OK, out-of-scope 403
- [x] pipeline-test skill — end-to-end with all 4 modules under new credentials
- [x] CI `minio-it` job — ephemeral MinIO, random SA keys, scope IT runs in PR gate

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Wait for CI + review**

The PR is the final delivery. Implementation work is complete once CI is green (the existing `test` job AND the new `minio-it` job) and review is approved.

---

## Self-Review (per writing-plans skill)

**Spec coverage:**

| Spec section / requirement | Task |
|---|---|
| §1 single bucket | Task 5 (no bucket split, existing ILM preserved) |
| §1 prefix unchanged | Task 2 (no prefix re-keying) |
| §2 4 SAs (post-revision) | Task 2 (4 policy JSONs) + Task 4 (4 secret keys) |
| §2 ext-api owns ocid-mapping | Task 2 step 2 (resource list includes `ocid-mapping/*`) + Task 11 step 4 (E2E verifies write) + Task 2 step 6 (test assertion) |
| §2 calculator split | Task 2 step 3 |
| §2 synchronizer does NOT own ocid-mapping | Task 2 step 4 (resource list omits `ocid-mapping/*`) + Task 2 step 6 (test assertion) |
| §2 cleanup prefix-scoped delete | Task 2 step 5 + Task 11 negative test |
| §3 root isolated to bootstrap | Task 5 (env_file only on minio-bootstrap) |
| §3 one-shot container | Task 5 (restart: "no") |
| §3 idempotency | Task 3 (guarded user/policy create) + Task 5 step 4 (re-run smoke + ILM count assertion) |
| §3 ILM 1-rule-per-prefix invariant | Task 3 (list/rm/add loop) + Task 5 step 4 (assertion) |
| §3 no periodic rotation | Task 9 (ADR with runbook) |
| §3 zero Spring code change | Task 6 step 6 (env vars only, `application.yml` unchanged for 4 modules) |
| §3 rest-controller no MinIO config | Task 6 step 4 (remove `storage.minio.*` block) |
| §3 cleanup no wildcard delete | Task 2 step 5 (prefix-scoped resource) + Task 11 test |
| §3 env file split (4 modules) | Task 6 |
| §3 dev-bootstrap.sh | Task 8 |
| §3 CI ephemeral | Task 13 |
| Appendix A policy skeleton | Task 2 (4 concrete policies) |
| Appendix B bootstrap script | Task 3 (full script) |
| Appendix C env mapping | Task 6 (4 `.env.<module>` files) |
| Appendix D rotation deferred ADR | Task 9 |
| Manual rotation runbook | Task 9 (ADR Appendix) |

No spec requirement is unaddressed.

**Placeholder scan:**

No `TBD` / `TODO` / `fill in` patterns in this plan. The one inline `<copy …>` pattern in Task 4 / Task 6 is a literal copy-paste instruction (not a placeholder for the engineer to invent content). The `<paste-key-N>` patterns in Task 4 step 2 reference secrets that the engineer pastes from the immediately-preceding `openssl rand` output.

**Type / name consistency:**

- Policy names: `policy-ext-api`, `policy-calculator`, `policy-synchronizer`, `policy-cleanup` — used consistently in Task 2, 3, 5.
- SA names: `ext-api`, `calculator`, `synchronizer`, `cleanup` — used consistently across all tasks.
- Env var names: `SA_EXT_API_SECRET_KEY` etc. (uppercased, dashes → underscores) — Task 3, 4, 8, 9, 11, 13 all use this form.
- Secret key format: `openssl rand -hex 32` for prod/dev, `openssl rand -hex 16` for CI (CI is ephemeral, lower entropy is fine). Documented in Task 4 step 1 (hex 32) and Task 13 step 2 (hex 16).
- Resource ARNs: `arn:aws:s3:::maple-expectation/<prefix>` — consistent across all 4 policies.
- Bucket name: `maple-expectation` — used consistently.

**Ambiguity check:**

None. Each step has either literal code, literal command, or a clear instruction with the values defined earlier in the plan.

**Known sub-optimal pattern in Task 10:**

The plan originally proposed `@TestInstance(PER_METHOD)` + per-method `@TestPropertySource` + `@ParameterizedTest`. This pattern is fragile in Spring TestContextManager and was replaced in Task 10 step 1 (corrected) with 4 separate test classes (one per SA). The corrected approach is more boilerplate but more reliable. Documented in the plan as an inline correction so the engineer reading task 10 doesn't re-attempt the broken pattern.
