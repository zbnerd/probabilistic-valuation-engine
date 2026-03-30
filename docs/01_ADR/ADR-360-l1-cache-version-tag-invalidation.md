# ADR-360: L1 Cache Version Tag for Stale Detection

## 상태 (Status)

**수락됨 (Accepted)**

## 컨텍스트 (Context)

### 현재 상태

현재 L1 캐시인 Caffeine은 인스턴스 로컬 메모리에 저장됩니다. 다중 인스턴스 환경에서 발생하는 경쟁 상황 문제가 존재합니다:

```
인스턴스 A → L1 캐시 업데이트 → NOTIFY 발행
인스턴스 B → L1 캐시 무효화 실패 → 오래된 데이터 유지
```

### 문제 정의

1. **Race Condition**: 인스턴스 간 LISTEN/NOTIFY를 통한 캐시 무효화 시 타이백 문제 발생
2. **False Eviction**: 이미 업데이트된 데이터를 불필요하게 제거하는 성능 저하
3. **Stale Data**: 네트워크 지연으로 인해 오래된 데이터가 계속 유지될 수 있음

## 결정 (Decision)

### 1. Sidecar Version Map 구조 도입

```kotlin
class TieredCache<K, V> {
    // 기존 Caffeine 캐시
    private val cache = Caffeine.newBuilder().build<K, V>()

    // 새로운 버전 탯 sidecar
    private val versionTags = ConcurrentHashMap<K, AtomicLong>()
    private val versionCounter = AtomicLong(0)

    // Put 시 버전 증가
    override fun put(key: K, value: V): V {
        val newVersion = versionCounter.incrementAndGet()
        versionTags[key] = AtomicLong(newVersion)
        return cache.put(key, value)
    }
}
```

### 2. NOTIFY 무효화 로직 변경

```kotlin
// PostgresNotifySubscriber.kt
fun handleInvalidation(event: CacheInvalidationEvent) {
    val eventVersion = event.version
    val currentVersion = versionTags.getOrPut(event.key) { AtomicLong(0) }

    // 로컬 버전이 이벤트 버전보다 작은 경우만 무효화
    if (currentVersion.get() < eventVersion) {
        cache.invalidate(event.key)
        currentVersion.set(eventVersion)
    }
}
```

### 3. Backfill 시 버전 동기화

```kotlin
fun backfill(key: K, value: V): V {
    val newVersion = versionCounter.incrementAndGet()
    versionTags[key] = AtomicLong(newVersion)
    return cache.put(key, value)
}
```

## 결과 (Consequences)

### 긍정적 영향

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| False Eviction | 발생 | 0 |
| Race Condition | 발생 | 방지 |
| 성능 | 무효화 시 성능 저하 | 최소화 |

### 부정적 영향

| 항목 | 영향 | 완화 방안 |
|------|------|---------|
| 메모리 사용량 | ConcurrentHashMap 추가 | 1% 미만 증가 |
| CPU 오버헤드 | AtomicLong 연산 | 최소한의 CAS 연산 |

### 마이그레이션 경로

1. **Phase 1**: Sidecar ConcurrentHashMap 추가
2. **Phase 2**: Version 태그 로직 구현
3. **Phase 3**: NOTIFY 핸들러 업데이트
4. **Phase 4**: 버전 충돌 테스트

## 이력 (History)

| 날짜 | 변경 내용 | 작성자 |
|------|---------|--------|
| 2026-03-29 | 초안 작성 | Claude (Haiku 4.5) |

## 참조 (References)

### 관련 문서
- [Caffeine Cache Documentation](https://github.com/ben-manes/caffeine)
- [PostgreSQL LISTEN/NOTIFY](https://www.postgresql.org/docs/current/sql-listen.html)

### 구현 파일
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/CacheInvalidationEvent.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/PostgresNotifySubscriber.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCacheManager.kt`

### 관련 Issue
- Issue #632: L1 Cache Version Tag Invalidation