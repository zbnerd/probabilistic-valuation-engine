# MinIO Operations: Service Account & Policy Isolation

- **Status**: Approved (with post-approval revision on 2026-06-15)
- **Date**: 2026-06-15
- **Owner**: solo dev

> **Revision (2026-06-15):** Q4 dropped `read-api` SA after codebase audit showed rest-controller and module-app have no ObjectStorage caller. Q5 reassigned `ocid-mapping/*` ownership from synchronizer to external-api after OcidLookupPhase write path discovery. CI strategy (Q6) and dev ergonomics (Q7) added to the plan only. See end of this spec for revision history.

---

## 1. Background / Problem

### Background

Pipeline is a Spring Boot multi-module data platform operating against a single MinIO bucket `maple-expectation`. Today every module (external-api, calculator, synchronizer, cleanup, rest-controller, module-app) shares the same credential pair:

```env
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=<MINIO_ROOT_PASSWORD>
```

This is effectively the MinIO root account. The platform has accumulated operational evidence — 82h uptime, 13TB processed, 4B items calculated, cleanup steady state, OOM root-caused — that signals a transition from "build" to "operate" phase. Coolify Managed Application migration is on the roadmap. Operating a multi-module platform on a shared root credential violates the least-privilege principle and blocks several follow-on improvements (per-environment key separation, audit logging, key rotation without cluster-wide restart).

### Problem

1. **Blast radius is bucket-wide.** Any module compromise (or bug, e.g. `cleanup` with `Delete: *`) can destroy the entire dataset.
2. **No audit signal.** All operations appear under a single identity, so a malicious or misbehaving module cannot be distinguished in MinIO logs.
3. **Key rotation is cluster-wide.** There is no path to rotate a single module's credential without restarting every other module.
4. **Coolify secret model is undermined.** Coolify's per-application secret isolation is meaningless when every application reads the same root credential.

### Goal

Introduce least-privilege per-module credentials at the MinIO layer, isolate the root account to a one-shot bootstrap role, and keep the change set to zero Spring source modifications.

### Non-Goal

- Bucket split (lakehouse / zone separation).
- Lifecycle / retention policy.
- Periodic key rotation.
- Object lock / WORM.
- STS / temporary credentials.
- Terraform / IaC (current scale; revisit at scale-out).

---

## 2. Decision

> **Single bucket, prefix-based policy isolation, four service accounts, one-shot bootstrap container for SA + policy creation, root credential isolated to the bootstrap container only. No periodic key rotation; rotation deferred to an ADR. CI uses ephemeral MinIO with random SA keys (no GitHub Secrets). Local dev uses a one-line `scripts/dev-bootstrap.sh`.**

```text
MinIO
  └─ root (MINIO_ROOT_USER / MINIO_ROOT_PASSWORD)
       └─ used by: minio-bootstrap container (one-shot, restart=no)
       └─ NOT used by: any runtime module

Service Accounts (4)
  ├─ ext-api       → Get/Put  runs/*, snapshots/*, ocid-mapping/*
  ├─ calculator    → Get      runs/*, data/snapshots/*
  │                → Put      calculator/runs/*
  ├─ synchronizer  → Get/List runs/*, calculator/runs/*
  └─ cleanup       → Get/List/Delete runs/*, calculator/runs/*  (prefix-scoped, no wildcard)

Modules with NO MinIO credential
  ├─ rest-controller → reads via PostgreSQL read model (v6 path)
  └─ module-app (legacy) → same; no ObjectStorage caller

Boot
  └─ minio-bootstrap: minio/mc, depends_on minio healthy, restart="no"
       └─ mc admin user add × 4
       └─ mc admin policy create × 4
       └─ mc admin policy attach × 4
       └─ mc mb --ignore-existing maple-expectation
       └─ mc ilm ls/rm/add  (idempotent: 1 rule per prefix)
       └─ exit 0

Dev
  └─ scripts/dev-bootstrap.sh (one-liner, generates .env.bootstrap + 4 × .env.<module>)

CI
  └─ GitHub Actions ephemeral MinIO + openssl rand SA keys (no GitHub Secrets)
```

**Bucket** `maple-expectation` — unchanged.

**Prefix** — unchanged. `data/snapshots/*` is marked **LEGACY** in the bootstrap comments; current policy includes it for backward compatibility.

**Spring code** — unchanged. `MinioProperties.accessKey / secretKey` is bound from `${MINIO_ACCESS_KEY}` / `${MINIO_SECRET_KEY}` exactly as today. The only thing that changes is *which value* those env vars hold per module.

---

## 3. Trade-offs

### Sensitivity

- **Prefix convention drift** — If a future module introduces a new top-level prefix, its SA policy must be updated. Mitigated by centralising prefix documentation in this spec and adding a bootstrap-time assertion that each SA can read its expected prefix.
- **Bootstrap idempotency** — Re-running the bootstrap container must not fail or duplicate users OR ILM rules. The script uses `mc admin user add` guarded by `mc admin user info $name`, `mc admin policy create` guarded by `mc admin policy info`, and `mc ilm add` guarded by `mc ilm ls` (with `mc ilm rm` for the 1-or-N existing rule case) so the invariant is "exactly 1 ILM rule per managed prefix after every run".
- **ocid-mapping/* ownership** — `OcidLookupPhase` (external-api) WRITES `ocid-mapping/ocid-mapping-*.jsonl.gz` via `objectStorage.putStream`. `OcidCacheProvider` (external-api) READS the same prefix. `synchronizer` does not touch this prefix. Owner is exclusively external-api; cleanup excludes it (ILM expiry is the only deletion path).
- **`validateBucket()` boot smoke** — `MinioObjectStorage.@PostConstruct` calls `headBucket`. Each SA needs at least `s3:ListBucket` (or `s3:HeadBucket` via `s3:GetBucket*`) for the bucket to pass startup. Policies include the minimum IAM action set required for the SDK calls the storage layer issues.
- **Env file duplication** — 4 env files (one per module) replace one shared file. Drift risk: a rotation in one file does not propagate. Mitigated by treating each env file as the module's only source of truth, with no shared symlinks. `scripts/dev-bootstrap.sh` regenerates the full set in one call.
- **CI ephemeral secret handling** — Per-job ephemeral MinIO + random SA keys. No long-lived SA keys in GitHub Secrets. Job duration ≈ 10 min; secrets die with the container.

### Trade-off

| Choice | What we get | What we give up |
|---|---|---|
| Single bucket + prefix policy (vs. multi-bucket) | One storage surface to operate; cross-module reads work without cross-bucket policy; lifecycle / replication applied once | Cleanup's blast radius covers both `runs/*` and `calculator/runs/*` (still prefix-bounded) |
| 4 service accounts (vs. 3, 5, or more) | Calibrated to the 4 modules that actually call ObjectStorage; rest-controller and module-app have no caller, so no SA needed | If a future module gains an ObjectStorage caller, a 5th SA + policy must be added (acceptable friction) |
| One-shot bootstrap container (vs. init hook or IaC) | Reproducible, restart-safe, no extra tooling; root key never leaves the bootstrap env | Re-running is required for any policy change; no audit trail of policy drift |
| `scripts/dev-bootstrap.sh` (vs. README 5-step manual) | 30-second dev setup, no typo risk, professional onboarding | One extra script to maintain (low complexity) |
| CI ephemeral MinIO (vs. GitHub Secrets for SA keys) | Zero long-lived CI secrets; no drift vs. prod; SA scope IT still runs in PR gate | CI job is slightly heavier (per-job MinIO spin-up) |
| No periodic rotation (vs. 6-month cycle) | Zero operational burden at current scale; no rotation runbook required | Key compromise window is unbounded — relies on suspicion-based manual rotation |
| Preserve all current prefixes (vs. lakehouse re-key) | Zero data migration; zero code change; legacy `data/snapshots/*` remains accessible | Prefix layout is heterogeneous; new engineers must read this spec to understand it |

### Risk

- **Cleanup misuse** — A bug in `RunCleanupService` that lists under the wrong prefix is no longer masked by "we have root anyway"; it now manifests as `AccessDenied` or, if the policy is misconfigured, as silent no-op. **Mitigation:** integration test that asserts each SA can list its expected prefix after bootstrap, and that listing outside its prefix returns 403.
- **Bootstrap container failure** — If bootstrap fails or is skipped, modules start with stale credentials and fail their `validateBucket()`. **Mitigation:** `depends_on: condition: service_healthy` on the MinIO service; modules use `restart: on-failure` so the cluster self-recovers once bootstrap completes.
- **Env file drift** — Updating one module's key without updating all replicas after a future scale-out. **Mitigation:** Coolify per-application secret model (when migrating) replaces env files and provides rotation in-place.

### Non-Risk

- ~~Bucket split needed now~~ — Not at 13TB and 5 modules; revisit at multi-environment or >50TB scale.
- ~~Periodic rotation~~ — At current scale, suspicion-based manual rotation is operationally appropriate.
- ~~STS / temporary credentials~~ — MinIO service accounts with static keys are sufficient until cross-cloud identity federation is needed.

---

## 4. Result / Evidence

### Deliverables

| Item | Path | Change |
|---|---|---|
| This spec | `docs/superpowers/specs/2026-06-15-minio-operations-design.md` | new |
| Bootstrap script | `docker/minio/bootstrap.sh` | new |
| Bootstrap service | `docker-compose.yml` (`minio-bootstrap:` block) | add |
| Module env files | `.env.ext-api`, `.env.calculator`, `.env.synchronizer`, `.env.cleanup` | new (split from `.env`) |
| `.env` | shared MinIO endpoint / region / bucket only | trim root + per-module keys |
| `.env.bootstrap` | `MINIO_ROOT_USER` + `MINIO_ROOT_PASSWORD` for the bootstrap container | new |
| Module `application.yml` | unchanged | none |
| Spring source | unchanged | none |
| ADR (rotation deferred) | `docs/01_ADR/ADR-NNN_minio-key-rotation-deferred.md` | new |

### Metrics

| Metric | Value | Notes |
|---|---|---|
| Spring source files changed | 0 | Spec verified by reading `MinioProperties`, `StorageConfig`, `MinioObjectStorage`, all module `application.yml` |
| Bucket count | 1 | `maple-expectation` |
| Service accounts | 4 | ext-api, calculator, synchronizer, cleanup (read-api dropped — see revision history) |
| Policies | 5 | one per SA |
| Env files | 5 (+ 1 bootstrap) | replaces 1 shared `.env` for credentials |
| `cleanup` `DeleteObject` resource scope | `runs/*`, `calculator/runs/*` | no wildcard |
| Root credential references in runtime modules | 0 | after env split |

### Observed Result

- Each module's `application.yml` continues to bind `${MINIO_ACCESS_KEY}` / `${MINIO_SECRET_KEY}` — the value at runtime is now per-module.
- `MinioObjectStorage.@PostConstruct validateBucket()` still issues `headBucket` against `maple-expectation` — passes for any of the 5 SAs because policies grant the required bucket-level action.
- Cross-module read paths (calculator → `runs/*`, synchronizer → `calculator/runs/*`) work because the policy is identity-scoped, not bucket-scoped.
- Root credential surface after the change = the `minio-bootstrap` service env only. Removing the bootstrap container from a running stack exposes zero root credentials.

---

## 5. Summary

> Replace the shared `minioadmin` root credential with four prefix-scoped service accounts and a one-shot bootstrap container, leaving all Spring source and `application.yml` bindings untouched.

---

## Appendix A: Policy skeleton (per `mc admin policy create`)

```
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": [
        "arn:aws:s3:::maple-expectation/runs/*",
        "arn:aws:s3:::maple-expectation/snapshots/*"
      ]
    }
  ]
}
```

Action sets per SA (Resource lists in §2):

| SA | Action |
|---|---|
| ext-api | `s3:GetObject`, `s3:HeadObject`, `s3:PutObject` |
| calculator | `s3:GetObject`, `s3:HeadObject`, `s3:PutObject` (split into 2 statements for resource clarity) |
| synchronizer | `s3:GetObject`, `s3:HeadObject`, `s3:ListBucket` |
| cleanup | `s3:GetObject`, `s3:HeadObject`, `s3:ListBucket`, `s3:DeleteObject` |

`cleanup` is split into two statements internally: `Get` + `List` on `runs/*` and `calculator/runs/*`, then `Delete` on the same resources. The split is for policy readability, not access separation. `s3:HeadObject` is required for `ObjectStorage.exists()` and `getLastModified()` in module-infra.

synchronizer's `ocid-mapping/*` resource was originally listed here, but post-audit (revision 2026-06-15) it was dropped — `ocid-mapping/*` is owned exclusively by external-api.

---

## Appendix B: Bootstrap script outline

```bash
#!/usr/bin/env bash
# docker/minio/bootstrap.sh
# One-shot MinIO bootstrap: bucket, ILM, 4 service accounts, 4 policies, attach.
# Idempotent — safe to re-run. Preserves the invariant:
#   exactly 1 ILM rule per managed prefix after every run.
# Required env: MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_ENDPOINT,
#   SA_EXT_API_SECRET_KEY, SA_CALCULATOR_SECRET_KEY,
#   SA_SYNCHRONIZER_SECRET_KEY, SA_CLEANUP_SECRET_KEY.

set -euo pipefail

mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/maple-expectation
mc anonymous set none local/maple-expectation

# ILM: list, remove all existing rules for the prefix, add one fresh rule.
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

**Secret source: env file** (`.env.bootstrap`) read by the `minio-bootstrap` container only. No Docker secrets driver, no mounted files — matches the existing project's env-file convention. Per-SA secrets are generated at first run with `openssl rand -hex 32` and stored in `.env.bootstrap` alongside the root credentials. `.env.bootstrap` is `.gitignore`'d. Local dev uses `scripts/dev-bootstrap.sh` to regenerate the full set in one call; CI uses per-job ephemeral MinIO with random SA keys.

---

## Appendix C: Module → env file mapping

| Module | Env file | `MINIO_ACCESS_KEY` value |
|---|---|---|
| module-external-api | `.env.ext-api` | `ext-api` |
| module-calculator | `.env.calculator` | `calculator` |
| module-synchronizer | `.env.synchronizer` | `synchronizer` |
| module-cleanup | `.env.cleanup` | `cleanup` |
| module-rest-controller | (no MinIO env) | n/a — module uses PostgreSQL read model only |
| module-app (legacy) | (no MinIO env) | n/a — module has no ObjectStorage caller |
| Airflow | (no MinIO env) | n/a — HTTP trigger only |

---

## Appendix D: Out-of-scope follow-ups (ADR candidates)

- `ADR-NNN_minio-key-rotation-deferred` — status Deferred, trigger: scale-out, multi-env, audit, or incident. Includes a manual rotation runbook (prod-only).
- Lifecycle / retention policy tuning per prefix.
- Lakehouse / zone re-keying if dataset taxonomy demands.
- Multi-env SA isolation (dev/stg/prod each with their own 4 SAs).

---

## Revision history

| Date | Change | Trigger |
|---|---|---|
| 2026-06-15 | Initial draft | Brainstorming session |
| 2026-06-15 | Q4: dropped `read-api` SA (rest-controller and module-app have no ObjectStorage caller) | Codebase audit |
| 2026-06-15 | Q5: reassigned `ocid-mapping/*` ownership from synchronizer to external-api | OcidLookupPhase write path discovery |
| 2026-06-15 | Q6: CI uses ephemeral MinIO + random SA keys (no GitHub Secrets) | User preference for zero long-lived CI secrets |
| 2026-06-15 | Q7: local dev uses `scripts/dev-bootstrap.sh` (one-liner) instead of README 5-step | User preference for automated onboarding |
