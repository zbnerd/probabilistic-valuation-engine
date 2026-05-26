# ADR-717: External API Nexon Throughput Tuning

- Status: Accepted
- Date: 2026-05-17
- Owner: Backend

---

## 1. Background / Problem

### Background

- `module-external-api` fetches large Nexon `ITEM_EQUIPMENT` payloads through WebClient/Reactor Netty.
- `ITEM_EQUIPMENT` is the meaningful production load path; `CHARACTER_BASIC` is small and can hide the real bottleneck.
- The urgent Kafka pipeline depends on this stage draining steadily before calculator and synchronizer can keep up.

### Problem

- The previous default connection pool was too small for sustained `ITEM_EQUIPMENT` collection.
- Raising only the rate limit above the connection pool's stable capacity creates pending connection pressure without reaching the configured rate.
- Sink submit time was negligible, so tuning should start with HTTP connection pool and external API rate, not storage writer changes.

### Goal

- Make the default external-api runtime use the observed stable operating point.
- Keep the values externally overrideable for future load tests and production tuning.
- Preserve before/after evidence for later TCP/read-path or async-contract work.

---

## 2. Decision

> Run the Nexon snapshot fetch path with a default HTTP connection pool of 150 and default `ITEM_EQUIPMENT` fetch rate of 250 requests per second.

```text
NEXON_HTTP_MAX_CONNECTIONS default: 150
external-api.rate-limit.permits-per-second default: 250
```

The rate remains configurable through `EXTERNAL_API_RATE_LIMIT_PERMITS_PER_SECOND`.
The pool remains configurable through `NEXON_HTTP_MAX_CONNECTIONS`.

---

## 3. Trade-offs

### Sensitivity

* Nexon `ITEM_EQUIPMENT` response size, commonly around 200KB+.
* Nexon external latency and tail response distribution.
* Reactor Netty connection pool active/pending count.
* Kafka chunk-ready throughput after snapshot files are written.
* JVM heap and network bandwidth under larger response volume.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Pool 150 / rate 250 default | Stable pressure point close to measured capacity | Does not chase configured 300/s |
| Keep `.join()` path unchanged | Avoids behavior change in snapshot pipeline | Blocking join may become next bottleneck after HTTP pool is no longer saturated |
| Keep sink architecture unchanged | Avoids unnecessary writer complexity | Does not decouple fetch and disk/Kafka publication yet |

### Risk

* Actual production throughput may vary with Nexon latency and response size.
* Pool 150 can still saturate under `ITEM_EQUIPMENT`; pending connections must be monitored.
* If pool pressure drops after future improvements, `.join()` and batch wait may become the next bottleneck.

### Non-Risk

* Snapshot sink submit is not the current bottleneck; observed submit time was near zero.
* `CHARACTER_BASIC` throughput is not a useful proxy for production pressure.
* Pool 200 did not prove better during the sampled run and should not be the default.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| Pool 50 / rate 200 body avg | ~510ms | Pool active 50/50, pending observed |
| Pool 100 / rate 200 throughput | ~200/s | Reached configured cap |
| Pool 150 / rate 300 throughput | ~220-235/s | Pool often 150/150 with pending |
| Pool 200 / rate 300 early throughput | ~180/s | Unstable early run; pool snapshots alternated saturated and idle |
| Pool 150 / rate 250 throughput | ~215/s | Sample0->4: 51,759 item-equipment responses over 241s |
| Pool 150 / rate 250 pool state | 150/150 active with pending in hot samples | Pending 68-83 observed in early samples |
| Sink submit avg | <0.01ms | Not the limiting stage in sampled runs |

### Observed Result

* Increasing pool from 50 to 100 removed the first connection bottleneck and allowed the configured 200/s rate.
* Increasing target rate to 300/s did not produce 300/s actual throughput.
* Pool 150 with rate 250 keeps pressure controlled while matching the observed stable band.
* The next investigation should use pool/rate metrics plus body-received latency before changing the async contract.

---

## 5. Summary

> Default external-api Nexon fetch tuning is pool 150 and rate 250 because measured `ITEM_EQUIPMENT` throughput stabilized around 215-235/s and sink work was not the bottleneck.
