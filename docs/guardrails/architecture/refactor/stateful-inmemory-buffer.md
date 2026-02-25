---
id: GR-REFACTOR-011
category: architecture/refactor
severity: warning
keywords: [in-memory, buffer, caffeine, stateful, scale-out, data-loss]
languages: [java, kotlin]
---

# In-Memory Stateful Buffer

## DON'T (위반 사항/장애 원인)

### 위험 코드
```java
// Caffeine 기반 L1 Cache (Instance-local)
private final Cache<String, Boolean> localCache = Caffeine.newBuilder()
    .expireAfterAccess(1, TimeUnit.MINUTES)
    .maximumSize(10_000)
    .build();

// 인스턴스 재시작 시 데이터 유실 위험
public Boolean addRelation(String accountId, String targetOcid) {
    if (localCache.getIfPresent(relationKey) != null) return false;
    // ...
    localCache.put(relationKey, Boolean.TRUE); // 인스턴스 재시작 시 유실
    return isNew;
}
```

### 위험 요소
- **Scale-out 시 데이터 유실**: 인스턴스 간 L1 Cache 공유 불가
- **Rebalance 중 데이터 손실**: Scale-out/Scale-in 시 L1 데이터 소실
- **일관성 문제**: 인스턴스별로 다른 상태 유지

### 수치 (Before)
- Scale-out 시 데이터 유실: 가능성 HIGH
- 인스턴스간 불일치: 발생

## DO (수정 방법/재발 방지)

### 수정 코드 (옵션 1: L1을 Warm-up Cache로만 사용)
```java
// L1은 단순 캐시로만 사용, 영구 저장소는 L2(Redis)
public Boolean addRelation(String accountId, String targetOcid) {
    String relationKey = accountId + ":" + targetOcid;

    // 1. L1 체크 (성능 최적화용)
    if (localCache.getIfPresent(relationKey) != null) {
        return false;
    }

    // 2. L2(Redis) 원자적 추가 (진실 공급원)
    RSet<String> relationSet = redissonClient.getSet("relations:" + accountId);
    Boolean isNew = relationSet.add(targetOcid);

    // 3. L1 warm-up (선택적 캐시)
    if (isNew) {
        localCache.put(relationKey, Boolean.TRUE);
    }
    return isNew;
}
```

### 수정 코드 (옵션 2: 완전한 Stateless - L1 제거)
```java
// L1 제거, L2(Redis)만 사용
public Boolean addRelation(String accountId, String targetOcid) {
    RSet<String> relationSet = redissonClient.getSet("relations:" + accountId);
    return relationSet.add(targetOcid);
}
```

### 개선 수치 (After)
- L1 Warm-up 패턴: 성능 최적화 + 일관성 보장
- Scale-out 시 데이터 유실: 방지됨 (L2 Redis에 존재)

### 핵심 원칙
1. **L2를 진실 공급원으로**: Redis 등 영구 저장소를 Source of Truth
2. **L1은 선택적 캐시**: Warm-up 전략으로만 사용, 유실해도 무방
3. **모니터링**: L1/L2 hit rate를 Prometheus로 모니터링

## 출처
- 문서: [docs/05_Reports/04_08_Refactor/STATEFUL_REFACTORING_TARGETS.md](../../../05_Reports/04_08_Refactor/STATEFUL_REFACTORING_TARGETS.md)
- 관련 문서: [docs/03_Technical_Guides/infrastructure.md](../../../03_Technical_Guides/infrastructure.md) (Section 17: TieredCache)
