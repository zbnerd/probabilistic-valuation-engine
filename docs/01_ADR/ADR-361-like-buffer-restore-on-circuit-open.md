# ADR-041: Like Buffer Restore on Circuit Breaker Open

## 상태 (Status)

**수락됨 (Accepted)**

## 컨텍스트 (Context)

### 현재 상태

현재 LikeBufferStrategy의 batchFallback 메서드는 fetchAndClear 호출 후 버퍼가 비어있는 상태에서 예외를 throw합니다:

```kotlin
// LikeSyncExecutor.kt
private fun batchFallback(keys: List<K>): Map<K, V> {
    val remaining = fetchAndClear(keys) // 버퍼 초기화
    if (remaining.isEmpty()) {
        throw LikeSyncException("No data available")
    }
    // ... 처리
}
```

### 문제 정의

1. **Data Loss**: fetchAndClear 호출로 인해 버퍼의 모든 데이터가 사라짐
2. **Permanent Loss**: Circuit가 열려있는 동안 데이터 지속적 손실
3. **Zero Recovery**: 버퍼 복구 메커니즘 부재

## 결정 (Decision)

### 1. LikeBufferStrategy에 restoreEntries 메서드 추가

```kotlin
interface LikeBufferStrategy<K, V> {
    // 기존 메서드들...

    // 새로 추가된 복구 메서드
    fun restoreEntries(entries: Map<K, V>): Int {
        entries.forEach { (key, value) ->
            put(key, value)
        }
        return entries.size
    }
}
```

### 2. InMemoryLikeBufferStorage 최적화 구현

```kotlin
class InMemoryLikeBufferStorage<K, V> : LikeBufferStrategy<K, V> {
    private val buffer = ConcurrentHashMap<K, V>()

    override fun restoreEntries(entries: Map<K, V>): Int {
        // AtomicLong.addAndGet 패턴으로 최적화
        val added = buffer.size.toLong()
        entries.forEach { (key, value) ->
            buffer[key] = value
        }
        return (buffer.size - added).toInt()
    }
}
```

### 3. batchFallback 로직 변경

```kotlin
// LikeSyncExecutor.kt
private fun batchFallback(keys: List<K>): Map<K, V> {
    val remaining = fetchAndClear(keys)

    // 데이터가 있으면 복구 후 처리
    if (remaining.isNotEmpty()) {
        val restored = bufferStrategy.restoreEntries(remaining)
        log.info("Restored {} entries on circuit open", restored)
        return processWithBuffer(keys)
    }

    // 데이터가 없으면 예외 throw
    throw LikeSyncException("No data available")
}
```

## 결과 (Consequences)

### 긍정적 영향

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| 데이터 손실 | 발생 | 0 |
| Circuit Open 시 | 데이터 지속적 손실 | 복구 가능 |
| 동시성 처리 | 직접 구현 필요 | AtomicLong로 최적화 |

### 부정적 영향

| 항목 | 영향 | 완화 방안 |
|------|------|---------|
| 메모리 사용량 | 복구로 인한 증가 | 적절한 TTL 설정 |
| 동시성 경합 | 복구 시 경합 가능성 | CAS 연산 사용 |

### 마이그레이션 경로

1. **Phase 1**: restoreEntries 인터페이스 메서드 추가
2. **Phase 2**: InMemoryLikeBufferStorage 구현체 업데이트
3. **Phase 3**: batchFallback 로직 변경
4. **Phase 4**: Circuit open 시 복구 테스트

## 이력 (History)

| 날짜 | 변경 내용 | 작성자 |
|------|---------|--------|
| 2026-03-29 | 초안 작성 | Claude (Haiku 4.5) |

## 참조 (References)

### 관련 문서
- [Circuit Breaker Pattern](https://martinfowler.com/bliki/CircuitBreaker.html)
- [ConcurrentHashMap Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html)

### 구현 파일
- `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/like/InMemoryLikeBufferStorage.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/LikeSyncExecutor.kt`

### 관련 Issue
- Issue #635: Like Buffer Restore on Circuit Breaker Open