# MinIO Operations: Service Account & Policy Isolation

- **Status**: Draft (brainstorming complete, awaiting user review)
- **Date**: 2026-06-15
- **Owner**: solo dev

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

> **Single bucket, prefix-based policy isolation, five service accounts, one-shot bootstrap container for SA + policy creation, root credential isolated to the bootstrap container only. No periodic key rotation; rotation deferred to an ADR.**

```text
MinIO
  └─ root (MINIO_ROOT_USER / MINIO_ROOT_PASSWORD)
       └─ used by: minio-bootstrap container (one-shot, restart=no)
       └─ NOT used by: any runtime module

Service Accounts (5)
  ├─ ext-api       → Get/Put  runs/*, snapshots/*
  ├─ calculator    → Get      runs/*, data/snapshots/*
  │                → Put      calculator/runs/*
  ├─ synchronizer  → Get/List runs/*, calculator/runs/*
  ├─ cleanup       → Get/List/Delete runs/*, calculator/runs/*  (prefix-scoped)
  └─ read-api      → Get      maple-expectation/*  (read-only wildcard; narrow after caller audit)

Boot
  └─ minio-bootstrap: minio/mc, depends_on minio healthy, restart="no"
       └─ mc admin user add × 5
       └─ mc admin policy create × 5
       └─ mc admin policy attach × 5
       └─ mc mb --ignore-existing maple-expectation
       └─ exit 0
```

**Bucket** `maple-expectation` — unchanged.

**Prefix** — unchanged. `data/snapshots/*` is marked **LEGACY** in the bootstrap comments; current policy includes it for backward compatibility.

**Spring code** — unchanged. `MinioProperties.accessKey / secretKey` is bound from `${MINIO_ACCESS_KEY}` / `${MINIO_SECRET_KEY}` exactly as today. The only thing that changes is *which value* those env vars hold per module.

---

## 3. Trade-offs

### Sensitivity

- **Prefix convention drift** — If a future module introduces a new top-level prefix, its SA policy must be updated. Mitigated by centralising prefix documentation in this spec and adding a bootstrap-time assertion that each SA can read its expected prefix.
- **Bootstrap idempotency** — Re-running the bootstrap container must not fail or duplicate users. The script uses `mc admin user add` guarded by `mc admin user info $name` and `mc admin policy create` with `--ignore-existing`-style guards.
- **`read-api` scope** — rest-controller and module-app both call `ObjectStorage.get(key)` against arbitrary keys, with no observed prefix restriction in the current code. The `read-api` policy grants `s3:GetObject` on `maple-expectation/*` (read-only wildcard). A follow-up caller audit narrows the resource to the actual prefix set used; until that audit lands, the broadest read-only policy is accepted as the lower-risk default (read-only blast radius).
- **`validateBucket()` boot smoke** — `MinioObjectStorage.@PostConstruct` calls `headBucket`. Each SA needs at least `s3:ListBucket` (or `s3:HeadBucket` via `s3:GetBucket*`) for the bucket to pass startup. Policies include the minimum IAM action set required for the SDK calls the storage layer issues.
- **Env file duplication** — 5 env files (one per module) replace one shared file. Drift risk: a rotation in one file does not propagate. Mitigated by treating each env file as the module's only source of truth, with no shared symlinks.

### Trade-off

| Choice | What we get | What we give up |
|---|---|---|
| Single bucket + prefix policy (vs. multi-bucket) | One storage surface to operate; cross-module reads work without cross-bucket policy; lifecycle / replication applied once | Cleanup's blast radius covers both `runs/*` and `calculator/runs/*` (still prefix-bounded) |
| 5 service accounts (vs. 3 or 10) | Calibrated to current module count; 3 leaves cleanup/synchronizer overlapping, 10 invents roles that don't exist | No clear separation between "operator" and "service" identities (deferred) |
| One-shot bootstrap container (vs. init hook or IaC) | Reproducible, restart-safe, no extra tooling; root key never leaves the bootstrap env | Re-running is required for any policy change; no audit trail of policy drift |
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
| Module env files | `.env.ext-api`, `.env.calculator`, `.env.synchronizer`, `.env.cleanup`, `.env.read-api` | new (split from `.env`) |
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
| Service accounts | 5 | ext-api, calculator, synchronizer, cleanup, read-api |
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

> Replace the shared `minioadmin` root credential with five prefix-scoped service accounts and a one-shot bootstrap container, leaving all Spring source and `application.yml` bindings untouched.

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
| ext-api | `s3:GetObject`, `s3:PutObject` |
| calculator | `s3:GetObject`, `s3:PutObject` (split into 2 statements for resource clarity) |
| synchronizer | `s3:GetObject`, `s3:ListBucket` |
| cleanup | `s3:GetObject`, `s3:ListBucket`, `s3:DeleteObject` |
| read-api | `s3:GetObject` |

`cleanup` is split into two statements internally: `Get` + `List` on `runs/*` and `calculator/runs/*`, then `Delete` on the same resources. The split is for policy readability, not access separation.

---

## Appendix B: Bootstrap script outline

```bash
#!/usr/bin/env bash
set -euo pipefail

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/maple-expectation

for sa in ext-api calculator synchronizer cleanup read-api; do
  # SA secret comes from the bootstrap container's env file (.env.bootstrap)
  # as SA_<NAME>_SECRET_KEY. This is the only place that secret lives.
  secret_var="SA_${sa//-/_}_SECRET_KEY"
  sa_secret="${!secret_var:?missing $secret_var}"

  mc admin user info local "$sa" >/dev/null 2>&1 || \
    mc admin user add local "$sa" "$sa_secret"
  mc admin policy info local "policy-$sa" >/dev/null 2>&1 || \
    mc admin policy create local "policy-$sa" "/etc/minio/policies/$sa.json"
  mc admin policy attach local "policy-$sa" --user "$sa"
done
```

**Secret source: env file** (`.env.bootstrap`) read by the `minio-bootstrap` container only. No Docker secrets driver, no mounted files — matches the existing project's env-file convention. Per-SA secrets are generated at first run with `openssl rand -hex 32` and stored in `.env.bootstrap` alongside the root credentials. `.env.bootstrap` is `.gitignore`'d.

---

## Appendix C: Module → env file mapping

| Module | Env file | `MINIO_ACCESS_KEY` value |
|---|---|---|
| module-external-api | `.env.ext-api` | `ext-api` |
| module-calculator | `.env.calculator` | `calculator` |
| module-synchronizer | `.env.synchronizer` | `synchronizer` |
| module-cleanup | `.env.cleanup` | `cleanup` |
| module-rest-controller | `.env.read-api` | `read-api` |
| module-app (legacy) | `.env.read-api` | `read-api` |
| Airflow | (no MinIO env) | n/a |

---

## Appendix D: Out-of-scope follow-ups (ADR candidates)

- `ADR-NNN_minio-key-rotation-deferred` — status Deferred, trigger: 5→N scale-out or external audit requirement.
- Lifecycle / retention policy on `runs/*` and `calculator/runs/*`.
- Lakehouse / zone re-keying if dataset taxonomy demands.
- `read-api` policy scope narrowing after a full caller scan.
