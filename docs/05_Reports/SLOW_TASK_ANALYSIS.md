# Slow Task Analysis — PGMQ Pipeline Load Test

**Source:** `module-app/logs/app.log`
**Generated:** 2026-04-21
**Total slow task entries:** 135,130
**Unique task types:** 16,439 (14 with count >= 10; 16,425 per-character/per-request names)

---

## Top 20 Slow Tasks

| # | Task | Count | Avg (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|------|-------|----------|----------|----------|----------|----------|
| 1 | PgmqWorker:CalculateOnly:expectation_calc_high | 93,236 | 25,466 | 28,533 | 29,863 | 30,571 | 34,500 |
| 2 | Observability:Track:external.api.nexon.basic | 13,801 | 637 | 402 | 1,757 | 2,656 | 5,637 |
| 3 | Observability:Track:external.api.nexon.itemdata | 10,060 | 601 | 398 | 1,603 | 2,238 | 3,961 |
| 4 | V4:PresetJoin | 429 | 839 | 467 | 1,918 | 3,265 | 5,092 |
| 5 | PgmqWorker:ProcessBatch:expectation_calc_high | 202 | 1,350 | 1,278 | 2,704 | 3,325 | 4,985 |
| 6 | ExpectationCalcWorker:PreWarm:expectation_calc_high | 193 | 1,374 | 1,279 | 2,821 | 3,888 | 4,975 |
| 7 | AdaptiveBatch:ProcessChunk | 141 | 2,166 | 2,132 | 3,993 | 5,054 | 5,122 |
| 8 | Parser:StreamingParse:allPresets | 76 | 931 | 948 | 1,185 | 1,245 | 1,245 |
| 9 | ExpectationCalcWorker:BatchWrite | 31 | 875 | 957 | 1,528 | 1,566 | 1,566 |
| 10 | PgmqWorker:DrainBuffer:expectation_calc_high | 31 | 900 | 957 | 1,595 | 1,619 | 1,619 |
| 11 | NexonApiPgmqProcessor:PollAndProcess:nexon_retry_queue | 23 | 10,347 | 9,889 | 15,539 | 20,992 | 20,992 |
| 12 | AlertService:Critical:외부 API 장애 | 12 | 267 | 242 | 387 | 387 | 387 |
| 13 | CubeService:CalculateDP:INT_PERCENT | 11 | 977 | 1,032 | 1,344 | 1,344 | 1,344 |
| 14 | CubeService:CalculateDP:STR_PERCENT | 11 | 837 | 906 | 1,251 | 1,251 | 1,251 |
| 15 | CharacterOcidAdapter:ResolveOcids:count=120 | 7 | 613 | 515 | 1,063 | 1,063 | 1,063 |
| 16 | PgmqClient:Send:nexon_retry_queue | 6 | 824 | 815 | 1,091 | 1,091 | 1,091 |
| 17 | PgmqWorker:ProcessBatch:expectation_calc_low | 5 | 725 | 913 | 1,086 | 1,086 | 1,086 |
| 18 | CubeService:CalculateDP:LUK_PERCENT | 5 | 1,053 | 1,071 | 1,193 | 1,193 | 1,193 |
| 19 | CubeService:CalculateV1:ADDITIONAL | 4 | 973 | 978 | 993 | 993 | 993 |
| 20 | CubeService:CalculateDP:ATTACK_POWER | 3 | 480 | 214 | 1,017 | 1,017 | 1,017 |

---

## Coverage

| Scope | Count | % of Total |
|-------|-------|------------|
| Top 1 (CalculateOnly) | 93,236 | 69.0% |
| Top 3 (+ Nexon observability) | 117,097 | 86.6% |
| Top 10 | 118,393 | 87.6% |
| Count >= 10 | 118,296 | 87.5% |
| Count < 10 (per-character noise) | 16,834 | 12.5% |

---

## Key Findings

### 1. PgmqWorker:CalculateOnly:expectation_calc_high — 69% of all entries

- **93,236 occurrences** — dominant bottleneck, up from 54,661 in prior run (+70%)
- P50 at **28.5s**, P99 at **30.6s** — consistently slow, tight spread
- The P50→P99 gap of only 2s indicates a fixed-cost computation wall, not tail latency variance
- Near the Bulkhead timeout ceiling (~30s) — most tasks are hitting the full timeout budget

### 2. Observability:Track:external.api.nexon.* — 17.6% combined

- **basic** (13.8K) + **itemdata** (10.1K) — up dramatically from 1,251/367 in prior run
- P50 ~400ms is normal, but P95/P99 spikes to **1.6–2.7s**
- Long tail correlates with Nexon API rate-limiting under high load

### 3. V4:PresetJoin — 429 occurrences

- Down from 5,844 in prior run — major improvement
- P50 dropped from 1.9s to 467ms
- Suggests the preset join optimization (parallel equipment calc) is working

### 4. NexonApi Retry Queue — 23 events, avg 10.3s

- Small count but **P99 at 21s** — worst-case latency
- Consistent with prior run (27 events, avg 8.9s) — upstream API degradation persists

### 5. Disappeared from prior run

- **FanOutBatchLoader:Fetch** (was 7,054) → 0 — eliminated
- **AdvisoryLock:ElectLeader** (was 1,848) → 0 — eliminated
- **AdaptiveBatch:FastLane** (was 1,853) → 0 — eliminated

These removals suggest the pipeline architecture changes eliminated several hotspots.
