# Module Separation Design Document

> **Version**: 1.0.0
> **Created**: 2026-02-27
> **Status**: Approved
> **Related Issues**: #409-#443 (35 issues)

---

## 1. Overview

### 1.1 Goal

Migrate 347 Java files from `module-app` to 4 well-defined modules:
- **Separation of Concerns (SoC)**
- **Dependency Inversion (DIP)**
- **Testability improvement**
- **Foundation for Kotlin migration**

### 1.2 Current State

| Module | Java Files | Status |
|--------|------------|--------|
| module-app | 347 | All code here |
| module-core | 7 | Almost empty |
| module-infra | 11 | Almost empty |
| module-web | 0 | Empty |
| module-common | 0 | Empty |

### 1.3 Target State

| Module | Files (Est.) | Responsibility |
|--------|--------------|----------------|
| module-common | ≤5% | Pure utilities, exceptions |
| module-core | 45-55% | Business rules, use cases, ports |
| module-infra | 30-40% | External adapters, cache, batch |
| module-web | 10-15% | HTTP layer, controllers, DTOs |

---

## 2. Architecture

### 2.1 Module Structure

```
module-app (347개) → 분리 →
├── module-common  (≤5%)  : 순수 공통 유틸/예외
├── module-core    (45-55%): 비즈니스 규칙/계산/유스케이스/포트
├── module-infra   (30-40%): 외부 연동/캐시/배치/스케줄러
└── module-web     (10-15%): HTTP 진입점/Controller/DTO
```

### 2.2 Dependency Direction

```
web ──────→ core ──────→ common
infra ─────→ core ──────→ common

❌ core → web/infra 참조 금지
❌ common → Spring 의존 금지
```

### 2.3 Package Structure

```
module-core/
└── maple.expectation.core/
    ├── application/          # Use cases
    ├── calculator/           # Calculation engines
    ├── cube/                 # Cube domain
    ├── flame/                # Flame domain
    ├── starforce/            # Starforce domain
    ├── policy/               # Cost policies
    ├── facade/               # Facade services
    ├── v4/                   # V4 pure logic
    ├── v5/                   # V5 pure logic
    ├── monitoring/           # Monitoring logic
    └── port/                 # Interfaces for infra

module-infra/
└── maple.expectation.infra/
    ├── batch/                # Spring Batch
    ├── scheduler/            # Schedulers
    ├── cache/                # Cache implementations
    ├── redis/                # Redis adapters
    ├── mongo/                # MongoDB adapters
    ├── nexon/                # Nexon API client
    ├── discord/              # Discord adapter
    ├── openai/               # OpenAI adapter
    ├── prometheus/           # Prometheus client
    ├── outbox/               # Outbox pattern
    ├── shutdown/             # Graceful shutdown
    ├── auth/                 # Auth implementations
    └── config/               # Infra configs

module-web/
└── maple.expectation.web/
    ├── controller/           # REST controllers
    ├── dto/                  # Request/Response DTOs
    ├── filter/               # Filters
    └── config/               # Web configs

module-common/
└── maple.expectation.common/
    ├── error/                # Common exceptions
    ├── util/                 # Pure utilities
    └── time/                 # Time utilities
```

---

## 3. Port/Adapter Pattern

### 3.1 Core Principle

**Core knows interfaces (ports), Infra provides implementations (adapters).**

### 3.2 Separation Targets

| Domain | Core Port | Infra Adapter |
|--------|-----------|---------------|
| Auth | TokenPort, SessionPort | JwtTokenService, RedisSessionManager |
| Like Realtime | LikeEventPublisher, LikeEventSubscriber | RedisLikeEventPublisher/Subscriber |
| Shutdown | PersistenceTracker | RedisEquipmentPersistenceTrackerAdapter |
| V5 Event | MongoSyncEventPublisherInterface | MongoSyncEventPublisher |
| Nexon API | NexonCharacterPort | NexonApiRetryClientImpl |
| Cache | LikeBufferStrategy, AtomicFetchStrategy | RedisLikeRelationBufferAdapter |

### 3.3 Example Structure

```java
// module-core: Port
package maple.expectation.core.auth.port;

public interface TokenPort {
    String generateToken(Long userId);
    Long validateToken(String token);
}

// module-infra: Adapter
package maple.expectation.infra.auth.adapter;

@Component
public class JwtTokenService implements TokenPort {
    // Implementation
}
```

---

## 4. Migration Plan

### 4.1 Approach: Sequential Migration

Each phase must pass all tests before proceeding to the next.

### 4.2 Phase Overview

```
Phase 0: Foundation (P0) → ADR + Gradle structure
    ↓
Phase 1: module-web (P1) → Controller/DTO/Filter/Config
    ↓
Phase 2: module-core (P1) → Application/Calculator/Domain/Policy/Facade/V4/V5
    ↓
Phase 3: module-infra (P1) → Batch/Scheduler/Cache/Redis/Mongo/Nexon/Discord
    ↓
Phase 4: Refactoring (P1-P2) → Common + Port/Adapter
    ↓
Phase 5: Kotlin Prep (P2) → Kotlin setup + priority
    ↓
Phase 6: Integration (P0-P1) → Test/Doc/Cleanup/CI
```

### 4.3 Detailed Issue Mapping

#### Phase 0: Foundation
| Issue | Content | Deliverable |
|-------|---------|-------------|
| #409 | ADR Document | `docs/adr/XXX-module-separation.md` |
| #410 | Gradle Dependencies | `build.gradle`, `settings.gradle` |

#### Phase 1: module-web
| Issue | Target | Files (Est.) |
|-------|--------|--------------|
| #411 | Controller/DTO | ~20 |
| #412 | Filter/Interceptor | ~5 |
| #413 | Web Config | ~3 |

#### Phase 2: module-core
| Issue | Target | Files (Est.) |
|-------|--------|--------------|
| #414 | Application Layer | ~10 |
| #415-418 | Calculator/Cube/Flame/Starforce | ~40 |
| #419-420 | Policy/Facade | ~10 |
| #421-422 | V4/V5 Pure Logic | ~30 |
| #423 | Monitoring Pure Logic | ~20 |

#### Phase 3: module-infra
| Issue | Target | Files (Est.) |
|-------|--------|--------------|
| #424 | Batch/Scheduler | ~15 |
| #425-427 | Cache/Redis/Outbox | ~25 |
| #428-429 | Nexon/Discord/OpenAI | ~15 |
| #430-434 | Config/Shutdown/Auth/V5 Worker/V2 | ~20 |

#### Phase 4-5: Refactoring & Kotlin
| Issue | Content |
|-------|---------|
| #435-436 | Common + Port/Adapter |
| #437-438 | Kotlin Setup + Priority |

#### Phase 6: Integration
| Issue | Content |
|-------|---------|
| #439 | Integration Test Verification |
| #440 | Documentation |
| #441 | Cleanup module-app |
| #442 | CI/CD Update |
| #443 | ADR Status Update |

---

## 5. Test Strategy

### 5.1 Verification Levels

| Level | Content | Tool |
|-------|---------|------|
| Unit Test | Module internal logic | JUnit 5, Mockito |
| Integration Test | Module interactions | Testcontainers |
| Architecture Test | Dependency direction | ArchUnit |
| Chaos Test | Failure handling | Nightmare scenarios |

### 5.2 ArchUnit Rules

```java
@AnalyzeClasses(packages = "maple.expectation")
public class ModuleDependencyTest {

    @ArchTest
    static final ArchRule core_should_not_depend_on_web_or_infra =
        noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..web..", "..infra..");

    @ArchTest
    static final ArchRule common_should_not_depend_on_spring =
        noClasses()
            .that().resideInAPackage("..common..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.springframework..");
}
```

### 5.3 Phase Completion Criteria

| Phase | Criteria |
|-------|----------|
| Phase 0-3 | `./gradlew test` 100% pass |
| Phase 4 | ArchUnit verification pass |
| Phase 6 | Chaos Test N01-N18 all pass |

---

## 6. Success Criteria

### 6.1 Definition of Done

| Item | Criteria |
|------|----------|
| Build | `./gradlew clean build` success |
| Tests | 479+ tests 100% pass |
| Architecture | ArchUnit rules 100% compliance |
| Performance | Latency/RPS within 5% variance |
| API | All endpoints identical behavior |

### 6.2 Risk Management

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Circular dependency | Medium | High | Thorough Phase 0 design, ArchUnit early detection |
| Performance degradation | Low | High | Benchmark after each phase |
| Test failures | Medium | Medium | Test immediately after each file migration |
| Feature regression | Low | High | E2E tests, Chaos Test verification |

### 6.3 Rollback Strategy

- Each phase = separate branch/PR
- Problem → revert specific phase PR only
- `module-app` remains until end (safety net)

---

## 7. Kotlin Migration (Post-Separation)

### 7.1 Prerequisite

Module separation MUST complete before Kotlin migration.

### 7.2 Recommended Order

1. **module-web**: DTO/Controller (low complexity, quick wins)
2. **module-common**: Small, easy to clean
3. **module-core**: Pure calculation logic (Kotlin benefits high)
4. **module-infra**: Last (Spring/Redis/Mongo/Batch complexity)

### 7.3 Kotlin-First Types

- DTO → `data class`
- Calculation models → `data class` / `sealed class`
- Enums → Kotlin `enum` / `sealed class`
- Strategies → `object` / `sealed class`
- Mappers → extension functions

---

## 8. References

- CLAUDE.md Section 4 (Implementation Logic & SOLID)
- CLAUDE.md Section 11-13 (Exception Handling, LogicExecutor)
- GitHub Issues: #409-#443

---

## 9. Approval

| Role | Name | Date | Status |
|------|------|------|--------|
| Author | Claude | 2026-02-27 | Approved |
| Reviewer | User | 2026-02-27 | Approved |

---

*Document Version: 1.0.0*
*Last Updated: 2026-02-27*
