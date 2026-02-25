# Guardrails - Database

## 개요

데이터베이스 연결 풀, 쿼리 최적화, 그리고 락 경합 방지에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-DB-001 | [Database Connection Pool Guardrails](connection-pool.md) | critical | ConnectionPool, HikariCP, MySQL, LockContention, Sharding |

## 주요 주제

- **Connection Pool Sizing**: RPS 요구사항에 맞는 풀 크기 계산
- **Hot Row Lock 경합**: 샤딩으로 분산
- **인덱스 최적화**: Full Table Scan 방지

## 핵심 규칙

```
Max Pool Size = (RPS × avg_query_time_seconds) + buffer
```

## 관련 문서

- [docs/05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md](../../05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md)
- [docs/03_Technical_Guides/infrastructure.md](../03_Technical_Guides/infrastructure.md)
