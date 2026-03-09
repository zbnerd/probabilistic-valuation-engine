# 로컬 DB 접속 가이드

> **Branch**: `v2/postgresql-redesign`
> **Related Issue**: #547

## Overview

이 문서는 로컬 개발 환경에서 PostgreSQL 데이터베이스에 접속하는 방법을 안내합니다.

---

## 사전 요구사항

### 1. Docker 설치

```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install docker.io docker-compose-plugin

# macOS (Homebrew)
brew install docker docker-compose

# Windows (Docker Desktop)
# https://www.docker.com/products/docker-desktop 에서 설치
```

### 2. 환경 변수 설정

`.env` 파일을 프로젝트 루트에 생성:

```bash
# PostgreSQL
DB_USERNAME=maple
DB_PASSWORD=maple123
DB_SCHEMA_NAME=maple_expectation

# JWT
JWT_SECRET=your-jwt-secret-key-here
FINGERPRINT_SECRET=your-fingerprint-secret-here
```

---

## PostgreSQL 시작하기

### 1. 컨테이너 시작

```bash
# PostgreSQL만 시작
docker compose -f docker-compose.postgres.yml up -d postgres

# 또는 백그라운드에서 시작
docker compose -f docker-compose.postgres.yml up -d

# 상태 확인
docker compose -f docker-compose.postgres.yml ps
```

### 2. 초기화 확인

```bash
# PGMQ 확장 확인
docker exec maple-postgres psql -U maple -d maple_expectation -c "SELECT extname FROM pg_extension WHERE extname = 'pgmq';"

# 큐 목록 확인
docker exec maple-postgres psql -U maple -d maple_expectation -c "SELECT * FROM pgmq.list_queues();"

# 예상 출력:
#  queue_name | created_at
# ------------+-------------------------
#  v4_buffer_queue | 2026-03-09 ...
#  v5_event_queue | 2026-03-09 ...
#  donation_outbox_queue | 2026-03-09 ...
```

### 3. 컨테이너 중지

```bash
docker compose -f docker-compose.postgres.yml down

# 볼륨까지 삭제 (초기화)
docker compose -f docker-compose.postgres.yml down -v
```

---

## 연결 문자열

### JDBC

```
jdbc:postgresql://localhost:5432/maple_expectation
```

### Spring Boot 설정

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/maple_expectation
    username: maple
    password: maple123
    driver-class-name: org.postgresql.Driver
```

### 환경 변수

```bash
export DB_URL=jdbc:postgresql://localhost:5432/maple_expectation
export DB_USERNAME=maple
export DB_PASSWORD=maple123
```

---

## IDE/도구 연결

### IntelliJ IDEA

1. **Database** 패널 열기 (`View → Tool Windows → Database`)
2. **+** 버튼 → **Data Source** → **PostgreSQL**
3. 연결 정보 입력:
   - Host: `localhost`
   - Port: `5432`
   - Database: `maple_expectation`
   - User: `maple`
   - Password: `maple123`
4. **Test Connection** → **OK**

### DBeaver

1. **새 데이터베이스 연결** (Ctrl+Shift+N)
2. **PostgreSQL** 선택
3. 연결 정보 입력:
   - Host: `localhost`
   - Port: `5432`
   - Database: `maple_expectation`
   - Username: `maple`
   - Password: `maple123`
4. **연결 테스트** → **완료**

### psql CLI

```bash
# 컨테이너 내부에서 접속
docker exec -it maple-postgres psql -U maple -d maple_expectation

# 로컬 psql 설치 후 접속
psql -h localhost -p 5432 -U maple -d maple_expectation
```

### pgAdmin 4

1. pgAdmin 실행
2. **Add New Server**
   - General → Name: `MapleExpectation Local`
   - Connection:
     - Host: `localhost`
     - Port: `5432`
     - Database: `maple_expectation`
     - Username: `maple`
     - Password: `maple123`
3. **Save**

---

## 프로필 전환

### MySQL (기존)

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

### PostgreSQL (신규)

```bash
./gradlew bootRun --args='--spring.profiles.active=pglocal'
```

### IntelliJ Run Configuration

1. **Run → Edit Configurations**
2. **Active profiles**: `pglocal`
3. **Environment variables**: `.env` 파일 경로 또는 직접 입력

---

## 유용한 쿼리

### PGMQ 큐 관리

```sql
-- 큐에 메시지 추가
SELECT pgmq.send('v4_buffer_queue', '{"character": "TestChar", "value": 100}');

-- 큐에서 메시지 읽기 (읽고 삭제)
SELECT * FROM pgmq.pop('v4_buffer_queue');

-- 큐에서 메시지 읽기 (읽고 유지)
SELECT * FROM pgmq.read('v4_buffer_queue', 30, 10);  -- 30초 VT, 최대 10개

-- 큐 길이 확인
SELECT pgmq.length('v4_buffer_queue');

-- 큐 삭제
SELECT pgmq.drop_queue('v4_buffer_queue');
```

### UNLOGGED 테이블 확인

```sql
-- UNLOGGED 테이블 목록
SELECT relname FROM pg_class WHERE relpersistence = 'u';

-- 버퍼 테이블 내용 확인
SELECT * FROM equipment_expectation_buffer LIMIT 10;
SELECT * FROM character_like_buffer LIMIT 10;
```

### 성능 모니터링

```sql
-- 활성 연결 확인
SELECT count(*) FROM pg_stat_activity WHERE datname = 'maple_expectation';

-- 실행 중인 쿼리
SELECT pid, query, state, query_start
FROM pg_stat_activity
WHERE datname = 'maple_expectation' AND state = 'active';

-- 테이블 크기 확인
SELECT
    relname AS table_name,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC
LIMIT 10;
```

---

## 문제 해결

### 1. 컨테이너가 시작되지 않음

```bash
# 로그 확인
docker compose -f docker-compose.postgres.yml logs postgres

# 포트 충돌 확인
sudo lsof -i :5432

# 포트 변경 (docker-compose.postgres.yml)
ports:
  - "5433:5432"  # 로컬 포트 5433 사용
```

### 2. 연결 거부

```bash
# 컨테이너 실행 확인
docker ps | grep maple-postgres

# 컨테이너 내부 연결 테스트
docker exec maple-postgres pg_isready -U maple -d maple_expectation
```

### 3. PGMQ 확장 없음

```bash
# 수동으로 확장 설치
docker exec maple-postgres psql -U maple -d maple_expectation -c "CREATE EXTENSION IF NOT EXISTS pgmq;"

# 큐 재생성
docker exec maple-postgres psql -U maple -d maple_expectation -c "SELECT pgmq.create('v4_buffer_queue');"
```

### 4. 권한 오류

```bash
# 권한 부여
docker exec maple-postgres psql -U postgres -d maple_expectation -c "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO maple;"
docker exec maple-postgres psql -U postgres -d maple_expectation -c "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO maple;"
```

### 5. 데이터 초기화

```bash
# 볼륨 삭제 후 재시작
docker compose -f docker-compose.postgres.yml down -v
docker compose -f docker-compose.postgres.yml up -d postgres
```

---

## 참고 자료

- [PostgreSQL 공식 문서](https://www.postgresql.org/docs/current/)
- [PGMQ GitHub](https://github.com/tembo-io/pgmq)
- [Spring Data JPA - PostgreSQL](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#reference)
