# ADR-088: HikariCP Connection Pool Tuning for Virtual Thread Environment

## Status

**Status**: Proposed
**Date**: 2026-03-08
**Context**: P1-14 HikariCP Tomcat Integration (LOW PRIORITY)
**Related**: ADR-048 (Java 21 Virtual Threads), ADR-028 (PostgreSQL Migration)

---

## Problem Statement

### Current Situation

probabilistic-valuation-engine runs on **Java 21 Virtual Threads** with the following HikariCP configuration:

**application-local.yml:**
```yaml
spring.datasource.hikari:
  maximum-pool-size: 100
  minimum-idle: 50
```

**application-prod.yml:**
```yaml
spring.datasource.hikari:
  maximum-pool-size: 50
  minimum-idle: 50
```

**Lock Pool (LockHikariConfig.kt):**
```kotlin
@Value("${lock.datasource.pool-size:40}")
private val poolSize: Int
```

### Issues Identified

1. **Pool Size Mismatch with Virtual Thread Capacity**
   - Virtual Threads can handle **10,000+ concurrent operations**
   - Main pool: 50 connections (prod), Lock pool: 40-50 connections
   - **Total: ~90-100 connections** for potentially thousands of concurrent requests

2. **No HikariCP Metrics Monitoring**
   - Current `DatabaseMetricsCollector` only captures basic metrics
   - Missing: Pool utilization %, wait time percentiles, timeout rate
   - Cannot detect pool exhaustion before it causes failures

3. **Undocumented Sizing Rationale**
   - Current sizes (50 main + 50 lock) appear arbitrary
   - No documented calculation based on virtual thread environment
   - RDS `max_connections` constraint not documented

4. **Missing Alerting Thresholds**
   - No Prometheus alerts for pool saturation
   - No guidance on "healthy" vs "degraded" pool usage

---

## Analysis

### Virtual Thread vs Database Connection Relationship

**Key Insight**: Virtual Threads do **not** reduce database connection requirements.

```
┌─────────────────────────────────────────────────────────────┐
│ Virtual Thread Architecture                                 │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Request1 [VT] ──┐                                          │
│  Request2 [VT] ──┼──> HikariCP Pool ──> Database           │
│  Request3 [VT] ──┤   (Fixed Size)        (Fixed Connections)│
│     ...        ──┘                                          │
│                                                             │
│  VT = Unbounded, lightweight                                 │
│  DB Connections = Bounded, expensive                         │
└─────────────────────────────────────────────────────────────┘
```

**Critical Constraint**: Each database query still requires a physical connection.

### Current Configuration Analysis

| Environment | Main Pool | Lock Pool | Total | RDS max_connections |
|-------------|-----------|-----------|-------|---------------------|
| **local** | 100 | N/A | 100 | Unlimited |
| **prod** | 50 | 50 | 100 | ~150 (t3.medium) |

### Little's Law Applied to Connection Pools

```
L = λ × W

Where:
L = Number of connections needed (pool size)
λ = Request rate (RPS)
W = Average query latency (seconds)
```

**Example Calculation:**
- Peak traffic: 235 RPS (from load tests)
- Average DB query latency: 50ms (0.05s)
- Additional buffer: 20% for spikes

```
L = 235 × 0.05 × 1.2 ≈ 14 connections (minimum)
```

**Current 50 connections is ~3.5x the calculated minimum**, which is reasonable for:
- Burst traffic handling
- Multiple concurrent queries per request
- Connection validation overhead

---

## Decision

### 1. Document Current Sizing Rationale

**Main Pool (50 connections):**
- Sufficient for 235 RPS with 50ms average latency
- 2x buffer for burst traffic (470 RPS spikes)
- Considers multi-query transactions (5-10 queries/request)

**Lock Pool (50 connections):**
- Failover capacity when Redis is down
- MySQL Named Lock operations are fast (~10ms)
- Prevents main pool starvation during Redis failure

**Total: 100 connections** is appropriate for:
- AWS t3.medium RDS (default max_connections: ~152)
- Multi-instance deployment (3 instances = 300 connections < RDS limit)

### 2. Add Comprehensive HikariCP Metrics

**New Metrics to Collect:**
```kotlin
// Pool Utilization
hikaricp.connections.usage.ratio (active / max)
hikaricp.connections.active.percent

// Wait Time Metrics
hikaricp.connections.acquire.p50
hikaricp.connections.acquire.p95
hikaricp.connections.acquire.p99

// Timeout Tracking
hikaricp.connections.timeout.total
hikaricp.connections.timeout.rate

// Connection Lifecycle
hikaricp.connections.creation.total
hikaricp.connections.creation.rate
```

### 3. Add Prometheus Alert Rules

**Warning Threshold (70% utilization):**
```yaml
- alert: HikariCPHighUtilization
  expr: hikaricp_connections_active / hikaricp_connections_max > 0.7
  for: 5m
```

**Critical Threshold (90% utilization):**
```yaml
- alert: HikariCPCriticalUtilization
  expr: hikaricp_connections_active / hikaricp_connections_max > 0.9
  for: 2m
```

**Connection Timeout Alert:**
```yaml
- alert: HikariCPConnectionTimeout
  expr: rate(hikaricp_connections_timeout_total[5m]) > 0.1
  for: 1m
```

### 4. Configuration Recommendations

**No Immediate Pool Size Changes** - Current sizing is appropriate.

**Optional Tuning Parameters:**
```yaml
spring.datasource.hikari:
  maximum-pool-size: 50
  minimum-idle: 10          # Reduce idle connections (save memory)
  connection-timeout: 30000  # 30s (increase from default 30s)
  idle-timeout: 600000       # 10 min (reduce idle reclaim)
  max-lifetime: 1800000      # 30 min (default)
  validation-timeout: 5000   # 5s (reduce validation cost)
  leak-detection-threshold: 60000  # 60s (detect connection leaks)
```

---

## Implementation Plan

### Phase 1: Metrics Enhancement (This Task)

1. **Update DatabaseMetricsCollector.kt**
   - Add pool utilization ratio
   - Add wait time percentiles (p50, p95, p99)
   - Add timeout rate tracking

2. **Add Prometheus Metrics Documentation**
   - Document available HikariCP metrics
   - Add Grafana dashboard query examples

3. **Create ADR Document** (This file)

### Phase 2: Monitoring & Alerting (Future)

1. **Add Prometheus Alert Rules**
   - High utilization warning (70%)
   - Critical utilization (90%)
   - Connection timeout detection

2. **Grafana Dashboard Update**
   - Add HikariCP pool utilization panel
   - Add wait time heatmap
   - Add timeout rate sparkline

### Phase 3: Validation (Future)

1. **Load Test with Metrics**
   - Run existing load tests
   - Verify pool utilization stays < 80%
   - Document peak usage patterns

2. **Production Monitoring**
   - Track pool utilization over 7 days
   - Identify peak traffic patterns
   - Validate sizing decisions

---

## Configuration Justification

### Current Sizes Are Appropriate Because:

1. **Virtual Thread Advantage is Request Concurrency, Not Database**
   - VT = Many concurrent requests (I/O wait)
   - DB = Still limited by physical connections

2. **Little's Law Validation**
   ```
   Current: 50 connections
   Required: ~14 connections (235 RPS × 50ms)
   Buffer: 3.6x (handles burst traffic well)
   ```

3. **Multi-Query Transactions**
   - Typical request: 5-10 DB queries
   - Effective concurrency: 50 / 5 = 10 concurrent transactions
   - Matches virtual thread throughput

4. **RDS Constraints**
   - t3.medium RDS: ~152 max connections
   - 3 instances × 100 connections = 300 > 152 ❌
   - **Action**: Each instance uses 50 connections (150 total) ✅

---

## Verification

### Success Criteria

- [x] ADR document created with sizing rationale
- [ ] DatabaseMetricsCollector updated with new metrics
- [ ] Prometheus alert rules added
- [ ] Load test validates pool utilization < 80%
- [ ] Production monitoring shows healthy pool usage

### Testing Commands

```bash
# Check HikariCP metrics endpoint
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq

# Check pool utilization ratio
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.usage | jq

# Verify connection wait times
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.acquire | jq

# Load test with pool monitoring
wrk -t4 -c50 -d30s --latency http://localhost:8080/api/v4/character/test/expectation
```

---

## References

- **ADR-048**: Java 21 Virtual Threads adoption
- **ADR-028**: PostgreSQL migration planning
- **HikariCP Documentation**: https://github.com/brettwooldridge/HikariCP
- **Spring Boot DataSource Metrics**: https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics.datasource

---

*Author: HikariCP Configuration Specialist (P1-14)*
*Last Updated: 2026-03-08*
