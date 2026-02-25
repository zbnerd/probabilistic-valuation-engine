---
id: GR-RESILIENCE-004
category: backend/resilience
severity: critical
keywords: [Redis, Sentinel, ReadMode, READONLY, Redisson, Failover]
languages: [java, kotlin]
---

# Redis Sentinel ReadMode Configuration Guardrail

## 개요

Redis Sentinel Failover 시 발생하는 **READONLY 에러**를 방지하기 위해 `ReadMode.MASTER` 설정을 필수로 적용합니다. Slave 승격 후 Redisson이 구 Master 주소를 계속 사용하여 Slave에 쓰기를 시도하는 문제를 방지합니다.

> **설계 근거:** P0 #77 인시던트에서 Redis Failover 시 READONLY 에러가 발생하여 애플리케이션 오작동이 발생했습니다. ReadMode.MASTER 설정으로 이 문제를 완전히 차단했습니다.

## DON'T (안티패턴)

### 1. ReadMode.SLAVE 사용 (READONLY 에러 발생)

```java
// Bad - Slave에서 읽기 시도 (READONLY 에러)
config.useSentinelServers()
    .setReadMode(ReadMode.SLAVE)  // 기본값
    .setMasterConnectionPoolSize(64);

// 문제: Master가 Slave로 강등되면 READONLY 에러 발생
// redis.clients.jedis.exceptions.JedisConnectionException:
//   READONLY You can't write against a replica.
```

**위험성:**
- Failover 후 구 Master가 Slave가 됨
- Redisson이 캐시된 구 Master 주소로 쓰기 시도
- READONLY 에러로 장애 확산

### 2. Latency 모드 사용

```java
// Bad - Latency 모드 (최소 지연 노드 선택)
config.useSentinelServers()
    .setReadMode(ReadMode.LATENCY)
    .setSlaveConnectionPoolSize(32);

// 문제: 읽기 요청이 Slave로 분산되어 데이터 일관성 문제
// 쓰기 직후 읽기에서 최신 데이터를 확인하지 못함
```

**위험성:**
- Read-After-Write 일관성 보장 불가
- 캐시 갱신 직후 조회 실패
- 사용자에게 오래된 데이터 노출

## DO (베스트 프랙티스)

### 1. ReadMode.MASTER 설정 (모든 읽기에서 Master 사용)

```java
// Good - 모든 읽기를 Master에서 수행
config.useSentinelServers()
    .setReadMode(ReadMode.MASTER)  // 핵심 설정
    .setMasterConnectionPoolSize(64)
    .setMasterConnectionMinimumIdleSize(24);

// 효과:
// - Failover 후 새 Master만 사용 → READONLY 에러 완전 차단
// - Read-After-Write 일관성 보장
// - Slave 장애 시 읽기 영향 없음
```

### 2. RedissonConfig 전체 설정

```java
// Good - Redisson Sentinel 설정 (P0 #77 해결)
@Component
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        config.useSentinelServers()
            // Master 정보
            .setMasterName("mymaster")
            .addSentinelAddress("redis-sentinel-1:26379")

            // READONLY 에러 방지 (핵심)
            .setReadMode(ReadMode.MASTER)

            // Topology 즉시 업데이트
            .setScanInterval(1000)

            // DNS 안정성
            .setDnsMonitoringInterval(5000)

            // 재연결 및 타임아웃
            .setRetryAttempts(3)
            .setRetryInterval(1500)
            .setTimeout(3000)
            .setConnectTimeout(10000)

            // Connection Pool
            .setMasterConnectionPoolSize(64)
            .setMasterConnectionMinimumIdleSize(24)
            .setFailedSlaveCheckInterval(3000);

        return Redisson.create(config);
    }
}
```

### 3. Health Check로 ReadMode 확인

```java
// Good - Actuator Health Check로 ReadMode 확인
@RestController
@RequiredArgsConstructor
public class RedisHealthCheckController {

    private final RedissonClient redissonClient;

    @GetMapping("/actuator/redis-readmode")
    public ResponseEntity<Map<String, String>> checkReadMode() {
        RedissonClient client = (RedissonClient) redissonClient;
        SentinelConnectionManager cm =
            (SentinelConnectionManager) client.getConnectionManager();

        ReadMode readMode = cm.getCfg().getReadMode();

        Map<String, String> response = Map.of(
            "readMode", readMode.name(),
            "status", readMode == ReadMode.MASTER ? "OK" : "WARNING"
        );

        return ResponseEntity.ok(response);
    }
}
```

### 4. Failover 테스트로 READONLY 에러 검증

```java
// Good - Failover 테스트
@Test
@DisplayName("Sentinel Failover 시 READONLY 에러 미발생 검증")
void failoverShouldNotCauseReadonlyError() {
    // Given
    RMap<String, String> map = redissonClient.getMap("test");
    String key = "failover-test";

    // When - Master 장애 시뮬레이션
    simulateFailover();

    // Then - READONLY 에러 없이 쓰기 성공
    assertThatCode(() -> map.put(key, "value"))
        .doesNotThrowAnyException();

    assertThat(map.get(key)).isEqualTo("value");
}

private void simulateFailover() {
    // Docker Compose로 Master 장애 시뮬레이션
    // docker-compose exec redis-master redis-cli DEBUG SLEEP 30
}
```

### 5. 모니터링 메트릭 수집

```java
// Good - READONLY 에러 모니터링
@Component
public class RedisErrorMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter readonlyErrorCounter;

    public RedisErrorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.readonlyErrorCounter = Counter.builder("redis.readonly.errors")
            .description("Redis READONLY error count")
            .register(meterRegistry);
    }

    @EventListener
    public void onRedisError(ExceptionEvent event) {
        if (event.getCause() instanceof JedisConnectionException) {
            String message = event.getCause().getMessage();
            if (message != null && message.contains("READONLY")) {
                readonlyErrorCounter.increment();
                log.error("Redis READONLY error detected", event.getCause());
            }
        }
    }
}
```

## ReadMode 비교

| ReadMode | 동작 | 장점 | 단점 | 사용 여부 |
|----------|------|------|------|-----------|
| **SLAVE** | Slave에서 읽기 | Master 부하 분산 | READONLY 에러, 일관성 문제 | ❌ 금지 |
| **MASTER** | Master에서 읽기 | 일관성 보장, READONLY 에러 방지 | Master 부하 집중 | ✅ 권장 |
| **LATENCY** | 최소 지연 노드 선택 | 낮은 지연 | 일관성 보장 어려움 | ❌ 금지 |

## Failover 시나리오

```
┌─────────────────────────────────────────────────────────────┐
│  [정상 상태]                                                  │
│  Master (redis-master:6379) → 쓰기/읽기                      │
│  Slave (redis-slave:6379) → 복제만                          │
└─────────────────────────────────────────────────────────────┘
                            ↓
                    [Master 장애 발생]
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  [Failover 진행]                                             │
│  Sentinel 3대 합의 (quorum 2)                                │
│  Slave → Master 승격 (1-2초 소요)                           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  [ReadMode.SLAVE 사용 시 - 문제 발생]                        │
│  Redisson이 구 Master 주소로 쓰기 시도                       │
│  구 Master는 Slave가 됨 → READONLY 에러 발생!                │
│  ❌ "READONLY You can't write against a replica."           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  [ReadMode.MASTER 사용 시 - 정상 동작]                       │
│  Redisson이 항상 Master 주소로 쓰기/읽기 시도                │
│  scanInterval(1초)으로 새 Master 감지                        │
│  ✅ 모든 요청이 새 Master로 전달 → READONLY 에러 없음        │
└─────────────────────────────────────────────────────────────┘
```

## 출처

### 문서
- `docs/03_Technical_Guides/redis-ha-architecture.md` Section 6.2.1: Redisson Sentinel 설정 강화

### ADR
- `docs/01_ADR/ADR-006-redis-lock-lease-timeout-ha.md` - Redis Failover 안정성 개선

### 코드 (Evidence)
- `src/main/java/maple/expectation/config/RedissonConfig.java` (lines 45-65)

### 테스트
- `src/test/java/maple/expectation/chaos/nightmare/N01_Redis_Sentinel_Failover_Test.java`
- `src/test/java/maple/expectation/chaos/nightmare/N02_Redis_Network_Partition_Test.java`

## 검증 명령어

```bash
# ReadMode.MASTER 설정 확인
grep -r "ReadMode.MASTER" src/main/java --include="*.java"

# RedissonConfig 설정 확인
grep -A 20 "useSentinelServers" src/main/java/maple/expectation/config/RedissonConfig.java

# Failover 테스트 실행
./gradlew test --tests "*Sentinel*Test"

# READONLY 에러 로그 확인
grep -i "readonly" logs/application.log

# Actuator health check
curl -s http://localhost:8080/actuator/redis-readmode | jq
```
