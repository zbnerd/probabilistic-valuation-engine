# 4장: 아키텍처 대전환 — 모듈의 분리

> *2026년 2월 6일 ~ 2026년 2월 28일*

---

## 4.1 배경: 단일 모듈의 한계

2026년 2월 초, Like 도메인은 기능적으로는 안정되었지만, **구조적 부채**가 쌓여 있었다.

```
src/main/java/maple/expectation/
├── controller/     (HTTP)
├── service/v2/like/ (비즈니스 로직 + Redis + DB)
├── global/queue/like/ (인프라)
├── domain/         (엔티티)
└── repository/     (DB 접근)
```

문제점:
- **비즈니스 로직과 인프라가 뒤섞임**: LikeSyncService가 Redis 작업과 DB 작업을 동시에 수행
- **순환 의존**: 여러 계층이 서로를 참조
- **테스트 어려움**: 인프라 없이 순수 비즈니스 로직만 테스트하기 불가능

---

## 4.2 멀티모듈 마이그레이션 시작

2월 13일, 대대적인 모듈 분리가 시작되었다.

```
65f6c168 refactor: 멀티모듈 마이그레이션 및 코드 품질 개선 (Issue #282)
```

이슈 #282:
> *"멀티모듈 마이그레이션 및 코드 품질 개선"*

### 목표 모듈 구조

```
probabilistic-valuation-engine/
├── module-app/       (Application Layer — Controller, Scheduler)
├── module-core/      (Domain Layer — Entities, Ports, DTOs)
├── module-infra/     (Infrastructure Layer — Redis, DB, External API)
└── module-common/    (Cross-cutting — LogicExecutor, Exceptions)
```

### Like 도메인의 분리 계획

| 계층 | 이동할 컴포넌트 | 목적지 |
|------|----------------|--------|
| Domain | CharacterLike, LikeId | module-core |
| Port | LikeBufferStrategy, LikeAtomicFetchStrategy | module-core |
| Infra | RedisLikeBufferStorage, AtomicLikeToggleExecutor | module-infra |
| App | LikeSyncScheduler, CharacterLikeService | module-app |
| Common | LogicExecutor, ErrorCode | module-common |

---

## 4.3 Java → Kotlin 마이그레이션

2월 17~18일, 프로젝트 전체의 Kotlin 마이그레이션이 진행되었다.

```
#350 refactor: Migrate module-common from Java to Kotlin
#352 docs: Chaos Engineering 문서 구조 개선
```

### Like 도메인에서 Kotlin으로 마이그레이션된 것들

module-core의 Like 관련 클래스들이 Kotlin으로 전환:

```kotlin
// Before (Java)
public class CharacterLike {
    private Long id;
    private String targetOcid;
    private String likerAccountId;
    private LocalDateTime createdAt;
}

// After (Kotlin)
data class CharacterLike(
    val id: Long? = null,
    val targetOcid: String,
    val likerAccountId: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
```

> *참고: 마이그레이션은 점진적으로 진행되었다. Java-Kotlin interop 문제가 단계적으로 해결되며, 한 번에 전환된 것이 아니다.*

### 4.3.1 마이그레이션의 어려움

2월 24~25일, Java-Kotlin 상호 운용성 문제가 연달아 발생:

```
f9456442 feat: Java-to-Kotlin 마이그레이션 Phase 1-1, 2-1, 2-2 완료 (#390)
c241443d fix: Java-Kotlin interop 컴파일 에러 수정
1e2f8d21 fix: Java-Kotlin interop - Add MemberRepository bean and fix Spring CGLIB proxying
b2075654 feat: Kotlin-Java Interop 타입 불일치 수정 및 모니터링 모듈 마이그레이션 (#383, #384)
```

**주요 문제들**:

| 문제 | 원인 | 해결 |
|------|------|------|
| Bean 찾을 수 없음 | Kotlin의 `data class`는 기본 생성자 필요 | `@NoArgsConstructor` 추가 |
| CGLIB 프록시 실패 | Kotlin `class`는 기본 final | `open` 키워드 추가 |
| Nullable 불일치 | Kotlin의 null-safety vs Java의 nullable | Platform type 명시 |
| 생성자 인자 순서 | Kotlin named parameter vs Java positional | `@JvmOverloads` 추가 |

---

## 4.4 모듈 분리 Phase 2

2월 28일, 점진적 마이그레이션이 계속되었다.

```
ca912a96 feat: Module separation Phase 2 - Gradual migration (#445)
2b82a29c refactor: 기술부채 해결 - BatchScheduler 및 DTO 패키지 정리 (#447)
026c047a fix: Kotlin DTO nullability 및 생성자 이슈 수정 (#444)
```

### 4.4.1 DTO nullability 문제

Kotlin DTO에서 `null` 허용 여부가 Java 코드와 충돌:

```kotlin
// 문제: Java에서 null을 전달할 수 있었는데 Kotlin이 막음
data class LikeEvent(
    val userIgn: String,        // non-null
    val delta: Long,            // non-null
    val eventType: String       // non-null — Java에서 null 전달 시 컴파일 에러
)
```

---

## 4.5 이 시점의 모듈 구조 (2월 말)

```
module-core/
├── domain/model/like/
│   ├── CharacterLike.kt
│   ├── LikeId.kt
│   ├── LikeToggleResult.kt
│   └── LikeToggleWithCount.kt
├── dto/like/
│   ├── LikeEvent.kt
│   └── FetchResult.kt
└── port/out/like/
    ├── LikeAtomicFetchStrategy.kt
    └── CompensationCommand.kt

module-infra/
├── cache/like/
│   └── InMemoryLikeBufferStorage.kt
└── queue/like/
    ├── LikeSyncExecutor.kt
    └── event/
        └── LikeSyncFailedEvent.kt

module-app/
└── service/like/
    ├── LikeToggleService.java
    ├── LikeProcessor.java
    ├── DatabaseLikeProcessor.java
    ├── OcidResolutionService.java
    ├── listener/
    │   └── LikeSyncEventListener.java
    └── metrics/
        └── LikeSyncMetricsRecorder.java
```

### 아직 해결되지 않은 것

- LikeSyncScheduler가 아직 module-app에 남아 있음
- module-infra에 아직 Redis 의존성이 존재
- 헥사고날 아키텍처의 Port/Adapter 패턴이 완전히 적용되지 않음
- Java-Kotlin interop 경계에서 타입 안전성 미흡

이 문제들은 5장에서 다룬다.
