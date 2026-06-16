# ADR-728: MinIO Service Account Key Rotation — Deferred

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
