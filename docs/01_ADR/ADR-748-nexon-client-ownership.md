# ADR-748: Nexon Client Ownership

- Status: Accepted
- Date: 2026-07-20
- Owner: ETL Platform

---

## 1. Background / Problem

### Background

- System-key ETL and user BYOK authentication call the same Nexon Open API.

### Problem

- Transport construction, timeout/body limits, status classification, and redaction have drifted across modules.
- BYOK transient failures can be collapsed into invalid-key results and response publication can race delivery success.

### Goal

- Centralize transport policy while preserving endpoint, property, and event contracts.

---

## 2. Decision

> Create `module-nexon-client` with one shared transport policy and endpoint-aware failure taxonomy.

```text
SYSTEM_BULK transport -> raw-byte system client -> external-api adapters
USER_BYOK transport   -> neutral character model -> auth/legacy adapters
```

The profiles own independent connection providers. Active ETL uses asynchronous typed clients; `module-infra` retains only app/web compatibility mapping. API keys and raw error bodies are never observable data.

---

## 3. Trade-offs

### Sensitivity

- Nexon latency and rate limits
- Connection-pool saturation
- User-provided credential lifetime
- Response size and decoding drift

### Trade-off

| Choice | Gain | Cost |
| --- | --- | --- |
| Shared policy with isolated providers | Consistent safety without cross-workload contention | Two explicitly managed pools |
| Typed failures | Retry and terminal outcomes remain distinct | Compatibility adapters must map old contracts |

### Risk

- Legacy synchronous app callers retain a bounded blocking facade until their ownership is migrated.

### Non-Risk

- System and BYOK capacity cannot consume the same provider queue.
- Raw credentials and bodies are excluded from failure and observability contracts.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| --- | ---: | --- |
| Current system max connections | 250 | `nexon.http-client` default |
| Current system pending acquisitions | 1,000 | 5 s acquire timeout |
| Current system body cap | 2 MiB | raw-byte client |
| Planned BYOK max connections | 32 | independent provider |
| Planned BYOK body cap | 256 KiB | character-list response |

### Observed Result

- Static golden characterization freezes current paths, query names, headers, encoding, timeout defaults, pool defaults, and known unsafe behavior before migration.
- Runtime/performance and live-network baselines are intentionally skipped under the approved verification ceiling.

---

## 5. Summary

> One owned Nexon policy with two isolated transports preserves compatibility while making failures and credentials safe.
