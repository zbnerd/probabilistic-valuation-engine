# ADR-744: Internal-Network-Only Service Binding

- Status: Accepted
- Date: 2026-07-03
- Owner: platform / infra

---

## 1. Background / Problem

### Background

- The 2026-07-03 Airflow-DB cryptominer compromise
  ([incident report](../23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md))
  entered through an internet-exposed postgres port (`5432 → 0.0.0.0`) with a
  weak superuser password. The root cause was **internet exposure combined with
  weak/default auth** (ADR-744's sibling hardening: `127.0.0.1` bind + strong pw
  on airflow-db, PR #1455).
- At capture time, the same `0.0.0.0` exposure applied to almost every service:
  app postgres `5432`, redis `6379`, kafka `9092`, minio `9000/9001`, grafana
  `3001`, loki `3100`, app modules `8081–8084`, airflow UI `8180`. Docker
  `0.0.0.0` publishes bypass UFW (Docker writes the `DOCKER` iptables chain
  directly), so a host firewall alone does not close them.

### Problem

- Internal services that are only consumed by other containers were reachable
  from the internet. Removing that exposure is the highest-leverage preventive
  control (incident report §7).

### Goal

- Force all internal services to communicate on the `maple-network` bridge only.
- Publish to the host only where a human or external proxy genuinely needs it,
  and even then bind to `127.0.0.1` (SSH-tunnel access), never `0.0.0.0`.
- Only Coolify's public entry (`80/443`) and SSH (`22`) remain internet-facing.

---

## 2. Decision

> Move the two `network_mode: host` consumers (`prometheus`, `airflow`) onto the
> `maple-network` bridge and remove every non-essential host port publish.
> Internal services talk by service name; operator UIs bind to `127.0.0.1`.

```text
maple-network (bridge) — all services reach each other by service name
  postgres:5432   redis:6379   kafka:29092   minio:9000   loki:3100
  external-api/calculator/synchronizer/cleanup:8081-8084
  airflow-db:5432  cadvisor:8080  node-exporter:9100  alertmanager:9093
  prometheus:9090  grafana:3000

Host publishes (127.0.0.1 only — operator via SSH tunnel):
  127.0.0.1:9090 (prometheus UI)   127.0.0.1:3001 (grafana)
  127.0.0.1:9001 (minio console)   127.0.0.1:8180 (airflow UI)

Host publishes (0.0.0.0, intentional public entry):
  80/443 (coolify-proxy)   22 (ssh)   [coolify mgmt 8000]

Removed entirely: postgres/redis/kafka/minio-9000/loki/app-8081-8084/cadvisor
```

### Key wiring changes

- **prometheus**: `network_mode: host` → `networks: [maple-network]`. Scrape
  targets `localhost:808X` → `external-api:808X` (etc.), `localhost:8086` →
  `cadvisor:8080`. This also fixes a latent issue: grafana's datasource
  (`http://prometheus:9090`) only resolves once prometheus is on the bridge.
- **airflow webserver/scheduler**: `network_mode: host` →
  `networks: [maple-network]`. `SQL_ALCHEMY_CONN …@localhost:5433` →
  `…@airflow-db:5432`. Kafka already `kafka:29092`; Airflow connections already
  use service-name hosts (`external-api`) — both now resolve correctly on the
  bridge (they could not under host-network).
- **DBs / internal services**: `ports:` removed. Healthchecks are intra-container
  (`pg_isready`, `redis-cli ping`, `mc ready local`, kafka `localhost:9092` inside
  the container) so they keep working without a host publish.

---

## 3. Trade-offs

### Sensitivity

* Inter-service references must use service names consistently (already true for
  app modules; prometheus scrape config + airflow `SQL_ALCHEMY_CONN` updated here).
* Operator ergonomics: reaching grafana / airflow UI / minio console now requires
  an SSH tunnel (`ssh -L 3001:localhost:3001 …`) instead of a direct browser URL.
* `network_mode: host` consumers cannot be migrated lazily — prometheus/airflow
  must move in the same change as the publish removal, or they lose reachability.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| bridge-only + `127.0.0.1` UIs | internet exposure removed; latent host-net DNS bugs fixed; least-privilege networking | operator convenience (SSH tunnel for UIs); one coordinated restart |

### Risk

* A stale `localhost:` reference anywhere would silently break a scrape /
  connection. Mitigated by post-apply verification: prometheus `/api/v1/targets`
  all UP + airflow DAG trigger → sensor.
* `docker-compose.observability.yml` is a **legacy standalone overlay** (not in
  the active bring-up; referenced only in docs). It still defines a host-network
  prometheus and `0.0.0.0` ports. It is **not modified here**; if ever revived it
  must be migrated too or removed. Tracked as a follow-up.

### Non-Risk

* App modules ↔ postgres/redis/kafka/minio: already service-name on
  `maple-network`, unaffected by publish removal.
* Healthchecks: intra-container, unaffected.
* Coolify public REST (`8080`): deployed as a separate Coolify-managed service,
  outside this repo's compose — out of scope, unaffected.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| `0.0.0.0` host publishes (maple stack) before | 13 | postgres/redis/kafka/minio×2/grafana/loki/app×4/airflow-db/airflow-UI |
| `0.0.0.0` host publishes after | 3 | coolify 80/443, coolify-mgmt 8000 (SSH 22 is host, not compose) |
| `network_mode: host` services before | 2 | prometheus, airflow |
| `network_mode: host` services after | 0 | |

### Observed Result

* Populated post-apply (prometheus targets UP, module health UP, airflow reaches
  db/kafka/modules, airflow-db CPU clean).

---

## 5. Summary

> Internal services talk on the `maple-network` bridge by service name; only
> Coolify's `80/443` and SSH stay public, and operator UIs bind to `127.0.0.1` —
> closing the internet-exposure half of the 2026-07-03 compromise's root cause.
