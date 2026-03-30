---
id: GR-REFACTOR-007
category: architecture/refactor
severity: warning
keywords: [cache, tiered, duplication, l1-l2, template-method]
languages: [java, kotlin]
---

# Cache Service 조회/저장 로직 중복

## DON'T (위반 사항/장애 원인)

### 중복 코드
```java
// EquipmentCacheService.getValidCache()
public Optional<EquipmentResponse> getValidCache(String ocid) {
    return executor.execute(() -> {
        EquipmentResponse cached = tieredEquipmentCache.get(ocid, EquipmentResponse.class);
        if (cached != null && !"NEGATIVE_MARKER".equals(cached.getCharacterClass())) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }, context);
}

// TotalExpectationCacheService.getValidCache() (L1 → L2 조회)
public Optional<TotalExpectationResponse> getValidCache(String cacheKey) {
    return executor.execute(() -> {
        // L1 조회 (동일 패턴)
        Cache l1 = l1CacheManager.getCache(CACHE_NAME);
        if (l1 != null) {
            TotalExpectationResponse l1Result = l1.get(cacheKey, TotalExpectationResponse.class);
            if (l1Result != null) {
                return Optional.of(l1Result);
            }
        }

        // L2 조회 (동일 패턴)
        Cache l2 = l2CacheManager.getCache(CACHE_NAME);
        if (l2 != null) {
            TotalExpectationResponse l2Result = l2.get(cacheKey, TotalExpectationResponse.class);
            if (l2Result != null) {
                // L1 warm-up (중복 로직)
                if (l1 != null) {
                    l1.put(cacheKey, l2Result);
                }
                return Optional.of(l2Result);
            }
        }

        return Optional.empty();
    }, context);
}
```

### 위험 요소
- **Null 체크 + Optional 변환 패턴 반복**: 3개 캐시 서비스에서 동일
- **L1→L2 조회 로직 중복**: 일반화 가능한 패턴
- **캐싱 전략 하드코딩**: Null Marker 검증 로직 분산

### 수치
- 중복 서비스: 3개 (Equipment, TotalExpectation, Like)
- 코드 라인: 250라인

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 1. TieredCache 전략 인터페이스 통합
public interface TieredCacheStrategy<K, V> {
    Optional<V> getFromL1(K key);
    Optional<V> getFromL2(K key);
    void saveToL1(K key, V value);
    void saveToL2(K key, V value);
    boolean isValid(V value); // Null Marker 등 검증 로직
}

// 2. 공통 캐시 템플릿
public abstract class AbstractTieredCacheService<K, V> {
    protected final TieredCacheStrategy<K, V> strategy;
    protected final LogicExecutor executor;

    public Optional<V> getValidCache(K key) {
        return executor.execute(() -> {
            // L1 → L2 → L1 Warm-up 패턴 통합
            Optional<V> l1Hit = strategy.getFromL1(key);
            if (l1Hit.isPresent()) {
                return l1Hit;
            }

            Optional<V> l2Hit = strategy.getFromL2(key);
            if (l2Hit.isPresent()) {
                strategy.saveToL1(key, l2Hit.get()); // Warm-up
                return l2Hit;
            }

            return Optional.empty();
        }, buildContext("GetValid", key));
    }

    public void saveCache(K key, V value) {
        executor.executeVoid(() -> {
            if (strategy.isValid(value)) {
                strategy.saveToL2(key, value); // L2 first
            }
            strategy.saveToL1(key, value);     // L1 always
        }, buildContext("Save", key));
    }
}

// 3. 구현체는 전략만 주입
@Service
public class EquipmentCacheService extends AbstractTieredCacheService<String, EquipmentResponse> {
    // 전략 구현만 담당
}
```

### 개선 수치 (After)
- 코드 라인 수: 250 → 80 (68% 감소)
- 캐싱 전략 변경 시 1개 파일만 수정

### 핵심 원칙
1. **Strategy Pattern**: 캐싱 전략을 인터페이스로 분리
2. **Template Method Pattern**: L1→L2→Warm-up 패턴을 템플릿화
3. **SRP 준수**: 각 서비스는 전략 구현만 담당

## 출처
- 문서: [docs/05_Reports/05_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/05_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P0 (심각한 중복)
