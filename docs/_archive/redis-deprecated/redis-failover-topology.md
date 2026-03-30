<!-- 
<-- DEPRECATED --> This document references Redis/Redisson infrastructure completely removed. See ADR-022 (Redis removal), ADR-024 (MySQL removal). Redis replaced by PostgreSQL (advisory locks, UNLOGGED tables, NOTIFY/LISTEN).
 -->

---
id: GR-RESILIENCE-005
category: backend/resilience
severity: critical
keywords: [Redis, Sentinel, Failover, Topology, Redisson, ScanInterval, DNS]
languages: [java, kotlin]
---

# Redis Failover Topology Update Guardrail

## 개요

Redis Sentinel Failover 후 클라이언트가 새 Master를 즉시 감지하도록 **Topology 업데이트 설정**을 필수로 적용합니다. `scanInterval`, `dnsMonitoringInterval`, `failedSlaveCheckInterval` 등의 설정으로 Failover 시간을 30초에서 1-2초로 단축합니다.

> **설계 근거:** P0 #77 인시던트에서 Failover 후 Topology 업데이트 지연으로 30초 동안 장애가 지속되었습니다. scanInterval 1초 설정으로 1-2초 내 새 Master 감지를 달성했습니다.

## DON'T (안티패턴)

### 1. scanInterval 기본값 사용 (느린 감지)

```java
// Bad - 기본 scanInterval (5000ms)
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis-sentinel-1:26379")
    // scanInterval 설정 없음 → 기본값 5000ms

// 문제: Failover 후 5초 동안 구 Master 주소 사용
// 읽기/쓰기 실패 지속
```

**위험성:**
- Failover 후 5초간 장애 지속
- 사용자 요청 타임아웃
- 서비스 가용성 저하

### 2. DNS 모니터링 미사용 (UnknownHostException)

```java
// Bad - DNS 모니터링 미설정
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis-sentinel-1:26379")
    // dnsMonitoringInterval 설정 없음

// 문제: Docker Compose 재시작 후 DNS 변경 감지 못함
// redis.clients.jedis.exceptions.JedisConnectionException:
//   java.net.UnknownHostException: redis-master
```

**위험성:**
- 컨테이너 재시작 후 DNS 변경 미감지
- 연결 실패 지속
- 수동 재시작 필요

### 3. failedSlaveCheckInterval 미설정

```java
// Bad - 장애 Slave 제거 간격 미설정
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis-sentinel-1:26379")
    // failedSlaveCheckInterval 설정 없음

// 문제: 장애 Slave가 Connection Pool에 계속 남음
// 연결 시도 타임아웃으로 성능 저하
```

**위험성:**
- 장애 Slave로 연결 시도 반복
- 타임아웃으로 지연 증가
- Connection Pool 낭비

### 4. 재시도 설정 부족

```java
// Bad - 재시도 설정 없음
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis-sentinel-1:26379")
    // retryAttempts 설정 없음 → 기본값 3회
    // retryInterval 설정 없음 → 기본값 1500ms

// 문제: 일시적 네트워크 오류 시 빠르게 실패
```

**위험성:**
- 일시적 네트워크 오류로 장애 확산
- 재시도 부족으로 복구 불가

## DO (베스트 프랙티스)

### 1. scanInterval 1초 설정 (빠른 Failover 감지)

```java
// Good - 1초마다 Master/Slave 구성 스캔
config.useSentinelServers()
    .setScanInterval(1000)  // 핵심: 1초마다 Topology 업데이트
    .setMasterName("mymaster")
    .addSentinelAddress("redis-sentinel-1:26379");

// 효과:
// - Failover 후 1-2초 내 새 Master 감지
// - 기존 30초에서 1-2초로 단축
// - 서비스 가용성 99.9% 달성
```

### 2. DNS 모니터링 5초 설정 (UnknownHostException 방지)

```java
// Good - 5초마다 DNS 갱신
config.useSentinelServers()
    .setDnsMonitoringInterval(5000)  // 핵심: DNS 캐시 주기 갱신
    .setMasterName("mymaster")
    .addSentinelAddress("redis-sentinel-1:26379");

// 효과:
// - Docker Compose 재시작 후 DNS 변경 감지
// - UnknownHostException 방지
// - 컨테이너 동적 확장 지원
```

### 3. failedSlaveCheckInterval 3초 설정

```java
// Good - 3초마다 장애 Slave 확인
config.useSentinelServers()
    .setFailedSlaveCheckInterval(3000)  // 핵심: 장애 Slave 빠르게 제거
    .setMasterName("mymaster")
    .addSentinelAddress("redis-sentinel-1:26379");

// 효과:
// - 장애 Slave를 Connection Pool에서 즉시 제거
// - 타임아웃으로 인한 지연 방지
// - 연결 풀 효율성 개선
```

### 4. 전체 RedissonConfig 설정 (P0 #77 해결)

```java
// Good - 완전한 Redisson Sentinel 설정
@Component
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        config.useSentinelServers()
            // Master 정보
            .setMasterName("mymaster")
            .addSentinelAddress(
                "redis://redis-sentinel-1:26379",
                "redis://redis-sentinel-2:26379",
                "redis://redis-sentinel-3:26379"
            )

            // 핵심: Topology 즉시 업데이트
            .setScanInterval(1000)                    // 1초마다 Master/Slave 스캔

            // 핵심: READONLY 에러 방지
            .setReadMode(ReadMode.MASTER)

            // 핵심: DNS 안정성
            .setDnsMonitoringInterval(5000)           // 5초마다 DNS 갱신

            // 재연결 및 타임아웃
            .setRetryAttempts(3)                      // 재시도 3회
            .setRetryInterval(1500)                   // 재시도 간격 1.5초
            .setTimeout(3000)                         // 명령 타임아웃 3초
            .setConnectTimeout(10000)                 // 연결 타임아웃 10초

            // Connection Pool
            .setMasterConnectionPoolSize(64)
            .setMasterConnectionMinimumIdleSize(24)
            .setSlaveConnectionPoolSize(64)
            .setSlaveConnectionMinimumIdleSize(24)

            // 핵심: 장애 Slave 빠르게 제거
            .setFailedSlaveCheckInterval(3000);       // 3초마다 확인

        return Redisson.create(config);
    }
}
```

### 5. Failover 시간 측정 테스트

```java
// Good - Failover 시간 측정
@Test
@DisplayName("Sentinel Failover 시간 1-2초 검증")
void failoverShouldCompleteWithin2Seconds() {
    // Given
    RMap<String, String> map = redissonClient.getMap("failover-test");
    String key = "test-key";
    map.put(key, "initial-value");

    // When - Master 장애 시뮬레이션
    long startTime = System.currentTimeMillis();
    simulateMasterFailover();

    // Then - 2초 내 새 Master로 쓰기 성공
    await().atMost(2, TimeUnit.SECONDS)
        .untilAsserted(() -> {
            map.put(key, "after-failover");
            assertThat(map.get(key)).isEqualTo("after-failover");
        });

    long duration = System.currentTimeMillis() - startTime;
    log.info("Failover completed in {}ms", duration);
    assertThat(duration).isLessThan(2000);
}

private void simulateMasterFailover() {
    // Docker Compose로 Master 장애 시뮬레이션
    // docker-compose stop redis-master
}
```

### 6. Topology 모니터링 엔드포인트

```java
// Good - Topology 상태 모니터링
@RestController
@RequestMapping("/actuator")
public class RedisTopologyController {

    private final RedissonClient redissonClient;

    @GetMapping("/redis-topology")
    public ResponseEntity<Map<String, Object>> getTopology() {
        RedissonClient client = (RedissonClient) redissonClient;
        SentinelConnectionManager cm =
            (SentinelConnectionManager) client.getConnectionManager();

        SentinelServersConfig cfg = cm.getCfg();

        Map<String, Object> topology = Map.of(
            "scanInterval", cfg.getScanInterval(),
            "readMode", cfg.getReadMode().name(),
            "dnsMonitoringInterval", cfg.getDnsMonitoringInterval(),
            "failedSlaveCheckInterval", cfg.getFailedSlaveCheckInterval(),
            "masterName", cfg.getMasterName(),

            // 현재 Master 주소
            "currentMaster", getCurrentMasterAddress(cm)
        );

        return ResponseEntity.ok(topology);
    }

    private String getCurrentMasterAddress(SentinelConnectionManager cm) {
        return cm.getSentinels().stream()
            .filter(s -> s.getAddr().toString().contains("master"))
            .findFirst()
            .map(s -> s.getAddr().toString())
            .orElse("unknown");
    }
}
```

### 7. 메트릭 수집

```java
// Good - Failover 메트릭 수집
@Component
public class RedisFailoverMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong lastFailoverTime = new AtomicLong(0);

    @EventListener
    public void onFailover(FailoverEvent event) {
        long duration = System.currentTimeMillis() - lastFailoverTime.get();
        lastFailoverTime.set(System.currentTimeMillis());

        meterRegistry.counter("redis.failover.count",
            "master", event.getNewMasterAddress()
        ).increment();

        meterRegistry.timer("redis.failover.duration").record(duration, TimeUnit.MILLISECONDS);

        log.warn("Redis failover detected: newMaster={}, duration={}ms",
            event.getNewMasterAddress(), duration);
    }
}
```

## 설정 파라미터 비교

| 파라미터 | 기본값 | 권장값 | 설명 | 효과 |
|----------|--------|--------|------|------|
| `scanInterval` | 5000ms | 1000ms | Master/Slave 스캔 주기 | Failover 감지 시간 5초 → 1초 |
| `dnsMonitoringInterval` | -1 (비활성) | 5000ms | DNS 갱신 주기 | UnknownHostException 방지 |
| `failedSlaveCheckInterval` | 3000ms | 3000ms | 장애 Slave 확인 주기 | 빠른 장애 Slave 제거 |
| `retryAttempts` | 3 | 3 | 재시도 횟수 | 일시적 오류 복구 |
| `retryInterval` | 1500ms | 1500ms | 재시도 간격 | 재시도 간 대기 시간 |
| `connectTimeout` | 10000ms | 10000ms | 연결 타임아웃 | 연결 대기 시간 |
| `timeout` | 3000ms | 3000ms | 명령 타임아웃 | Redis 명령 대기 시간 |

## Failover 타임라인

```
[T=0ms]      Master 장애 발생
             ↓
[T=0ms]      Sentinel 3대 감지 시작 (down-after-milliseconds 1000ms)
             ↓
[T=1000ms]   Sentinel 합의 (quorum 2/3达成)
             ↓
[T=1000ms]   Slave → Master 승격 명령
             ↓
[T=1000ms]   승격 완료, 새 Master 준비됨
             ↓
[T=1000ms]   Redisson scanInterval 실행 (1초마다)
             ↓
[T=1000-2000ms] Redisson이 새 Master 발견 및 연결
             ↓
[T=1000-2000ms] Topology 업데이트 완료
             ↓
[T=1000-2000ms] 정상 쓰기/읽기 재개
             ↓
             총 Failover 시간: 1-2초
```

## 출처

### 문서
- `docs/03_Technical_Guides/redis-ha-architecture.md` Section 6.2.1: Redisson Sentinel 설정 강화

### ADR
- `docs/01_ADR/ADR-006-redis-lock-lease-timeout-ha.md` - Redis Failover 안정성 개선

### 코드 (Evidence)
- `src/main/java/maple/expectation/config/RedissonConfig.java` (lines 45-75)

### 테스트
- `src/test/java/maple/expectation/chaos/nightmare/N01_Redis_Sentinel_Failover_Test.java`
- `src/test/java/maple/expectation/chaos/nightmare/N02_Redis_Network_Partition_Test.java`

### 보고서
- `docs/05_Reports/P0_Issues_Resolution_Report_2026-01-20.md` (P0 #77 해결)

## 검증 명령어

```bash
# scanInterval 설정 확인
grep -r "setScanInterval" src/main/java --include="*.java"

# dnsMonitoringInterval 설정 확인
grep -r "setDnsMonitoringInterval" src/main/java --include="*.java"

# 전체 RedissonConfig 설정 확인
grep -A 30 "useSentinelServers" src/main/java/maple/expectation/config/RedissonConfig.java

# Failover 테스트 실행
./gradlew test --tests "*Sentinel*Failover*Test"

# Actuator topology 엔드포인트 확인
curl -s http://localhost:8080/actuator/redis-topology | jq

# Failover 메트릭 확인
curl -s http://localhost:8080/actuator/metrics/redis.failover.count | jq
```
