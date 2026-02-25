---
id: GR-CACHE-006
category: backend/cache
severity: warning
keywords: [Two-Phase-Snapshot, Light-Full, Cache-Optimization, OCID-Lookup]
languages: [java, kotlin]
---

# Two-Phase Snapshot Cache Pattern

## DON'T (안티패턴)

### 1. Full Data 조회 후 캐시 키 생성

```java
// Bad: 모든 데이터를 조회한 후 캐시 확인
public TotalExpectationResponse calculateExpectation(String userIgn) {
    // 1. Full Snapshot으로 Character 조회 (includes all equipment data)
    GameCharacter character = gameCharacterRepository.findByUserIgnWithEquipment(userIgn);

    // 2. 캐시 키 생성 (이미 DB 조회 완료!)
    String cacheKey = generateCacheKey(character.getFingerprint(), character.getOcid());

    // 3. 캐시 확인 (늦음 - 이미 DB 접근 발생)
    return cacheService.getValidCache(cacheKey)
        .orElseGet(() -> calculate(character));
}
```

**문제점:**
- Cache HIT 시에도 불필요한 DB 조회 발생 (JOIN, equipment data 로드)
- 네트워크 왕복 추가 (~6ms)
- DB Connection Pool 낭비

### 2. 캐시 키 생성을 위한 전체 데이터 로드

```java
// Bad: fingerprint 생성을 위해 모든 데이터 필요
public String generateCacheKey(String userIgn) {
    GameCharacter character = gameCharacterRepository.findByUserIgn(userIgn);
    String fingerprint = FingerprintGenerator.generate(character.getAllEquipment());
    return "expectation:v1:" + fingerprint + ":" + tableHash;
}
```

**영향:**
- 1000 RPS 시 캐시 HIT율 90%라도 100회/초 불필요한 DB 조회
- p99 지연시간 증가

## DO (베스트 프랙티스)

### 1. Light Snapshot 먼저 조회

```java
// Good: Light Snapshot으로 최소 필드만 조회
public TotalExpectationResponse calculateExpectation(String userIgn) {
    // Phase 1: Light Snapshot (ocid, fingerprint만)
    GameCharacter lightSnapshot = findLightSnapshot(userIgn);

    // Phase 2: 캐시 키 생성 및 조회
    String cacheKey = generateCacheKey(lightSnapshot.getFingerprint(), tableHash);
    return cacheService.getValidCache(cacheKey)
        .orElseGet(() -> {
            // Cache MISS 시에만 Full Snapshot 조회
            GameCharacter fullSnapshot = findFullSnapshot(lightSnapshot.getOcid());
            return calculate(fullSnapshot);
        });
}

// Light Snapshot: 캐시 키 생성용 최소 필드
@Query("SELECT new maple.expectation.dto.LightSnapshot(gc.id, gc.userIgn, gc.ocid, gc.fingerprint) " +
       "FROM GameCharacter gc WHERE gc.userIgn = :userIgn")
LightSnapshot findLightSnapshot(@Param("userIgn") String userIgn);

// Full Snapshot: 계산용 전체 데이터
@Query("SELECT gc FROM GameCharacter gc LEFT JOIN FETCH gc.equipments WHERE gc.ocid = :ocid")
GameCharacter findFullSnapshot(@Param("ocid") String ocid);
```

### 2. Light/Full 분리 DTO

```java
// Light Snapshot DTO (캐시 키 생성용)
public record LightSnapshot(
    Long id,
    String userIgn,
    String ocid,
    String fingerprint
) {}

// Full Snapshot DTO (계산용) - 필요시에만 로드
@Entity
@NamedEntityGraph(name = "GameCharacter.full",
    attributeNodes = @NamedAttributeNode("equipments"))
public class GameCharacter {
    @Id
    private Long id;
    private String userIgn;
    private String ocid;
    private String fingerprint;
    @OneToMany(fetch = LAZY)
    private List<Equipment> equipments;
    // ...
}
```

### 3. 캐시 조회 최적화 흐름

```java
public CompletableFuture<TotalExpectationResponse> calculateAsync(String userIgn) {
    return CompletableFuture.supplyAsync(() -> {
        // Step 1: Negative Cache 확인 (없는 캐릭터)
        if (ocidNegativeCache.contains(userIgn)) {
            throw new CharacterNotFoundException(userIgn);
        }

        // Step 2: Light Snapshot 조회 (DB 1회, 최소 필드)
        GameCharacter light = repository.findLightSnapshot(userIgn);
        String cacheKey = "expectation:v1:" + light.fingerprint() + ":" + tableHash;

        // Step 3: 결과 캐시 확인
        return expectationCache.getValidCache(cacheKey)
            .map(CompletableFuture::completedFuture)
            .orElseGet(() -> calculateWithFullSnapshot(light));
    }, asyncExecutor);
}

private CompletableFuture<TotalExpectationResponse> calculateWithFullSnapshot(GameCharacter light) {
    return singleFlight.executeAsync(light.ocid(), () -> {
        // Single-Flight 보호된 Full Snapshot 조회
        GameCharacter full = repository.findFullSnapshot(light.ocid());
        return calculator.calculate(full);
    });
}
```

## Before/After 성능

| 시나리오 | Before (Full만) | After (Light→Full) | 개선 |
|---------|-----------------|-------------------|------|
| **Cache HIT** | DB 조회 + equipment JOIN (~15ms) | Light 조회만 (~6ms) | **-60%** |
| **Cache MISS** | Full 조회 1회 (~15ms) | Light + Full 조회 2회 (~20ms) | -33% (허용) |
| **1000 RPS (90% HIT)** | 100회 Full 조회 | 100회 Light 조회 | **DB 부하 -60%** |

**계산:**
- Before: 1000 RPS × 15ms = 15,000ms DB 작업/초
- After: (100회 × 6ms) + (100회 × 20ms) = 2,600ms DB 작업/초
- **DB 부하 감소: 83%**

## 모니터링 메트릭

```yaml
# Prometheus Metrics
cache_light_snapshot_hit_total:
  description: "Light snapshot cache hit count"
  labels: [user_ign]

cache_full_snapshot_load_total:
  description: "Full snapshot DB load count (should be low)"
  labels: [ocid]

two_phase_cache_hit_ratio:
  description: "Ratio of light hits that avoided full load"
  formula: "cache_light_snapshot_hit_total / (cache_light_snapshot_hit_total + cache_full_snapshot_load_total)"
```

## 캐시 구성 예시

| 캐시 | 키 패턴 | TTL | 용도 |
|------|---------|-----|------|
| `ocidNegativeCache` | `{userIgn}` | 5분 | 존재하지 않는 캐릭터 |
| `ocidCache` | `{userIgn}` | 1시간 | OCID 매핑 |
| `equipment` | `{ocid}` | 15분 | 장비 데이터 (Full) |
| `expectationResult` | `expectation:v{ver}:{fingerprint}:{tableHash}` | 30분 | 기대값 결과 |

## Negative Cache 패턴

```java
// Bad: 없는 캐릭터 매번 DB 조회
public GameCharacter findByUserIgn(String userIgn) {
    return repository.findByUserIgn(userIgn)
        .orElseThrow(() -> new CharacterNotFoundException(userIgn));
}

// Good: Negative Cache로 불필요한 DB 조회 방지
public GameCharacter findByUserIgn(String userIgn) {
    // 1. Negative Cache 확인
    if (ocidNegativeCache.contains(userIgn)) {
        throw new CharacterNotFoundException(userIgn);
    }

    // 2. DB 조회
    return repository.findByUserIgn(userIgn)
        .orElseGet(() -> {
            // 3. 없으면 Negative Cache에 저장
            ocidNegativeCache.put(userIgn, true, Duration.ofMinutes(5));
            throw new CharacterNotFoundException(userIgn);
        });
}
```

## 검증 명령어

```bash
# Light Snapshot vs Full 조회 횟수 확인
curl -s http://localhost:8080/actuator/metrics/cache.light.snapshot.hits | jq '.measurements'
curl -s http://localhost:8080/actuator/metrics/cache.full.snapshot.loads | jq '.measurements'

# Two-Phase Cache Hit Ratio 계산
rate(cache_light_snapshot_hit_total[5m]) /
rate(cache_full_snapshot_load_total[5m])

# Negative Cache Hit 확인
rate(ocid_negative_cache_hit_total[5m])
```

## 출처

- [expectation-cache-sequence.md](../../../04_Sequence_Diagrams/expectation-cache-sequence.md) - Phase 2: Light Snapshot
- [infrastructure.md](../../../03_Technical_Guides/infrastructure.md) Section 17: TieredCache & Cache Stampede Prevention
- EVIDENCE-004 from high-traffic-performance-analysis.md
