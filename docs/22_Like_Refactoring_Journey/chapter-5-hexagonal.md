# 5장: 헥사고날 — 경계의 명확화

> *2026년 3월 1일 ~ 2026년 3월 4일*

---

## 5.1 ADR-003: LikeSyncScheduler의 헥사고날 리팩토링

2026년 3월 1일, 세 개의 연속 커밋으로 Like 도메인이 헥사고날 아키텍처로 재편되었다.

```text
9f8c2e32 refactor: ADR-003 LikeSyncScheduler 헥사고날 아키텍처 리팩토링 (#451)
0979c84d feat: ADR-005 LikeSyncScheduler 이관 (#424) (#481)
14a75004 refactor: module-infra 및 module-core 구조 정리 (#465)
```

### 동기

LikeSyncScheduler는 비즈니스 로직(동기화 정책)과 인프라 로직(Redis 접근, DB 쿼리)이 혼재되어 있었다. 이를 헥사고날 아키텍처로 분리:

```text
Before:
LikeSyncScheduler
├── @Scheduled 메서드
├── Redis 직접 접근
├── JPA Repository 직접 호출
└── 보상 트랜잭션 로직

After:
module-core:
├── LikeAtomicFetchStrategy (Port) — "무엇을 할 것인가"
└── CompensationCommand (Port)

module-infra:
├── LikeSyncExecutor (Adapter) — "어떻게 할 것인가"
└── InMemoryLikeBufferStorage (Adapter)

module-app:
├── LikeSyncEventListener (Use Case)
└── LikeProcessor (Application Service)
```

### Port/Adapter 패턴의 적용

```kotlin
// module-core: Port (인터페이스)
interface LikeAtomicFetchStrategy {
    fun fetchAndClear(): Map<String, Long>
    fun increment(userIgn: String, delta: Long)
}

// module-infra: Adapter (구현체)
class InMemoryLikeBufferStorage(
    private val executor: LogicExecutor
) : LikeAtomicFetchStrategy {
    override fun fetchAndClear(): Map<String, Long> {
        return executor.execute(
            { /* Caffeine buffer 스냅샷 + 클리어 */ },
            TaskContext.of("LikeBuffer", "fetchAndClear")
        )
    }
}
```

**핵심 원칙**: module-core는 **어떤 인프라 기술도 모른다**. Redis, Caffeine, PostgreSQL — 모두 module-infra의 구현 세부사항이다.

---

## 5.2 ADR-012: Like 패키지 Core/Infra 분리

3월 2일, Like 패키지의 완전한 계층 분리가 이루어졌다.

```text
c0b37b54 refactor: ADR-012 like 패키지 core/infra 분리 완료 (#535)
```

### 분리된 구조

```text
module-core/src/main/kotlin/maple/expectation/core/
├── domain/model/like/
│   ├── CharacterLike.kt          # 엔티티 (순수 도메인)
│   ├── LikeId.kt                 # 값 객체
│   ├── LikeToggleResult.kt       # 토글 결과
│   └── LikeToggleWithCount.kt    # 토글 + 카운트
├── dto/like/
│   ├── LikeEvent.kt              # 이벤트 DTO
│   └── FetchResult.kt            # 조회 결과
└── port/out/like/
    ├── LikeAtomicFetchStrategy.kt # 아웃바운드 포트
    └── CompensationCommand.kt     # 보상 명령

module-infra/src/main/kotlin/maple/expectation/infrastructure/
├── cache/like/
│   └── InMemoryLikeBufferStorage.kt  # 어댑터 (Caffeine)
└── queue/like/
    ├── LikeSyncExecutor.kt           # 어댑터 (동기화 실행)
    └── event/
        └── LikeSyncFailedEvent.kt    # 이벤트
```

### 의존성 규칙

```text
module-app → module-core ← module-infra
                    ↑
            (인터페이스/Port)
```

- **module-app**은 module-core의 Port를 통해 인프라에 접근
- **module-infra**는 module-core의 Port를 구현
- **module-core**는 아무것도 모른다 (순수 Kotlin, 프레임워크 독립)

---

## 5.3 ADR-004 Phase 5: Application 계층 이관

3월 3~4일, Like 패키지가 application 계층으로 이관되었다.

```text
2a1d5766 refactor: ADR-004 Phase 5 - 빈 패키지 제거 및 adapter/in → application/usecase 이관 (#538)
6e8a05b2 refactor: ADR-004 Phase 5-E & 5-F - like/donation 패키지 application 계층 이관 (#539)
04a5b505 refactor: ADR-004 Phase 5-G/H - facade/worker/flame/starforce 패키지 이관 (#540)
```

### Application 계층의 역할

```text
module-app/src/main/java/maple/expectation/application/service/like/
├── LikeToggleService.java       # Use Case: 좋아요 토글
├── LikeProcessor.java           # Use Case: 좋아요 처리 (인터페이스)
├── DatabaseLikeProcessor.java   # Use Case: DB 기반 처리 (구현체)
├── OcidResolutionService.java   # Use Case: OCID 해석
├── listener/
│   └── LikeSyncEventListener.java  # Event Handler
└── metrics/
    └── LikeSyncMetricsRecorder.java # Metrics
```

**LikeToggleService**가 핵심 Use Case 역할을 한다. 이 서비스는:

1. Port를 통해서만 인프라에 접근
2. Domain Model만으로 비즈니스 로직 수행
3. 테스트 가능 (인프라 Mock 가능)

---

## 5.4 이 시점의 완성된 아키텍처

```text
┌──────────────────────────────────────────────────────────┐
│                     module-app                            │
│  ┌─────────────────────────────────────────────────────┐ │
│  │              Application Services                    │ │
│  │  LikeToggleService ← LikeProcessor                  │ │
│  │  OcidResolutionService                               │ │
│  │  LikeSyncEventListener                               │ │
│  └──────────┬──────────────────────┬───────────────────┘ │
│             │ (Port)               │ (Port)              │
├─────────────┼──────────────────────┼─────────────────────┤
│  module-core │                      │  module-infra       │
│  ┌───────────▼──────────┐  ┌───────▼──────────────────┐ │
│  │   Domain Model       │  │   Adapters               │ │
│  │  CharacterLike       │  │  InMemoryLikeBuffer      │ │
│  │  LikeToggleResult    │  │  LikeSyncExecutor         │ │
│  │                      │  │  DatabaseLikeProcessor    │ │
│  │   Ports (Out)        │  │  OcidResolutionAdapter    │ │
│  │  LikeAtomicFetch     │  │                           │ │
│  │  CompensationCommand │  │   External                │ │
│  │                      │  │  PostgreSQL / PGMQ        │ │
│  └──────────────────────┘  └───────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### 달성된 것

| 원칙 | 상태 | 비고 |
|------|------|------|
| 의존성 역전 (DIP) | ✅ | App → Core ← Infra |
| 단일 책임 (SRP) | ✅ | 각 클래스가 하나의 역할 |
| 인터페이스 분리 (ISP) | ✅ | Port가 최소 메서드 |
| 개방-폐쇄 (OCP) | ✅ | Infra 교체 가능 |
| 비즈니스 로직 격리 | ✅ | Core는 순수 Kotlin |

**Phase 5 요약**: 헥사고날 아키텍처로의 전환을 통해 Like 도메인이 인프라 독립적인 구조가 되었다. Redis에서 PostgreSQL로의 마이그레이션이 6장에서 얼마나 쉬워졌는지 보여줄 것이다.
