# Guardrails - Database

## 개요

데이터베이스 연결 풀, 쿼리 최적화, 그리고 락 경합 방지에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-DB-001 | [Database Connection Pool Guardrails](connection-pool.md) | critical | ConnectionPool, HikariCP, MySQL, LockContention, Sharding |
| GR-DB-002 | [InnoDB Buffer Pool Tuning](innodb-buffer-pool.md) | warning | InnoDB, Buffer-Pool, MySQL, Memory-Tuning, Hit-Rate, Disk-I/O |

## 주요 주제

- **Connection Pool Sizing**: RPS 요구사항에 맞는 풀 크기 계산
- **Hot Row Lock 경합**: 샤딩으로 분산
- **인덱스 최적화**: Full Table Scan 방지
- **Buffer Pool Tuning**: RAM의 60-70% 할당으로 Hit Rate > 99%

## 핵심 규칙

```
# Connection Pool Sizing
Max Pool Size = (RPS × avg_query_time_seconds) + buffer

# Buffer Pool Sizing
Buffer Pool = Total RAM × 0.6 ~ 0.7
Buffer Pool Instances = 1 (if < 4GB), else = Buffer Pool Size / 1GB
```

## Before/After 성능

| 지표 | Before | After | Improvement |
|------|--------|-------|-------------|
| **Connection Pool (1000 RPS)** | 30 | 150 | 5× |
| **Buffer Pool Hit Rate** | < 95% | > 99% | +4% p.p. |
| **Disk I/O (reads/sec)** | 500-1000 | 50-100 | -90% |
| **Avg Query Time** | 50-100ms | 5-10ms | -90% |
| **Hot Row UPDATE** | 200/s | 2,000/s | 10× (sharding) |

## 관련 문서

- [docs/05_Reports/05_02_Cost_Performance/high-traffic-performance-analysis.md](../../05_Reports/05_02_Cost_Performance/high-traffic-performance-analysis.md) - P0/P1 Analysis
- [docs/05_Reports/05_02_Cost_Performance/p1-p2-performance-improvements-report.md](../../05_Reports/05_02_Cost_Performance/p1-p2-performance-improvements-report.md) - InnoDB Tuning
- [docs/05_Reports/05_02_Cost_Performance/COST_PERF_REPORT_N23.md](../../05_Reports/05_02_Cost_Performance/COST_PERF_REPORT_N23.md) - Scale-out Cost Analysis
- [docs/03_Technical_Guides/infrastructure.md](../03_Technical_Guides/infrastructure.md) - Infrastructure Guide
