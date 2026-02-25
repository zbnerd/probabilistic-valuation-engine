---
id: GR-REFACTOR-014
category: architecture/refactor
severity: warning
keywords: [buffer, duplication, tiered, caffeine, template-method]
languages: [java, kotlin]
---

# Tiered Buffer 구조적 중복

## DON'T (위반 사항/장애 원인)

### 중복 패턴
```java
// LikeRelationBuffer.java (관계 버퍼링)
public Boolean addRelation(String accountId, String targetOcid) {
    // 1. L1 체크
    if (localCache.getIfPresent(relationKey) != null) return false;

    // 2. L2 원자적 추가
    Boolean isNew = getRelationSet().add(relationKey);

    // 3. L1 warm-up
    if (isNew) {
        localCache.put(relationKey, Boolean.TRUE);
        localPendingSet.put(relationKey, Boolean.TRUE);
    }
    return isNew;
}

// RedisLikeBufferStorage.java (카운트 버퍼링) - 유사한 구조
public Long increment(String key) {
    // 1. L1 체크
    // 2. L2 원자적 증가
    // 3. L1 warm-up
}
```

### 위험 요소
- **L1 → L2 → L1 Warm-up 패턴 반복**: 두 버퍼 모두 동일한 3단계 구조
- **Caffeine 설정 중복**: `expireAfterAccess`, `maximumSize` 등
- **메트릭 등록 로직 중복**: Gauge.builder() 패턴 반복

### 수치
- 중복 코드: 280라인
- 구조적 유사성: 90%

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 1. 추상 버퍼 베이스
public abstract class AbstractTieredBuffer<K, V> {
    protected final Cache<K, V> localCache;
    protected final ConcurrentHashMap<K, V> localPendingSet;
    protected final RedissonClient redissonClient;

    public AbstractTieredBuffer(MeterRegistry registry, int maxSize, long ttlMinutes) {
        this.localCache = Caffeine.newBuilder()
            .expireAfterAccess(ttlMinutes, TimeUnit.MINUTES)
            .maximumSize(maxSize)
            .build();

        // 메트릭 등록 통일
        Gauge.builder("buffer.l1.size", () -> localCache.estimatedSize())
            .register(registry);
    }

    // Template Method
    public final V getOrCompute(K key, Function<K, V> compute) {
        V cached = localCache.getIfPresent(key);
        if (cached != null) return cached;

        V computed = compute.apply(key);
        localCache.put(key, computed);
        localPendingSet.put(key, computed);
        return computed;
    }
}

// 2. 구현체는 Redis Key 패턴만 정의
public class LikeRelationBuffer extends AbstractTieredBuffer<String, Boolean> {
    @Override
    protected String getRedisKey(String key) {
        return "buffer:like:relations:" + key;
    }
}
```

### 개선 수치 (After)
- 코드 라인 수: 280 → 150 (46% 감소)
- 버퍼 전략 일관성 보장
- 신규 버퍼 타입 추가 용이

### 핵심 원칙
1. **Template Method Pattern**: L1→L2→Warm-up 패턴을 추상 클래스에 정의
2. **SRP 준수**: 각 구현체는 Redis Key 패턴만 정의
3. **메트릭 등록 통일**: 생성자에서 일관되게 메트릭 등록

## 출처
- 문서: [docs/05_Reports/04_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/04_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P1 (중간 수준 중복)
