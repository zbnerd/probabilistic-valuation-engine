---
id: GR-DB-001
category: database
severity: critical
keywords: [ConnectionPool, HikariCP, MySQL, LockContention, Sharding]
---

# Database Connection Pool Guardrails

## DON'T (안티패턴)

### 1. Connection Pool을 RPS 요구사항에 맞추지 않기
```java
// BAD: 1000 RPS인데 Connection Pool=30
config.setMaximumPoolSize(POOL_SIZE);  // = 30
config.setMinimumIdle(POOL_SIZE);      // = 30
```

**영향 분석:**
- Redis 장애 시 MySQL Named Lock으로 fallback
- Lock 보유 시간: ~100ms
- 30 connections × (1000ms / 100ms) = **300 req/s 최대**
- 1000 RPS 중 **700 req/s 대기/타임아웃**

### 2. Hot Row Lock 경합 (likeCount UPDATE)
```java
// BAD: 단일 row에 집중 업데이트
@Query("UPDATE GameCharacter c SET c.likeCount = c.likeCount + :count WHERE c.userIgn = :userIgn")
void incrementLikeCount(@Param("userIgn") String userIgn, @Param("count") Long count);
```

**영향:**
- 인기 캐릭터에 1000 RPS 좋아요 요청
- 단일 row에 Exclusive Lock 경합
- Lock 보유: 1-5ms → **200 update/s 실제 처리량**
- **80% 요청이 Lock 대기**

### 3. 인덱스 미적용 Full Table Scan
```java
// BAD: user_ign 인덱스 없음
@Query("""
    SELECT ees FROM EquipmentExpectationSummary ees
    JOIN GameCharacter gc ON gc.id = ees.gameCharacterId
    WHERE gc.userIgn = :userIgn
    ORDER BY ees.presetNo
    """)
List<EquipmentExpectationSummary> findAllByUserIgn(@Param("userIgn") String userIgn);
```

**영향:**
- `game_character.user_ign` 인덱스 미존재 시 Full Table Scan
- 100K+ rows: **100ms+ 쿼리 시간**

## DO (베스트 프랙티스)

### 1. RPS 기반 Connection Pool 계산
```java
// GOOD: RPS와 평균 Lock 시간 기반 계산
// Required = (RPS × avg_lock_hold_time) + buffer
// = (1000 × 0.1) + 50 = 150 connections

@Bean
@ConfigurationProperties(prefix = "spring.datasource.hikari.lock")
public DataSource lockDataSource() {
    HikariConfig config = new HikariConfig();
    config.setMaximumPoolSize(150);  // 5× 증가
    config.setMinimumIdle(50);       // 33% of max
    config.setConnectionTimeout(3000); // 3s timeout
    config.setIdleTimeout(600000);    // 10 min idle timeout
    return new HikariDataSource(config);
}
```

**계산 공식:**
```
Max Pool Size = (RPS × avg_query_time_seconds) + buffer
             = (1000 × 0.1) + 50
             = 150 connections

Buffer = 20% of Max Pool (for burst handling)
```

### 2. Hot Row Sharding (likeCount)
```sql
-- GOOD: 10개 샤드로 분산
ALTER TABLE game_character
ADD COLUMN like_count_shard_0 BIGINT DEFAULT 0,
ADD COLUMN like_count_shard_1 BIGINT DEFAULT 1,
...
ADD COLUMN like_count_shard_9 BIGINT DEFAULT 9;

-- 해시 기반 샤드 선택
UPDATE game_character
SET like_count_shard_{hash % 10} = like_count_shard_{hash % 10} + ?
WHERE user_ign = ?;

-- 조회 시 합산
SELECT (like_count_shard_0 + ... + like_count_shard_9) AS total_likes
FROM game_character WHERE user_ign = ?;
```

**Java 구현:**
```java
public void incrementLikeCount(String userIgn, long count) {
    int shardId = Math.abs(userIgn.hashCode()) % 10;
    String shardColumn = "like_count_shard_" + shardId;
    repository.incrementShardLikeCount(userIgn, shardColumn, count);
}
```

### 3. 인덱스 생성
```sql
-- GOOD: user_ign 인덱스 추가
CREATE INDEX idx_game_character_user_ign
ON game_character(user_ign);

CREATE INDEX idx_ees_character_preset
ON equipment_expectation_summary(game_character_id, preset_no);
```

## Monitoring & Alerts

```prometheus
# Connection Pool 고갈 경고
ALERT ConnectionPoolExhaustion
  IF hikaricp_connections_active / hikaricp_connections_max > 0.9
  FOR 1m
  SEVERITY critical

  ANNOTATIONS {
    summary = "Connection pool nearly exhausted",
    description = "Active: {{$value}} / Max: {{$max}}",
    runbook = "https://docs/runbooks/connection-pool.html"
  }

# Connection 대기 시간 모니터링
ALERT ConnectionWaitTimeHigh
  IF hikaricp_connections_creation_seconds > 0.1
  FOR 2m
  SEVERITY warning

# Lock 경합 감지
ALERT LockContentionDetected
  IF rate(mysql_lock_waits_total[1m]) > 50
  SEVERITY warning
```

## Verification Commands

```bash
# Connection Pool 상태 확인
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq '.measurements'

# Connection Pool 사용률
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.max | jq '.measurements'

# DB Lock 대기 상태
mysql> SHOW ENGINE INNODB STATUS\G
# Look for "Lock wait seconds" in TRANSACTIONS section
```

## Before/After Performance

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Connection Pool | 30 | 150 | 5× |
| Max Throughput (with lock) | 300 req/s | 1,500 req/s | 5× |
| Hot Row UPDATE | 200/s | 2,000/s | 10× (sharding) |
| Lock Wait Time | 5-50ms | <1ms | 50× faster |
| Query Time (no index) | 100ms+ | <5ms | 20× faster |

## Connection Pool Sizing Guidelines

| Target RPS | Avg Query Time | Min Pool | Max Pool |
|------------|----------------|----------|----------|
| **100** | 50ms | 10 | 20 |
| **500** | 50ms | 30 | 50 |
| **1,000** | 100ms | 50 | 150 |
| **5,000** | 100ms | 250 | 750 |

Formula: `Max Pool = (RPS × query_time) × 1.5 (buffer)`

## Sharding Strategy Comparison

| Strategy | Complexity | Write Amplification | Lock Contention | Use Case |
|----------|------------|---------------------|-----------------|----------|
| **Single Row** | Low | 1× | Critical (100% collision) | <100 RPS |
| **Modulo Sharding (10)** | Medium | 1× | Low (10% collision) | <1,000 RPS |
| **Consistent Hashing** | High | 1.2× | Very Low | Scale-out |
| **CDC + Counter Table** | Very High | 2× | None | Eventual consistency |

## References

- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
- [MySQL InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- [Database Sharding Patterns](https://www.pingcap.com/blog/hot-row-optimization-in-distributed-databases/)

## 출처
- [docs/05_Reports/05_02_Cost_Performance/high-traffic-performance-analysis.md](../../../05_Reports/05_02_Cost_Performance/high-traffic-performance-analysis.md)
- Evidence ID: EVIDENCE-002, EVIDENCE-005, EVIDENCE-010
