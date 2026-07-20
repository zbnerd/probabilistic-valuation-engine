# ADR-747: Valuation Kernel Ownership

- Status: Accepted
- Date: 2026-07-20
- Owner: Probabilistic Valuation Engine team

---

## 1. Background / Problem

### Background

- The calculator currently obtains deterministic V4 equipment valuation through module-infra.
- Probability CSV loading, Spring wiring, cache concerns, and pure formulas are coupled in one runtime graph.

### Problem

- module-calculator inherits module-infra runtime dependencies to execute deterministic calculations.
- The mutable repository-shaped probability table makes exact input, table, and logic identity difficult to prove.

### Goal

- Give the deterministic valuation rules and immutable probability model one pure owner while retaining calculator and legacy compatibility boundaries.

---

## 2. Decision

> Move the deterministic V4 equipment valuation subset and immutable probability-table model to module-core, reusing existing core cost/rate/starforce policies. Calculator owns CSV loading, cache, metrics, mapping, Spring wiring, and the observed Noljang target cap. module-infra keeps old public types as delegates for app/web. Extraction preserves observed mass normalization and all golden outputs.

---

## 3. Trade-offs

### Sensitivity

- Probability CSV content and ordering
- Floating-point mass normalization and rounding order
- Complete canonical input, table checksum, and logic-version identity
- Legacy bean and public-type compatibility

### Trade-off

| Choice | Gain | Cost |
| --- | --- | --- |
| Pure immutable core kernel | Deterministic tests and correct dependency direction | Explicit adapter mapping at calculator and infra boundaries |
| Preserve observed formulas and normalization | Zero unexplained output drift | Known policy corrections remain separate work |
| Keep an infra compatibility facade | Stable app/web callers | Two CSV adapters coexist temporarily and require a SHA guard |

### Risk

- An incomplete cache key or resource drift could return a result calculated under different rules.
- Legacy edge behavior can be counterintuitive and must remain characterized during extraction.

### Non-Risk

- Kafka event JSON and calculator result JSON do not change in this decision.
- The separate V1/V2 cube service and app/web-only Noljang table are outside this migration.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| --- | ---: | --- |
| Frozen golden cases | 26 | Full, component, grade/level, star, Noljang, normalization, option, and invalid-input cases |
| Infra CSV rows | 413,802 | 413,803 lines including header |
| Infra CSV SHA-256 | `9a329fe4b861c9f21b69d766e1847e59982c2a02ea4f30c6e5b332a7f2e955c0` | Baseline at `0ff9666b9f1ca4e4f2eaeb5070d6b71332c8375a` |
| Golden fixture SHA-256 | `4eb178a35b04a29157a3464f3f139124f80f6c9fb02bb14a21ecd5ee21ccc8c2` | Read-only fixture |

### Observed Result

- The focused legacy golden test passes all 26 frozen cases.
- The calculator resource copy is absent at the baseline commit; its Task 5 loader adds the copy and the byte-identity guard together.
- Runtime and performance measurements are intentionally deferred under the approved verification ceiling.

---

## 5. Summary

> Pure valuation rules belong to module-core; calculator and infra retain only their adapter responsibilities while exact legacy behavior stays frozen.
