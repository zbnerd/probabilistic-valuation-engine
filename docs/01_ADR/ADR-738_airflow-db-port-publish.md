# ADR-738: airflow DB 접근 repair (host 유지 + airflow-db port publish)

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1435, #1428, #1429, ADR-737

---

## 1. Background / Problem

### Background

- airflow scheduler/webserver 는 host-network 로 구동(compose 는 bridge 선언이나 runtime host override). app connections 은 localhost(host 가 published port 로 app 도달).
- airflow-db 는 bridge `probabilistic-valuation-engine_maple-network`, 포트 미퍼블리시.
- `AIRFLOW__DATABASE__SQL_ALCHEMY_CONN` = `postgresql+psycopg2://airflow:airflow@airflow-db:5432/airflow`.

### Problem

- host-network scheduler/webserver 에서 `airflow-db` Docker DNS 미해석(`socket.gaierror`) → metadata DB 단절 → scheduler "No alive jobs found" unhealthy.
- bridge 통일(최초 구상)은 grill(critic opus) 결과 2 BLOCKER 유발: loki/grafana/promtail strand + connection-recreate race.

### Goal

- 최소 변경으로 DB 도달 repair + autoheal 활성화. app connections·DAG 코드 불변.

---

## 2. Decision

> host-network 유지. airflow-db `5433:5432` 퍼블리시(5432 는 maple-postgres 점유 → 5433, 실측 FREE). `SQL_ALCHEMY_CONN` `@airflow-db:5432` → `@localhost:5433`. compose 에 `network_mode: host` 명시(runtime 일치) + autoheal label + scheduler healthcheck `start_period: 120s`.

```text
docker-compose.airflow.yml:
  airflow-db:    + ports ["5433:5432"], + labels.autoheal
  webserver/scheduler: networks -> network_mode: host, - ports(webserver),
                        + labels.autoheal, SQL_ALCHEMY_CONN @localhost:5433,
                        scheduler healthcheck + start_period: 120s
```

근거: app connections(localhost) 불변 → DAG 영향·strand·race 無. Simplicity First.

---

## 3. Trade-offs

### Sensitivity

* 포트 5433 미사용(실측 FREE) — 타 서비스 5433 사용 시 충돌.
* host-mode webserver 8180 — host 직접 bind(ports 매핑 없이).
* airflow-db recreate metadata 단결(수초, volume 보존).
* morning_chain 다음 발화(06-27 03:00 KST) 간섭 않도록 idle window.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| host 유지 + port publish | 최소 변경, connections 불변, strand/race 无 | bridge 통일의 containerized 정합(차선) |
| start_period 120s | autoheal 기동 중 restart-loop 방지 | 첫 unhealthy 감지 120s 지연 |

### Risk

* metadata DB recreate 중 단결(수초). volume 보존.
* rollback 시 compose revert + `--force-recreate`(scheduler unhealthy 원상복구 — 임시 복귀). 상세 절차: `docs/superpowers/plans/2026-06-26-airflow-network-reconcile.md` Task 5.

### Non-Risk

* airflow-db 데이터 — volume `probabilistic-valuation-engine_airflow_db_data` 보존.
* app 컨테이너 / connections — 변경 無.
* DAG 코드 — `get_external_api_base()` 유도(localhost connections 그대로 유효).
* 관측 스택(loki/grafana/promtail) — network 이동 없음, strand 無.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| scheduler health | unhealthy → healthy | 배포 후 |
| airflow-db 5433 | 미존재 → published | host localhost:5433 → container 5432 |
| DAG trigger → app | 200/202 | connections localhost 불변 |
| autoheal label | 3 컨테이너 부착 | |

### Observed Result (2026-06-26 배포 후 실측)

* **3컨테이너 healthy**: scheduler · webserver · airflow-db 전부 `healthy`.
* **airflow-db 5433 publish**: `5432/tcp -> 0.0.0.0:5433`.
* **DB conn localhost:5433**: scheduler 에서 `SELECT count(*) FROM dag` = 8(이전 단절 → 해결).
* **scheduler healthcheck**: `hostname -f` FQDN 매칭 + single-quote 제거(`$(hostname -f)` sh 확장) → 45s 내 `healthy`. 원래 `'$$(hostname)'` single-quote 가 expansion 차단으로 항상 false-unhealthy 였음(잠재 버그, 본 작업에서 fix).
* **DAG → app**: `requests localhost:8081/actuator/health` = 200 UP(connections localhost 불변).
* **autoheal label**: airflow-db/webserver/scheduler 3컨테이너 `autoheal=true` 부착.
* **morning_chain + per-phase DAGs**: 전부 `is_paused=False`(03:00 schedule 유지). 단 recreate 가 runtime `pip install kafka-python-ng` 설치분을 날려 per-phase DAG import 일시 실패 → 재설치로 복구(image 에 bake 안 됨, skill 에 문서화된 운용 step).

---

## 5. Summary

> airflow-db 5433 port publish + SQL_ALCHEMY_CONN localhost:5433 로 host-network airflow 의 DB 단결을 최소 repair. app connections·DAG 불변, 관측 스택 strand·race 无. autoheal label + start_period 120s 로 자동 복구 활성화.
