---
id: GR-ARCH-001
category: architecture
severity: critical
keywords: [Architecture, TieredCache, SingleFlight, CircuitBreaker, GZIP, HA, Observability]
---

# System Architecture Guardrails

## Overview

probabilistic-valuation-engine은 719 RPS 처리량, 1,000+ 동시 사용자를 지원하는 고가용성 분산 시스템 아키텍처를 따릅니다. 모든 아키텍처 결정은 **증거 기반(Evidence-based)**으로 검증되어야 합니다.

---

## GR-ARCH-001: TieredCache 필수 사용

### DON'T (안티패턴)

```java
// 안티패턴 1: L2(Redis)만 사용
@Cacheable(value = "equipment")
public EquipmentData getEquipment(String ocid) {
    return apiClient.fetchEquipment(ocid);  // 매번 Redis 네트워크 호출
}

// 안티패턴 2: 캐시 스탬프 발생 허용
public EquipmentData getEquipment(String ocid) {
    if (!cache.containsKey(ocid)) {  // Race Condition 가능
        EquipmentData data = apiClient.fetchEquipment(ocid);
        cache.put(ocid, data);
    }
    return cache.get(ocid);
}
```

### DO (베스트 프랙티스)

```java
// Good: TieredCache (L1 Caffeine + L2 Redis)
private final TieredCacheManager tieredCache;

public EquipmentData getEquipment(String ocid) {
    return tieredCache.get("equipment", ocid, () -> {
        // L1 MISS -> L2 MISS -> SingleFlight 로드
        return fetchFromNexonApi(ocid);
    });
}
```

**핵심 규칙:**
- L1(Caffeine): 5분 TTL, 로컬 메모리, < 1ms 응답
- L2(Redis): 10분 TTL, 분산 캐시, < 5ms 응답
- L2 HIT 시 L1 백필(Backfill) 필수
- SingleFlight 패턴으로 캐시 스탬프 방지

### 출처
- [architecture.md](../../00_Start_Here/architecture.md) - Section 3: Cache Architecture

---

## GR-ARCH-002: SingleFlight 패턴 필수

### DON'T (안티패턴)

```java
// 안티패턴: 동일 요청 중복 계산
public EquipmentData calculate(String ocid) {
    return cache.computeIfAbsent(ocid, key -> {
        // 100개의 동시 요청이면 100번의 API 호출
        return expensiveCalculation(key);
    });
}
```

### DO (베스트 프랙티스)

```java
// Good: SingleFlight로 중복 계산 방지
private final SingleFlightExecutor<String, EquipmentData> singleFlight;

public EquipmentData calculate(String ocid) {
    return singleFlight.execute(ocid, () -> {
        // 100개의 동시 요청이도 1번의 API 호출만 실행
        return expensiveCalculation(ocid);
    });
}
```

**핵심 규칙:**
- 동일 키에 대한 동시 요청은 단일 실행으로 통합
- Leader만 계산 실행, Follower는 결과 대기
- 99% 중복 제거율 목표 (N01 테스트 검증)

### 출처
- [architecture.md](../../00_Start_Here/architecture.md) - Section 2: Data Flow Diagram

---

## GR-ARCH-003: Circuit Breaker + Fallback 필수

### DON'T (안티패턴)

```java
// 안티패턴 1: 외부 API 타임아웃 없음
public EquipmentData fetchFromNexon(String ocid) {
    return webClient.get()  // 무한 대기 가능
        .uri("/api/character/" + ocid)
        .retrieve()
        .body(EquipmentData.class);
}

// 안티패턴 2: Fallback 없이 Circuit Breaker만 사용
@CircuitBreaker(name = "nexonApi")
public EquipmentData fetchWithCircuitBreaker(String ocid) {
    return apiClient.fetchEquipment(ocid);
    // OPEN 시 예외만 던지고, 대체 안내 없음
}
```

### DO (베스트 프랙티스)

```java
// Good: Resilience4j 스택 + Fallback
private final NexonApiFallbackService fallbackService;

public EquipmentData fetchFromNexon(String ocid) {
    return Try.ofCallable(() -> circuitBreaker.executeSupplier(
        () -> TimeLimiter.decorateFutureSupplier(
            timeout.ofSeconds(10),
            () -> webClient.get().uri("/" + ocid).retrieve().bodyToMono(EquipmentData.class)
        )
    ))
    .recover(throwable -> fallbackService.getFromDbOrApi(ocid))  // MySQL Fallback
    .get();
}
```

**핵심 규칙:**
- TimeLimiter: 10초 타임아웃 (28초 Nexon API 제한)
- CircuitBreaker: 50% 실패율 시 OPEN, 5분 쿨다운
- Fallback: MySQL DB + GZIP 압축 데이터 조회
- 실패 시 Discord 알림 + 보상 로그 기록

### 출처
- [architecture.md](../../00_Start_Here/architecture.md) - Section 5: Resilience Architecture

---

## GR-ARCH-004: GZIP 압축 필수

### DON'T (안티패턴)

```java
// 안티패턴: JSON 그대로 저장 (350KB/문서)
@Entity
public Equipment {
    @Column(columnDefinition = "TEXT")
    private String jsonData;  // 스토리지 낭비
}
```

### DO (베스트 프랙티스)

```java
// Good: GZIP 압축 (35KB/문서, 90% 절감)
@Entity
public Equipment {
    @Column(columnDefinition = "LONGBLOB")
    private byte[] dataGzip;

    @Convert(converter = GzipConverter.class)
    public String getDataJson() {
        return GzipUtils.decompress(this.dataGzip);
    }
}
```

**핵심 규칙:**
- 모든 장비 데이터는 GZIP 압축 후 LONGBLOB 저장
- 압축률 목표: 90% 이상 (350KB → 35KB)
- @Converter 패턴으로 투명한 압축/해제
- Redis 캐시에도 GZIP+Base64 인코딩 저장

### 출처
- [architecture.md](../../00_Start_Here/architecture.md) - Section 6: GZIP Compression Flow

---

## GR-ARCH-005: Redis HA 아키텍처 필수

### DON'T (안티패턴)

```yaml
# 안티패턴: 단일 Redis (SPOF)
redis:
  host: localhost
  port: 6379
```

### DO (베스트 프랙티스)

```yaml
# Good: Master-Slave + Sentinel x3
redis:
  sentinel:
    master: mymaster
    nodes:
      - host: sentinel1:26379
      - host: sentinel2:26380
      - host: sentinel3:26381
  # 쿼럼 2/3: 과반수 합의 시 자동 Failover
```

**핵심 규칙:**
- Master-Slave 복제: 읽기 분산 가능
- Sentinel 3개: 쿼럼 2/3로 분할 장애(Split Brain) 방지
- 자동 Failover: Master 장애 시 < 30초 Slave 승격
- Redisson Client: 자동 재연결 +拓扑 发现

### 출처
- [architecture.md](../../00_Start_Here/architecture.md) - Section 4: Redis HA Architecture

---

## GR-ARCH-006: Observability 스택 필수

### DON'T (안티패턴)

```java
// 안티패턴: 로그만 남기고 모니터링 없음
log.info("Equipment fetched: {}", ocid);
```

### DO (베스트 프랙티스)

```java
// Good: Prometheus 메트릭 + Loki 로그 통합
@Timed(value = "equipment.fetch", percentiles = {0.5, 0.95, 0.99})
public EquipmentData fetch(String ocid) {
    Counter.builder("equipment.cache.miss")
        .tag("ocid", ocid)
        .register(meterRegistry)
        .increment();

    log.info("Fetched equipment for ocid={}", ocid);  // Loki4j로 전송

    return data;
}
```

**핵심 규칙:**
- Prometheus: JVM, HTTP, Cache, Circuit Breaker 메트릭
- Loki: 구조화된 로그 (MDC TraceId 포함)
- Grafana: 대시보드 시각화 + 알림 룰
- Slow Query Log: MySQL 슬로우 쿼리 자동 감지

### 출처
- [architecture.md](../../00_Start_Here/architecture.md) - Section 7: Observability Stack

---

## GR-ARCH-007: 보안 필터 체인 필수

### DON'T (안티패턴)

```java
// 안티패턴: 인증 없는 API
@GetMapping("/api/v2/characters/{ign}")
public ResponseEntity<?> getCharacter(@PathVariable String ign) {
    return ResponseEntity.ok(service.getCharacter(ign));  // 무단 접근 가능
}
```

### DO (베스트 프랙티스)

```java
// Good: Security Filter Chain
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) {
    http
        .addFilterBefore(new RateLimitingFilter(), ChannelProcessingFilter.class)
        .addFilterBefore(new JwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new MDCFilter(), JwtAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/public/**").permitAll()
            .requestMatchers("/api/v2/characters/*/like").authenticated()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
        );
    return http.build();
}
```

**핵심 규칙:**
- Rate Limiter: IP/사용자별 요청 제한 (Bucket4j)
- JWT: HMAC512 서명, 24h 유효기간
- MDC: TraceId로 요청 추적
- 관리자 API: hasRole(ADMIN) 필수

### 출처
- [architecture.md](../../00_Start_Here/architecture.md) - Section 8: Security Architecture

---

## Verification Commands

```bash
# TieredCache 검증
redis-cli --scan --pattern 'equipment:*' | wc -l  # L2 key count
curl -s http://localhost:8080/actuator/metrics/cache.gets | jq

# SingleFlight 효율 검증
curl -s http://localhost:8080/actuator/metrics/singleflight.deduplication | jq

# Circuit Breaker 상태 검증
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'

# GZIP 압축률 검증
mysql -u root -p -e "SELECT AVG(LENGTH(data_gzip))/AVG(LENGTH(data_json)) FROM equipment;"

# Redis HA 검증
redis-cli -p 26379 sentinel masters | grep mymaster
```

---

## Evidence Links

- [architecture.md](../../00_Start_Here/architecture.md) - 전체 시스템 아키텍처
- [WRK Final Summary](../../05_Reports/Portfolio_Enhancement_WRK_Final_Summary.md) - 719 RPS 성능 증거
- [N01 Thundering Herd Test](../../02_Chaos_Engineering/06_Nightmare/Results/N01-thundering-herd-result.md) - SingleFlight 검증
- [N19 Recovery Report](../../05_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md) - Outbox 복구 검증
