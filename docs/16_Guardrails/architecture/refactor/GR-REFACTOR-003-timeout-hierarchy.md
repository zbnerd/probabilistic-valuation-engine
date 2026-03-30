---
id: GR-REFACTOR-003
category: architecture/refactor
severity: critical
keywords: [timeout, hierarchy, zombie-request, coordination]
languages: [java, kotlin]
---

# Timeout Hierarchy - 계층별 타임아웃 정렬

## DON'T (타임아웃 불일치)
- 클라이언트 타임아웃 < 서버 처리 체인으로 Zombie Request 발생
- 각 계층의 타임아웃이 독립적으로 설정되어 정렬되지 않음

```yaml
# Bad: 타임아웃 불일치로 Zombie Request 발생
resilience4j:
  timelimiter:
    configs:
      default:
        timeout-duration: 28s  # 상한

redis:
  timeout: 3s  # ❌ 너무 짧음

spring:
  datasource:
    hikari:
      connection-timeout: 30s  # 연결 타임아웃만 설정

  jpa:
    properties:
      jakarta.persistence.query.timeout: 5000ms  # ❌ 너무 짧음
      jakarta.persistence.lock.timeout: 5000ms    # ❌ 너무 짧음
```

**문제:**
- Redis timeout 3s → MySQL lock_wait 10s → TX timeout 5s
- 계층 간 타임아웃이 정렬되지 않아 Zombie Request 발생

## DO (타임아웃 계층 구조 정렬)
- 하위 계층의 타임아웃이 상위 계층보다 **커야** 함
- TimeLimiter(28s) > HTTP(8s) > Redis(8s) > MySQL lock_wait(8s) > TX(10s)

```yaml
# Good: 타임아웃 계층 구조 정렬
resilience4j:
  timelimiter:
    configs:
      default:
        timeout-duration: 28s  # 상한

redis:
  redisson:
    single-server-config:
      timeout: 8000           # 3s → 8s
      connect-timeout: 5000    # 10s → 5s

spring:
  datasource:
    hikari:
      connection-init-sql: "SET SESSION lock_wait_timeout = 8"  # 10 → 8
      connection-timeout: 30s

  jpa:
    properties:
      jakarta.persistence.query.timeout: 10000ms   # 5s → 10s
      jakarta.persistence.lock.timeout: 10000ms     # 5s → 10s
```

```java
// Good: TransactionConfig.java
@Configuration
public class TransactionConfig {
    @Bean
    public JpaTransactionManager transactionManager(DataSource dataSource) {
        JpaTransactionManager manager = new JpaTransactionManager();
        manager.setDataSource(dataSource);
        manager.setDefaultTimeout(10);  // 5 → 10 (MySQL lock_wait 8s보다 여유 있게)
        return manager;
    }
}
```

```kotlin
// Good: RedissonConfig.kt
@Configuration
class RedissonConfig {
    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config.singleServer()
            .setTimeout(8000)        // Issue #225: 3s → 8s
            .setConnectTimeout(5000) // 10s → 5s
        return Redisson.create(config)
    }
}
```

## 타임아웃 계층 다이어그램

```
TimeLimiter: 28s (상한)
├── HTTP: connect 3s + response 5s (× 3회 재시도)
│   └── Redis: timeout 8s
│       └── MySQL: lock_wait 8s
│           └── Transaction: timeout 10s
```

**핵심 원칙:**
1. 상위 계층 타임아웃은 하위 계층 **합산 + 여유값**
2. 하위 계층 간 타임아웃은 **유사 수준**으로 정렬
3. Transaction timeout은 **가장 긴 DB 작업**보다 커야 함

## 출처
- [P1 Nightmare Issues Resolution Report](../../../../05_Reports/05_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md) - Issue #225
- [CLAUDE.md](../../../../CLAUDE.md) - Section 4 (Sequential Thinking)

## Zombie Request 방지

| 증상 | 원인 | 해결 |
|------|------|------|
| 클라이언트 타임아웃 | 서버 타임아웃 < 클라이언트 | 서버 타임아웃 증가 |
| Connection 누적 | @Transactional 내 .join() | 트랜잭션 경계 분리 |
| 연결 누수 | Redis/DB timeout 불일치 | 타임아웃 정렬 |
