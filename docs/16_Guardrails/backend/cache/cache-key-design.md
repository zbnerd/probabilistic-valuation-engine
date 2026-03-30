---
id: GR-CACHE-010
category: backend/cache
severity: info
keywords: [Cache-Key, Versioning, Fingerprint, Hash-Collision, Key-Design, TTL-Strategy]
languages: [java, kotlin]
---

# Cache Key Design & Versioning

## DON'T (안티패턴)

### 1. 버전 없는 캐시 키

```java
// Bad: 버전 정보 없이 ocid만 사용
@Cacheable(value = "equipment", key = "#ocid")
public EquipmentData getEquipment(String ocid) {
    return nexonApiClient.fetchEquipment(ocid);
}
```

**문제점:**
- 데이터 구조 변경 시 기존 캐시와 호환되지 않음
- Deserialization 오류 (ClassCastException, StreamCorruptedException)
- 캐시 무효화 불가 (모든 키 수동 삭제 필요)

### 2. Hash Collision 무시

```java
// Bad: hashCode만 사용으로 충돌 가능
String cacheKey = String.valueOf(equipmentData.hashCode());
```

**영향:**
- 다른 장비 조합이 같은 캐시 키 사용
- 잘못된 데이터 반환 (Data corruption)
- 디버깅 어려움

### 3. 매번 같은 키 생성

```java
// Bad: 소스 데이터가 변경되어도 같은 키
String cacheKey = "equipment:" + ocid;  // 장비 변경 감지 불가
```

**영향:**
- 장비 장착/해제 후에도 같은 캐시 반환
- 정확도 저하

## DO (베스트 프랙티스)

### 1. 버전이 포함된 캐시 키

```java
// Good: 버전 정보 포함
public class CacheKeyGenerator {
    private static final String CACHE_VERSION = "v1";

    public String generateEquipmentKey(String ocid) {
        return String.format("equipment:%s:%s", CACHE_VERSION, ocid);
    }

    public String generateExpectationKey(String fingerprint, long tableHash) {
        return String.format("expectation:%s:%s:%d",
            CACHE_VERSION, fingerprint, tableHash);
    }
}
```

**버전 변경 시:**
```java
// v1 → v2로 변경하면 자동으로 모든 캐시 무효화
private static final String CACHE_VERSION = "v2";  // 이전 v1 캐시는 자연스럽게 만료
```

### 2. Fingerprint 기반 캐시 키

```java
// Good: 데이터 내용 기반 키 생성
public String generateFingerprint(List<Equipment> equipments) {
    // 장비 데이터를 정렬하여 순서 무관하게 키 생성
    List<Equipment> sorted = equipments.stream()
        .sorted(Comparator.comparing(Equipment::getItemId)
                          .thenComparing(Equipment::getPresetNo))
        .toList();

    // 장비 ID와 강화 수치로 해시 생성
    String data = sorted.stream()
        .map(e -> e.getItemId() + ":" + e.getUpgradeLevel())
        .collect(Collectors.joining(","));

    return DigestUtils.sha256Hex(data);  // SHA-256 해시
}
```

### 3. Multi-Level 캐시 키 구조

```java
// Good: 계층별 캐시 키
public class CacheKeys {
    // Level 1: Character ID 기반
    public static String characterOcid(String userIgn) {
        return "character:ocid:v1:" + userIgn;
    }

    // Level 2: OCID 기반 장비 데이터
    public static String equipmentData(String ocid) {
        return "equipment:data:v1:" + ocid;
    }

    // Level 3: Fingerprint 기반 계산 결과
    public static String expectationResult(String fingerprint, long tableHash) {
        return "expectation:result:v2:" + fingerprint + ":" + tableHash;
    }
}
```

### 4. 키 형식 템플릿

| 캐시 레벨 | 키 패턴 | 예시 | 용도 |
|----------|---------|------|------|
| **OCID Mapping** | `{type}:{version}:{identifier}` | `character:ocid:v1:MapleStory` | userIgn → ocid 매핑 |
| **Raw Data** | `{type}:{version}:{ocid}` | `equipment:data:v1:12345678` | 원본 장비 데이터 |
| **Computed** | `{type}:{version}:{fingerprint}:{tableHash}` | `expectation:result:v2:a3f2c1:1634567890` | 계산 결과 |

### 5. 캐시 키 메트릭

```java
// Good: 키 충돌 감지
private final Counter keyCollisionCounter = Counter.builder("cache.key.collision")
    .description("Cache key hash collision detected")
    .register(meterRegistry);

// 키 생성 시 충돌 검증
public String generateWithCollisionDetection(String baseKey) {
    String fullKey = baseKey + ":" + UUID.randomUUID().toString();

    // 충돌 검증 (선택 사항, 디버깅용)
    if (l2Cache.get(fullKey) != null) {
        keyCollisionCounter.increment();
        log.warn("Cache key collision detected: {}", fullKey);
    }

    return fullKey;
}
```

## 캐시 키 설계 원칙

### 1. Unique (유일성)

```java
// Bad: 같은 장비 조합이 다른 키
String key1 = "equipment:" + ocid + ":" + System.currentTimeMillis();
String key2 = "equipment:" + ocid + ":" + System.currentTimeMillis();

// Good: 장비 조합이 같으면 같은 키
String key = "equipment:" + fingerprint;  // fingerprint는 장비 조합 기반
```

### 2. Stable (안정성)

```java
// Bad: 매번 다른 키 (실패할 때마다 생성)
String key = "equipment:" + UUID.randomUUID();

// Good: 같은 데이터는 같은 키
String key = "equipment:" + fingerprint;  // 결정론적 (deterministic)
```

### 3. Human-readable (가독성)

```java
// Bad: 직관하지 않은 키
String key = "0a3f2c1b9e8d7f6a5c4b3d2e1f0a9b8c";

// Good: 용도를 알 수 있는 키
String key = "equipment:expectation:v2:a3f2c1b9:1634567890";
```

### 4. Version-aware (버전 인식)

```java
// Bad: 버전 없는 키
String key = "equipment:" + ocid;

// Good: 버전 포함 키
String key = "equipment:v2:" + ocid;  // v1 → v2 변경 시 자동 무효화
```

## 버전 관리 전략

### Global Version Bump (전역 버전 증가)

```yaml
# application.yml
cache:
  version: "v2"  # 전체 캐시 버전
```

```java
// 모든 캐시 키에 전역 버전 포함
public String generateKey(String type, String identifier) {
    return String.format("%s:%s:%s",
        type,
        globalCacheVersion,  # application.yml에서 로드
        identifier
    );
}
```

**장점:** 버전 변경으로 모든 캐시 일괄 무효화
**단점:** 세분화된 제어 불가

### Per-Type Version Bump (타입별 버전)

```java
public class CacheVersions {
    public static final String EQUIPMENT = "v1";
    public static final String EXPECTATION = "v2";  # 기대값 계산 로직 변경
    public static final String CUBE = "v1";
}
```

**장점:** 영향 범위 제한 가능
**단점:** 버전 관리 복잡

### Fingerprint-Based Version (지문 기반 버전)

```java
// Good: 데이터 변경 시 자동으로 다른 키
String cacheKey = "expectation:v2:" + equipmentFingerprint + ":" + probabilityTableHash;
```

**장점:** 데이터 변경 시 자동 무효화
**단점:** 지문 계산 비용

## 캐시 키 길이 고려사항

| 키 길이 | Redis Memory | 검색 성능 | 권장 |
|---------|--------------|-----------|------|
| **< 50 bytes** | 낮음 | 빠름 | ✅ |
| **50-100 bytes** | 중간 | 양호 | ⚠️ |
| **> 100 bytes** | 높음 | 느림 | ❌ |

```java
// Bad: 너무 긴 키
String key = "expectation:calculated:result:full:equipment:data:fingerprint:" + longFingerprint;

// Good: 간결한 키
String key = "exp:res:v2:" + shortHash;  # exp:res = expectation result 약어
```

## Hash Collision 방지

```java
// Good: 해시 + 전체 데이터 일부로 충돌 방지
public String generateSafeKey(String data) {
    String hash = DigestUtils.sha256Hex(data);  # 256-bit 해시

    // 충돌 방지: 데이터 앞부분 포함 (선택 사항)
    String prefix = data.substring(0, Math.min(10, data.length()));

    return hash + ":" + prefix;
}
```

## TTL과 키 버전 관계

```java
// Good: 버전별 다른 TTL
@Cacheable(value = "equipment", key = "'v1:' + #ocid")
public EquipmentData getEquipmentV1(String ocid) { ... }

@Cacheable(value = "equipment", key = "'v2:' + #ocid")
public EquipmentDataV2 getEquipmentV2(String ocid) { ... }

// v1 캐시는 5분, v2 캐시는 10분 TTL
```

## 모니터링

```promql
# 캐시 키 충돌 발생률
rate(cache_key_collision_total[5m])
# 목표: 0 (충돌 없어야 함)

# 캐시 키 생성 속도
rate(cache_key_generated_total[5m])
```

## 검증 명령어

```bash
# Redis에 저장된 캐시 키 패턴 확인
redis-cli --scan --pattern 'expectation:v2:*' | head -20

# 특정 버전의 캐시 키 개수
redis-cli --scan --pattern 'equipment:v1:*' | wc -l
redis-cli --scan --pattern 'equipment:v2:*' | wc -l
```

## 출처

- [expectation-cache-sequence.md](../../../04_Sequence_Diagrams/expectation-cache-sequence.md) - Phase 3: 기대값 캐시 조회
- TotalExpectationCacheService: `src/main/kotlin/maple/expectation/service/v2/cache/TotalExpectationCacheService.java`
- infrastructure.md Section 17: TieredCache
