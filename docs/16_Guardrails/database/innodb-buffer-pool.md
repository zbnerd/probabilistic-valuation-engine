---
id: GR-DB-002
category: database
severity: warning
keywords: [InnoDB, Buffer-Pool, MySQL, Memory-Tuning, Hit-Rate, Disk-I/O]
languages: [sql, java]
---

# InnoDB Buffer Pool Tuning

## DON'T (안티패턴)

### 1. 기본값 128MB 사용

```ini
# Bad: MySQL 기본값 128MB
[mysqld]
innodb_buffer_pool_size = 128M  # t3.small (2GB RAM) 기준 너무 작음
```

**영향:**
- Hit Rate < 95%
- Disk I/O 빈번 발생
- Query 응답 시간 50-100ms 증가

### 2. 너무 큰 Buffer Pool 설정

```ini
# Bad: OS + 애플리케이션 메모리 고려 안 함
[mysqld]
innodb_buffer_pool_size = 8G  # 16GB RAM 서버에서 과다 설정
```

**영향:**
- OOM (Out of Memory) 위험
- 스와핑 발생으로 성능 악화

## DO (베스트 프랙티스)

### 1. RAM의 60-70% 할당

```ini
# Good: t3.small (2GB RAM) 기준
[mysqld]
# 전체 RAM의 60% 할당
innodb_buffer_pool_size = 1200M

# 2GB 미만은 분할 불필요
innodb_buffer_pool_instances = 1

# 로그 버퍼 (트랜잭션 커밋 최적화)
innodb_log_buffer_size = 16M

# 성능 vs 안정성 균형 (2: 커밋 시 OS 버퍼에 기록, 1초마다 디스크 플러시)
innodb_flush_log_at_trx_commit = 2

# 이중 버퍼링 방지
innodb_flush_method = O_DIRECT
```

### 2. 인스턴스 크기별 설정

| 인스턴스 | RAM | Buffer Pool | Instances | Flush Method |
|---------|-----|-------------|-----------|--------------|
| **t3.small** | 2GB | 1200M (60%) | 1 | O_DIRECT |
| **t3.medium** | 4GB | 2800M (70%) | 1 | O_DIRECT |
| **t3.large** | 8GB | 5600M (70%) | 2 | O_DIRECT |
| **m5.large** | 8GB | 5600M (70%) | 2 | O_DIRECT |
| **m5.xlarge** | 16GB | 11G (70%) | 2-4 | O_DIRECT |

### 3. Buffer Pool Instances 계산

```ini
# 1GB 미만: 1개 (분할 비효율)
# 1GB ~ 4GB: 1개
# 4GB ~ 8GB: 2개
# 8GB ~ 16GB: 4개
# 16GB ~ 32GB: 8개

# 예시: 32GB RAM 서버
innodb_buffer_pool_size = 22G      # 70%
innodb_buffer_pool_instances = 8   # 1GB per instance
```

**분할 이유:**
- LRU 리스트 경합 감소
- Concurrent 처리량 향상

### 4. Docker Compose 설정

```yaml
# docker-compose.yml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: password
    volumes:
      # my.cnf 마운트
      - ./config/mysql/my.cnf:/etc/mysql/conf.d/my.cnf:ro
      - mysql-data:/var/lib/mysql
    ports:
      - "3306:3306"
```

```ini
# config/mysql/my.cnf
[mysqld]
# t3.small 최적화
innodb_buffer_pool_size = 1200M
innodb_buffer_pool_instances = 1
innodb_log_buffer_size = 16M
innodb_flush_log_at_trx_commit = 2
innodb_flush_method = O_DIRECT

# 모니터링
performance_schema = ON
```

## Before/After 성능

| 지표 | Before (128MB) | After (1200MB) | 개선 |
|------|----------------|----------------|------|
| **Buffer Pool Hit Rate** | < 95% | > 99% | **+4% p.p.** |
| **Disk I/O (reads/sec)** | 500-1000 | 50-100 | **-90%** |
| **Avg Query Time** | 50-100ms | 5-10ms | **-90%** |
| **p99 Query Time** | 200-500ms | 20-50ms | **-90%** |

## Hit Rate 모니터링

### SQL 쿼리

```sql
-- 1. Buffer Pool 크기 확인
SHOW VARIABLES LIKE 'innodb_buffer_pool_size';
-- 기대: 1258291200 (1200M)

-- 2. Buffer Pool 인스턴스 확인
SHOW VARIABLES LIKE 'innodb_buffer_pool_instances';
-- 기대: 1

-- 3. Hit Rate 계산
SELECT
  (1 - (
    VARIABLE_VALUE / (
      (SELECT VARIABLE_VALUE
       FROM performance_schema.global_status
       WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests')
    )
  )) * 100 AS hit_rate_percent
FROM performance_schema.global_status
WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads';
-- 기대: > 99%

-- 4. Buffer Pool 상세 통계
SHOW STATUS LIKE 'Innodb_buffer_pool%';
```

### Prometheus 메트릭

```promql
# Buffer Pool Hit Rate
(1 - rate(mysql_global_status_innodb_buffer_pool_reads[5m])
   / rate(mysql_global_status_innodb_buffer_pool_read_requests[5m])) * 100

# 목표: > 99%
```

## 알람 규칙

```prometheus
# Hit Rate 저하 경고
ALERT InnoDBHitRateLow
  IF (1 - rate(mysql_global_status_innodb_buffer_pool_reads[5m])
          / rate(mysql_global_status_innodb_buffer_pool_read_requests[5m])) < 0.95
  FOR 5m
  SEVERITY warning
  ANNOTATIONS {
    summary = "InnoDB Buffer Pool Hit Rate < 95%",
    description = "Current: {{$value}}%",
    runbook = "https://docs/runbooks/innodb-hit-rate.html"
  }
```

## 진단 가이드

### Hit Rate < 99% 원인 분석

| 원인 | 증상 | 해결 |
|------|------|------|
| **Buffer Pool 너무 작음** | Reads/sec 높음 | 크기 증설 |
| **Full Table Scan** | Read requests 급증 | 인덱스 추가 |
| **워킹 셋 너무 큼** | 데이터가 Pool에 못 담음 | Pool 크기 증설 또는 데이터 파티셔닝 |

### Full Table Scan 감지

```sql
-- Full Table Scan 횟수 확인
SHOW STATUS LIKE 'Handler_read%';

-- Handler_read_rnd_next: Full Table Scan 시 증가
-- Handler_read_first: Full Index Scan 시 증가
-- 정상: Handler_read_next (Index lookup)가 높아야 함
```

## OS 레벨 모니터링

```bash
# MySQL 프로세스 메모리 사용량
ps aux | grep mysqld
# RSS (Resident Set Size) 확인

# Swap 사용 확인 (Swapping 발생 시 성능 악화)
free -h
vmstat 1 5
# si/so (swap in/out)이 0이어야 함

# Disk I/O 모니터링
iostat -x 1
# %iowait가 5% 미만이어야 함
```

## InnoDB Flush Method 비교

| 설정 | 설명 | 장점 | 단점 |
|------|------|------|------|
| **fdatasync (기본)** | fsync() 호출 | 호환성 좋음 | 이중 버퍼링 |
| **O_DIRECT** | Direct I/O | 이중 버퍼링 방지 | some filesystems에서 issue |
| **O_DSYNC** | O_SYNC | 안정성 | 느림 |

**권장:** O_DIRECT (Linux 기준)

## flush_log_at_trx_commit 설정

| 값 | 설명 | 성능 | 안정성 | 사용 사례 |
|----|------|------|--------|-----------|
| **0** | 1초마다 write + flush | 최고 | 낮음 (1초 손실) | 벌크 import |
| **1** | 각 커밋마다 write + flush | 낮음 | 최고 | Financial |
| **2** | 각 커밋마다 write, 1초마다 flush | 높음 | 중간 (1초 손실) | **권장 (Web)** |

## 검증 명령어

```bash
# MySQL 컨테이너 접속
docker exec -it mysql_container mysql -u root -p

# Buffer Pool 크기 확인
mysql> SHOW VARIABLES LIKE 'innodb_buffer_pool_size';
+-------------------------+------------+
| Variable_name           | Value      |
+-------------------------+------------+
| innodb_buffer_pool_size | 1258291200 |
+-------------------------+------------+

# Hit Rate 계산 (스크립트)
mysql> SELECT
  ROUND((1 - (
    VARIABLE_VALUE /
    (SELECT VARIABLE_VALUE
     FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests')
  )) * 100, 2) AS hit_rate_percent
FROM performance_schema.global_status
WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads';
+------------------+
| hit_rate_percent |
+------------------+
|            99.42 |
+------------------+
```

## 출처

- [p1-p2-performance-improvements-report.md](../../../05_Reports/05_02_Cost_Performance/p1-p2-performance-improvements-report.md) Phase 5: #208 InnoDB Buffer Pool 튜닝
- [high-traffic-performance-analysis.md](../../../05_Reports/05_02_Cost_Performance/high-traffic-performance-analysis.md) EVIDENCE-007
- MySQL 8.0 Reference Manual: https://dev.mysql.com/doc/refman/8.0/en/innodb-buffer-pool.html
