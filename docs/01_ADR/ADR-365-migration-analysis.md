# Facade Migration Analysis Report (ADR-004 Phase 1)

**Date**: 2026-02-28
**Status**: Analysis Complete - DEFERRED to Phase 2

---

## Executive Summary

Facade 패키지(`service/v2/facade/`)의 **이관을 유예**하고, **ADR-003 Port 기반 리팩토링 선행**을 권장합니다.

---

## 1. Current State

### 1.1 Files in Facade Package
```
module-app/src/main/java/maple/expectation/service/v2/facade/
├── GameCharacterFacade.java       (152 lines)
└── GameCharacterSynchronizer.java (87 lines)
```

### 1.2 Dependency Analysis

#### GameCharacterFacade Dependencies
| Dependency | Type | Module | Port Available? |
|------------|------|--------|-----------------|
| `GameCharacterService` | Service | app | ❌ NO |
| `MessageTopic<String>` | Port | core (out) | ✅ YES |
| `MessageQueue<String>` | Port | core (out) | ✅ YES |
| `LogicExecutor` | Infra | infra | ✅ YES (via package) |
| `TaskContext` | Infra | infra | ✅ YES (via package) |
| `AsyncUtils` | Infra | infra | ✅ YES (via package) |

#### GameCharacterSynchronizer Dependencies
| Dependency | Type | Module | Port Available? |
|------------|------|--------|-----------------|
| `GameCharacterService` | Service | app | ❌ NO |
| `RedissonClient` | External API | infra | ❌ NO |
| `LogicExecutor` | Infra | infra | ✅ YES (via package) |
| `RCountDownLatch` | Redisson API | infra | ❌ NO |

---

## 2. Critical Blocking Issues

### Issue #1: GameCharacterService Dependency (P0 - BLOCKER)

**Problem**: Facade 클래스들은 `GameCharacterService`에 강하게 의존합니다.

```java
// GameCharacterFacade.java:25
private final GameCharacterService gameCharacterService;

// GameCharacterSynchronizer.java:20
private final GameCharacterService gameCharacterService;
```

**Impact**:
- `GameCharacterService`는 `module-app`에 위치한 서비스 클래스
- Repository, NexonApiClient, CacheManager 등 인프라 의존성이 심함
- Facade를 이관하려면 `GameCharacterService`도 함께 이관하거나 Port 추출 필요

### Issue #2: RedissonClient Direct Dependency (P0 - BLOCKER)

**Problem**: `GameCharacterSynchronizer`는 `RedissonClient`를 직접 사용합니다.

```java
// GameCharacterSynchronizer.java:21
private final org.redisson.api.RedissonClient redissonClient;

// GameCharacterSynchronizer.java:35
RCountDownLatch latch = redissonClient.getCountDownLatch("latch:char:" + cleanUserIgn);
```

**Impact**:
- 순수 비즈니스 로직이 아닌 **동시성 제어 인프라 로직**
- Hexagonal Architecture 원칙상 **Core는 Redisson API를 직접 알면 안 됨**
- `DistributedLockPort` 등의 Port 추출이 선행되어야 함

### Issue #3: Infra Package Dependencies (P1 - HIGH)

**Problem**: Facade는 `infrastructure` 패키지의 클래스들을 직접 사용합니다.

```java
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.util.AsyncUtils;
import org.redisson.api.RCountDownLatch;
```

**Current Situation**:
- `module-app`은 `module-infra`에 의존 가능
- `module-core`는 `module-infra`에 의존할 수 없음 (**DIP 위반**)

---

## 3. Architecture Violation Risk

### Risk #1: Dependency Inversion Violation

```
Current (Facade in app):
module-app → module-infra ✅ (허용)

Proposed (Facade in core):
module-core → module-infra ❌ (DIP 위반)
```

### Risk #2: Circular Dependency

```
GameCharacterService (app)
    ↓ depends on
GameCharacterFacade (core) ❌
    ↓ depends on
GameCharacterService (app) 💥 CYCLE!
```

### Risk #3: Infrastructure Leakage

```kotlin
// BAD: Core가 Infra 구현을 알게 됨
class GameCharacterFacade(
    private val redissonClient: RedissonClient  // ❌ Infra 구현 노출
)

// GOOD: Core가 Port만 알게 됨
class GameCharacterFacade(
    private val distributedLockPort: DistributedLockPort  // ✅ Port 추상화
)
```

---

## 4. Recommendations

### Recommendation #1: DEFER Facade Migration (PRIORITY)

**Rationale**:
1. **ADR-003 Port 기반 리팩토링이 선행**되어야 함
2. `GameCharacterService`가 Port/Adapter 패턴으로 리팩토링되면 Facade 이관이 자연스러워짐
3. 현재 단계에서 Facade를 강제 이관하면 **순환 의존성 발생**

**Timeline**:
- **Phase 1 (Current)**: Calculator, Flame, Starforce 도메인 이관
- **Phase 2**: `GameCharacterService` → `CharacterPort` 리팩토링
- **Phase 3**: Facade 이관 (의존성 해결 후)

### Recommendation #2: Port Interface Extraction (PRE-REQUISITE)

Facade 이관 전 다음 Port가 필요합니다:

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/
interface CharacterQueryPort {
    fun findCharacter(userIgn: String): GameCharacter?
    fun isNonExistent(userIgn: String): Boolean
}

interface CharacterCreationPort {
    fun createNewCharacter(userIgn: String): GameCharacter
    fun enrichBasicInfo(character: GameCharacter): GameCharacter
}

interface DistributedLockPort {
    fun tryAcquireLeadership(latchKey: String): Boolean
    fun awaitCompletion(latchKey: String, timeoutSeconds: Long): Boolean
    fun releaseAndDelete(latchKey: String)
}
```

### Recommendation #3: Layer Refactoring (ALTERNATIVE)

Facade를 **Application Service**로 재정의하여 `module-app`에 유지:

```
module-app/src/main/java/maple/expectation/application/
├── character/
│   ├── CharacterQueryService.java      # (was GameCharacterFacade)
│   └── CharacterSynchronizationService.java  # (was GameCharacterSynchronizer)
└── equipment/
    └── EquipmentCalculationService.java
```

**Rationale**:
- Facade는 본질적으로 **여러 서비스를 조율하는 Application Layer**
- 순수 비즈니스 로직이 아닌 **코디네이션(Coordination) 로직**
- `module-app`에 유지하는 것이 **Clean Layered Architecture**에 부합

---

## 5. Migration Blocker Summary

| Blocker | Severity | Resolution |
|---------|----------|------------|
| GameCharacterService 의존 | P0 | ADR-003 리팩토링 완료 후 Port 추출 |
| RedissonClient 직접 사용 | P0 | DistributedLockPort 정의 필요 |
| Infra 패키지 의존 | P1 | Port 인터페이스 추출 또는 유예 |
| 순환 의존성 위험 | P0 | 서비스 계층 리팩토링 선행 |

---

## 6. Conclusion

### Status: **DEFERRED** (유예)

**Reasoning**:
1. **술익기 전에 술 따르기**: ADR-003 Port 기반 리팩토링이 선행되어야 함
2. **순환 의존성 방지**: 현재 상태에서 이관 시 DIP 위반 발생
3. **아키텍처 정합성**: Facade는 Application Layer 패턴이므로 `module-app` 유지가 타당

**Next Steps**:
1. ✅ Calculator, Flame, Starforce 도메인 이관 (Phase 1)
2. ⏳ `GameCharacterService` → `CharacterPort` 리팩토링 (Phase 2)
3. ⏳ `DistributedLockPort` 정의 및 Adapter 구현 (Phase 2)
4. ⏳ Facade 이관 재검토 (Phase 3)

---

## 7. Related Documents

- ADR-003: Hexagonal Architecture (Ports & Adapters) 채택
- ADR-004: Module-Core 도메인 이관
- docs/04_Sequence_Diagrams/character-lookup-sequence.md
- docs/03_Technical_Guides/service-modules.md

---

**Report Version**: 1.0.0
**Author**: Claude (Executor Agent)
**Reviewed By**: Pending
