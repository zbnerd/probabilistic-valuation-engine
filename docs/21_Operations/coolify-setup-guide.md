# Coolify Setup Guide — Self-Healing Maple Stack

How the maple stack runs under Coolify (3-layer self-healing) and how to operate it. Covers Phases 1–3 (ADRs 731, 732, 733).

## Topology

Two Coolify **Docker Compose** resources, one shared external network, one autoheal sidecar.

| Resource | Compose file | Containers | Auto-deploy |
|----------|--------------|------------|-------------|
| `maple-infra` | `docker-compose.yml` | postgres, minio, kafka, redis, prometheus, grafana, loki, promtail, cadvisor, autoheal, minio-bootstrap | OFF (manual) |
| `maple-apps` | `docker-compose.services.yml` | external-api, calculator, synchronizer, cleanup | ON (push to `develop`) |

Network: `maple-network` is **external** — create once before first deploy:
```bash
docker network create --subnet=172.20.0.0/16 maple-network
```

## 3-Layer self-healing

| Layer | Owner | Fires on | Action |
|-------|-------|----------|--------|
| L1 Docker restart policy | Docker daemon | container exit | instant restart |
| L2 Coolify Sentinel | Coolify server setting | stopped/abnormal-exit container | restart |
| L3 autoheal | autoheal sidecar | `health_status=unhealthy` (labeled containers) | `docker restart` |

Every persistent container has a `healthcheck` + the `autoheal: "true"` label. Excluded: `minio-bootstrap` (one-shot), `autoheal` (cannot restart itself), `cadvisor` (observability infra; `restart: always` only — no healthcheck, to avoid false restart-loops on images lacking the probe binary).

## Secrets

| Tier | Mechanism | Examples |
|------|-----------|----------|
| A. Coolify Secrets (encrypted) | Coolify UI → env | `DB_ROOT_PASSWORD`, `NEXON_API_KEY`, `MINIO_ROOT_USER/PASSWORD`, `GRAFANA_ADMIN_*`, `SA_*_SECRET_KEY` |
| B. File secret (SA keys) | bind mount at `SECRETS_DIR_HOST` (`/opt/maple/secrets`) | `sa-<module>.key` (4 files) |
| C. Plain config | Coolify env | `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, image refs, `SECRETS_DIR` |

`/opt/maple/secrets/` is written by `minio-bootstrap` (infra resource) and read by the apps (apps resource) via the compose `secrets:` directive. **Back it up** alongside the volumes.

## Deploy order

1. `docker network create --subnet=172.20.0.0/16 maple-network` (one-time)
2. Deploy `maple-infra` → wait all healthy.
3. Deploy `maple-apps` → wait all healthy. If infra not ready, apps crash→restart until it is.

## Image pipeline (apps)

```
push to develop
  → GitHub Actions: bootJar → build.sh → tag/push ghcr.io/zbnerd/maple-<svc>:{sha,latest}
Coolify maple-apps (auto-deploy) → docker compose pull + up
```

Image refs in compose use `${IMAGE_<SVC>}` (default `maple/<svc>:dev` for local dev).

## Rollback

- **Apps:** set `IMAGE_<SVC>=ghcr.io/zbnerd/maple-<svc>:sha-<prior>` in the `maple-apps` resource env, redeploy. Or use Coolify's Rollback UI.
- **Infra:** NOT a rollback target — postgres/kafka version downgrades risk data. Fix-forward or restore from volume backup.

## Recovery verification

- **Hard crash:** `docker kill maple-redis` → it restarts within seconds (L1/L2).
- **Soft failure (unhealthy):** break a probe target or pause the service; within ~90–150s autoheal restarts it (L3). Watch `docker logs -f maple-autoheal`.
- **Restart-rate alert:** `ContainerRestartThrashing` (cAdvisor `container_start_time_seconds`, `changes() > 2` in 5m) in the prometheus UI.

## Observability gaps (pre-existing, separate cleanup)

- **alertmanager:** referenced by `prometheus.yml` (`alertmanager:9093`) but only defined in the legacy `docker-compose.observability.yml` overlay; not running. Alerts evaluate in prometheus but are not delivered until alertmanager is wired into the active `docker-compose.yml`.
- **node-exporter:** scraped by `prometheus.yml` but only defined in the legacy overlay; not running. System metrics alerts (`HighCpuUsage`, etc.) have no data until it is wired in.

## Backup checklist

- Named volumes: `postgres_data`, `minio_data`, `kafka_data`, `redis_data`, `loki_data`, `grafana_data`, `prometheus_data`.
- `/opt/maple/secrets/` (SA keys).
- Coolify resource configs (export from UI).
