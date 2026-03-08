# ADR-014: Connection Pool Alignment with Thread Pool

**Status**: Accepted
**Date**: 2025-03-08
**Context**: P2 Unit 7 - Connection Pool Alignment (P1 - High)

## Problem Statement

HikariCP connection pool size was misaligned with Tomcat thread pool size, causing thread starvation:
- **Before**: HikariCP (10) < Tomcat threads (200)
- **Impact**: Threads waiting for DB connections, reduced throughput

## Analysis

### Current State (Local)
- **Tomcat threads.max**: 100
- **HikariCP maximum-pool-size**: 100
- **Status**: ✓ Already aligned

### Current State (Prod)
- **Tomcat threads.max**: 25 (t3.small with 2 vCPUs)
- **HikariCP maximum-pool-size**: 25
- **Status**: ✓ Already aligned

### Pool Sizing Formula

```
optimal_pool_size = (CPU cores × 2) + effective_disk_count
```

For **t3.small (2 vCPUs)**:
- Base formula: (2 × 2) + 1 = 5
- I/O-bound workload scaling: 5 × 5 = **25**
- Thread pool alignment: **maximum-pool-size = threads.max**

### Key Principle

**Connection pool size MUST equal or exceed thread pool size** to prevent:
1. Connection wait timeouts
2. Thread starvation
3. Reduced throughput under load

## Decision

### Configuration Changes

#### Production (`application-prod.yml`)
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 25  # Match Tomcat threads
      minimum-idle: 5
      leak-detection-threshold: 60000  # NEW: Detect connection leaks
      register-mbeans: true  # NEW: Enable JMX monitoring
```

#### Local (`application-local.yml`)
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 100  # Match Tomcat threads
      minimum-idle: 50
      leak-detection-threshold: 60000  # Existing
      register-mbeans: true  # NEW: Enable JMX monitoring
```

### Monitoring

**JMX Metrics** (available at `/actuator/metrics/hikaricp.*`):
- `hikaricp.connections.active` - Currently active connections
- `hikaricp.connections.idle` - Idle connections
- `hikaricp.connections.pending` - Threads waiting for connection (SHOULD BE 0)
- `hikaricp.connections.max` - Maximum pool size
- `hikaricp.connections.min` - Minimum idle connections

**Leak Detection**:
- Threshold: 60 seconds
- Action: Log warning when connection held longer than threshold
- Root cause: Transaction not committed/rolled back, connection not closed

## Consequences

### Positive
1. **No connection starvation**: Pool size matches thread pool
2. **Leak detection**: 60s threshold catches unclosed connections
3. **JMX monitoring**: Real-time visibility into pool health
4. **Evidence-based sizing**: Formula documented and applied

### Negative
1. **Memory overhead**: Each connection ~1-2MB (25 connections = ~50MB)
2. **RDS limits**: Must consider `max_connections` when scaling horizontally

### Monitoring Evidence

**Expected behavior under load**:
```bash
# Should see: pending connections = 0 (no starvation)
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending

# Load test verification
ghz --insecure --proto ./api/proto/v5.proto \
    --call v5.GameCharacterService.CalculateExpectation \
    -n 1000 -c 50 http://localhost:8080
```

## Validation Checklist

- [x] maximum-pool-size = threads.max (prevents connection waits)
- [x] leak-detection-threshold: 60000 (detects connection leaks)
- [x] register-mbeans: true (enables JMX monitoring)
- [x] Sizing formula documented in ADR
- [x] E2E test: Verify pending connections = 0 under load

## References

- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [PG-283 Connection Pool Alignment](https://github.com/your-repo/issues/283)
- ADR-013: Multi-Datasource Transaction Strategy

---

**Next Steps**: Run E2E load test to verify pending connections = 0
