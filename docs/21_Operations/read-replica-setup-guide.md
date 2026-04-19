# Read Replica 설정 가이드 — V5 Query Server

**목적:** V5 Query Server(Next.js)가 Primary DB가 아닌 Read Replica에서 조회하도록 PostgreSQL streaming replication 설정.

---

## 1. Primary DB 설정

### postgresql.conf

```conf
wal_level = replica
max_wal_senders = 5
max_replication_slots = 5
```

### pg_hba.conf

```conf
# Replication connection
host    replication     replication     <REPLICA_IP>/32    scram-sha-256
```

### Replication slot 생성

```sql
SELECT pg_create_physical_replication_slot('query_server_replica');
```

### 복제 사용자 생성 (없는 경우)

```sql
CREATE ROLE replication WITH REPLICATION LOGIN PASSWORD '<password>';
```

---

## 2. Replica 인스턴스 설정

### Replica PostgreSQL 설치

동일 Vultr 리전에 새 인스턴스 생성 후 PostgreSQL 설치.

### Base backup

```bash
pg_basebackup \
  -h <PRIMARY_IP> \
  -U replication \
  -D /var/lib/postgresql/data \
  -Fp -Xs -P -R
```

`-R` 옵션이 `standby.signal` 파일과 `primary_conninfo`를 자동 생성.

### postgresql.conf (Replica)

```conf
hot_standby = on
```

---

## 3. 연결 확인

### Replica에서 복제 상태 확인

```sql
SELECT pg_is_in_recovery();  -- t (true = replica)
```

### Primary에서 복제 상태 확인

```sql
SELECT client_addr, state, sent_lsn, replay_lsn
FROM pg_stat_replication;
```

### 데이터 복제 확인

```sql
-- Primary에 데이터 삽입 후 Replica에서 조회
SELECT * FROM character_expectation_read_model LIMIT 1;
```

### Replica lag 확인

```sql
-- Primary
SELECT pg_current_wal_lsn();

-- Replica
SELECT pg_last_wal_replay_lsn();

-- Lag (bytes)
SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), pg_last_wal_replay_lsn());
```

**목표:** lag < 1MB (일반적으로 < 1초)

---

## 4. SSL 설정 (Vercel → Replica)

Vercel Serverless에서 Replica로 연결 시 SSL 필수.

### Replica postgresql.conf

```conf
ssl = on
ssl_cert_file = '/etc/ssl/certs/server.crt'
ssl_key_file = '/etc/ssl/private/server.key'
```

### 자체 서명 인증서 (테스트용)

```bash
openssl req -new -x509 -days 365 -nodes -text \
  -out /etc/ssl/certs/server.crt \
  -keyout /etc/ssl/private/server.key \
  -subj "/CN=<REPLICA_IP>"
```

### 연결 문자열

```env
REPLICA_DATABASE_URL=postgresql://maple:<password>@<REPLICA_IP>:5432/maple_expectation?sslmode=require
```

---

## 5. Vercel 환경 변수 설정

Vercel 프로젝트 설정 → Environment Variables:

| 변수 | 값 |
|------|-----|
| `REPLICA_DATABASE_URL` | `postgresql://maple:<password>@<REPLICA_IP>:5432/maple_expectation?sslmode=require` |
| `CACHE_TTL_SECONDS` | `3600` |
| `MAX_STALE_SECONDS` | `5` |

---

## 6. 장애 대응

### Replica 장애 시

1. Vercel 환경 변수를 Primary DB로 임시 변경
2. Replica 복구 후 다시 전환
3. `max_connections` 확인 — Vercel 인스턴스 × 2 = 필요 connection 수

### Replica lag 증가 시

```sql
-- Primary에서 lag 원인 확인
SELECT * FROM pg_stat_replication;
SELECT * FROM pg_replication_slots;
```

원인: 대량 write, 네트워크 지연, 디스크 I/O 병목.

---

## 7. 모니터링 (Phase 2)

### Prometheus 지표

```sql
-- replica_lag_bytes
SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), pg_last_wal_replay_lsn());
```

### 알림 임계값

- **Warning:** lag > 10MB
- **Critical:** lag > 100MB 지속 30초
