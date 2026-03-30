# ADR-012: Redis Operation Port Abstraction

## Status

Proposed

## Context

현재 `module-infra`의 38개 이상의 클래스가 `RedissonClient` 구현체에 직접 의존하고 있다.

### 문제점

1. **DIP 위반**: 상위 모듈이 하위 인프라 구현체에 직접 의존
2. **테스트 격리 어려움**: RedissonClient를 Mock으로 대체하기 어려움
3. **구현체 교체 불가**: Lettuce 등 다른 Redis 클라이언트로 교체 시 38개 파일 수정 필요
4. **OCP 위반**: 확장에는 열려있지 않고 수정에 열려있음

### 현재 의존 구조

```
비즈니스 로직 (상위)
    ↓ 직접 의존 (DIP 위반)
RedissonClient (하위 인프라)
```

## Decision

`RedisOperationPort` 인터페이스를 도입하여 DIP를 준수한다.

### 목표 의존 구조

```
비즈니스 로직 (상위)
    ↓ 인터페이스에만 의존 (DIP 준수)
RedisOperationPort (추상화)
    ↑ 구현
RedissonOperationAdapter (하위 인프라)
    ↓ 사용
RedissonClient
```

## Interface Design

### RedisOperationPort

```kotlin
/**
 * Redis 작업을 추상화한 Port 인터페이스
 *
 * DIP를 준수하여 비즈니스 로직이 Redis 구현체(RedissonClient)에
 * 직접 의존하지 않도록 한다.
 */
interface RedisOperationPort {

    // ===== Basic Operations =====

    fun <T> get(key: String): T?
    fun <T> set(key: String, value: T, ttl: Duration? = null)
    fun delete(key: String): Boolean
    fun exists(key: String): Boolean

    // ===== Hash Operations =====

    fun <T> hGet(key: String, field: String): T?
    fun <T> hGetAll(key: String): Map<String, T>
    fun hSet(key: String, field: String, value: Any)
    fun hSetAll(key: String, map: Map<String, Any>)
    fun hDelete(key: String, vararg fields: String): Long

    // ===== Set Operations =====

    fun <T> sMembers(key: String): Set<T>
    fun sAdd(key: String, vararg values: Any): Long
    fun sRem(key: String, vararg values: Any): Long
    fun sIsMember(key: String, value: Any): Boolean

    // ===== List Operations =====

    fun <T> lRange(key: String, start: Long, end: Long): List<T>
    fun lPush(key: String, vararg values: Any): Long
    fun rPush(key: String, vararg values: Any): Long
    fun lPop(key: String): Any?
    fun rPop(key: String): Any?

    // ===== Atomic Operations =====

    fun <T> getAndSet(key: String, newValue: T): T?
    fun increment(key: String, delta: Long = 1): Long
    fun decrement(key: String, delta: Long = 1): Long

    // ===== Lock Operations =====

    fun tryLock(key: String, waitTime: Duration, leaseTime: Duration): Boolean
    fun unlock(key: String)
    fun isLocked(key: String): Boolean

    // ===== Pub/Sub Operations =====

    fun publish(topic: String, message: Any): Long
    fun subscribe(topic: String, consumer: (message: Any) -> Unit)

    // ===== Script Operations =====

    fun <T> executeScript(script: String, keys: List<String>, args: List<Any>): T

    // ===== TTL Operations =====

    fun expire(key: String, ttl: Duration): Boolean
    fun getTtl(key: String): Duration?
    fun persist(key: String): Boolean
}
```

## Implementation Plan

### Phase 1: 인터페이스 정의 (즉시)

1. `RedisOperationPort` 인터페이스 생성
2. `RedissonOperationAdapter` 구현체 생성

### Phase 2: 파일럿 리팩토링

1. 대표 클래스 3개 선정하여 리팩토링
2. 테스트 코드 작성
3. 패턴 검증

### Phase 3: 전체 리팩토링

38개 파일 순차적 마이그레이션:

| 파일 | 현재 의존 | 변경 |
|------|----------|------|
| `TieredCache.kt` | RedissonClient | RedisOperationPort |
| `TieredCacheManager.kt` | RedissonClient | RedisOperationPort |
| `DistributedSingleFlightExecutor.kt` | RedissonClient | RedisOperationPort |
| ... | ... | ... |

### Phase 4: 테스트 및 검증

1. 통합 테스트 실행
2. 성능 테스트
3. 회귀 테스트

## Consequences

### 긍정적

- **DIP 준수**: 비즈니스 로직이 추상화에만 의존
- **테스트 용이성**: Mock 구현체로 단위 테스트 가능
- **유연성**: Redis 클라이언트 교체 가능 (Redisson → Lettuce)
- **OCP 준수**: 확장에는 열려있고 수정에는 닫혀있음

### 부정적

- **초기 비용**: 38개 파일 수정 필요
- **복잡성 증가**: 인터페이스 레이어 추가
- **학습 곡선**: 팀원이 Port/Adapter 패턴 이해 필요

### 위험 완화

- 파일럿 리팩토링으로 패턴 검증 후 전체 확장
- 충분한 테스트 코드 작성
- 코드 리뷰를 통한 품질 관리

## Related

- [ADR-004: Module Core Migration](./ADR-004-module-core-migration-cube-summary.md)
- [CLAUDE.md Section 4: SOLID](../CLAUDE.md)

## Timeline

- Phase 1: 즉시 진행
- Phase 2: 1일
- Phase 3: 2-3일
- Phase 4: 1일
