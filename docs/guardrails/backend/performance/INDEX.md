# Guardrails - Performance

## 개요

성능 튜닝, 스레드 풀 최적화에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-PERF-001 | [Thread Pool Tuning Guardrails](thread-pool-tuning.md) | critical | ThreadPool, ExecutorService, VirtualThreads, Backpressure, RPS |

## 주요 가드레일

### ThreadPool Sizing
- **DON'T**: ThreadPool 사이즈를 트래픽 요구사항에 맞추지 않기
- **DO**: RPS 기반 Connection Pool 계산

### Before/After 성능

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Max RPS | 235 | 1,000 | 4.25× |
| Thread Pool (max) | 8 | 500 | 62.5× |
| Queue Capacity | 200 | 5,000 | 25× |
| P99 Latency | 450ms | <100ms | 4.5× faster |

### Instance Type Guidelines

| Instance Type | vCPU | RAM | Recommended Max Pool | Queue |
|---------------|------|-----|---------------------|-------|
| **t3.small** | 2 | 2GB | 200-500 | 2,000-5,000 |
| **t3.medium** | 2 | 4GB | 500-1,000 | 5,000-10,000 |
| **t3.large** | 2 | 8GB | 1,000-2,000 | 10,000-20,000 |

## 관련 문서

- [docs/05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md](../../../05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md)
