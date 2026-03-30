# ADR-317: Hexagonal Architecture (Ports # ADR-003: Hexagonal Architecture (Ports & Adapters) 채택 Adapters) 채택

## 상태
Accepted (2026-02-28)

## 컨텍스트

현재 프로젝트는 Java → Kotlin 마이그레이션과 4-Module 분리를 진행 중이다 (ADR-002).

**반복적으로 발생하는 문제:**

1. **역방향 의존 (Architecture Violation)**
   - module-infra가 module-app의 서비스/타입을 참조
   - 예: `LowPriorityQueueWriter`(batch) → `PriorityCalculationQueue`(service)
   - 이관 시도 시 순환 의존/컴파일 오류 발생

2. **배치/리스너/파서의 강한 결합**
   - infra 컴포넌트가 app 서비스 구현체를 직접 호출
   - 변경의 파급이 모듈 경계를 넘어 전파됨

3. **마이그레이션 비용 증가**
   - Java/Kotlin interop으로 인한 타입/컬렉션 변환 비용
   - 변경 범위 파악 어려움

**출시 전 구조적 안정화 필요:**
- 출시 후 구조 변경 비용이 급격히 증가
- 결합도 문제를 구조적으로 해결해야 함

## 결정

모듈러 모놀리스 내에서 **Hexagonal Architecture (Ports & Adapters)** 를 아키텍처 규칙으로 채택한다.

### 핵심 규칙

```
app → core ← infra ✅ (올바른 의존성 방향)
infra → app ❌ (금지)
```

1. **App (Usecase/Application)**: 인프라 구현체를 직접 참조하지 않고 **Port (Interface)** 에만 의존
2. **Infra (Adapter/Implementation)**: Port를 구현하며, App을 참조하지 않음
3. **Core/Common**: 모델/Port만 보관
4. **Wiring**: Spring Bean 조립은 App의 `@Configuration`에서 수행
5. **외부 시스템**: AI/OpenAI, Redis, Kafka, Scheduler/Batch, HTTP Client는 Adapter로 격리

### 패키지 구조

```
module-core/src/main/kotlin/maple/expectation/core/
├── domain/          # 도메인 모델
│   └── model/       # Page, PageRequest (Spring 의존성 제거)
└── port/
    ├── in/          # Inbound Port (UseCase 인터페이스)
    └── out/         # Outbound Port (Infra가 구현) - 30개
        ├── AlertPort.kt
        ├── AlertPublisher.kt
        ├── AtomicFetchStrategy.kt
        ├── BackoffStrategy.kt
        ├── BufferStatusQuery.kt
        ├── CacheWarmupPort.kt              # PR #455
        ├── CubeRatePort.kt
        ├── EquipmentDataPort.kt
        ├── EventPublisher.kt
        ├── GameCharacterPort.kt
        ├── ItemPricePort.kt
        ├── LikeBufferStrategy.kt
        ├── LikeEventPort.kt
        ├── LikeRelationBufferStrategy.kt
        ├── LikeRelationSyncPort.kt         # PR #451
        ├── LikeSyncPort.kt                 # PR #451
        ├── MessageQueue.kt
        ├── MessageTopic.kt
        ├── NexonApiOutboxMetricsPort.kt    # PR #454
        ├── NexonApiOutboxProcessorPort.kt  # PR #454
        ├── NexonDataCollectorPort.kt       # PR #456
        ├── OcidQueryPort.kt                # PR #449
        ├── OutboxMetricsPort.kt            # PR #453
        ├── OutboxProcessorPort.kt          # PR #453
        ├── PersistenceTrackerStrategy.kt
        ├── PolicyPort.kt
        ├── PopularCharacterTrackerPort.kt  # PR #455
        ├── PotentialStatPort.kt
        ├── QueueWriterPort.kt              # PR #448
        └── TokenPort.kt

module-infra/src/main/kotlin/maple/expectation/infra/
├── adapter/
│   ├── incoming/    # Inbound Adapter (Controller, Listener)
│   └── outgoing/    # Outbound Adapter (Repository, Client)
└── config/          # Infra 설정만

module-app/src/main/java/maple/expectation/
├── application/     # Usecase (Port 사용)
├── batch/           # 배치 (Port 사용)
├── scheduler/       # 스케줄러 (Port 사용)
└── config/          # Wiring (@Configuration)
```

### 점진적 리팩토링 절차

```
1. 역참조 지점 식별 (infra → app import 찾기)
2. Port 인터페이스 추출 → module-core/port/out/
3. Adapter 구현 → module-infra/adapter/outgoing/
4. App 코드가 Port만 참조하도록 수정
5. Wiring 조립 → module-app/config/
6. 컴파일/테스트 검증
7. 이관 (선택적)
```

## 근거

1. **결합도 감소**: 변경의 파급이 모듈 경계에서 멈춤
2. **마이그레이션 용이**: 역참조/순환 의존을 구조적으로 방지
3. **테스트 용이**: Usecase는 Port를 mock/stub하여 빠른 단위 테스트 가능
4. **장기 확장**: 추후 MSA 분리 가능성을 열어둠
5. **Kotlin 효율**: 간결한 모델/Port 설계로 보일러플레이트 감소

## 적용 범위

### 적용 완료 (PR #448-457)

| PR | 대상 | Port 인터페이스 | 상태 |
|----|------|-----------------|------|
| #448 | LowPriorityQueueWriter | QueueWriterPort | ✅ 완료 |
| #449 | OcidReader | OcidQueryPort | ✅ 완료 |
| #450 | MonitoringReportJob | AlertPort (기존) | ✅ 완료 |
| #451 | LikeSyncScheduler | LikeSyncPort, LikeRelationSyncPort | ✅ 완료 |
| #452 | ExpectationCalculationScheduler | QueueWriterPort (재사용) | ✅ 완료 |
| #453 | OutboxScheduler | OutboxProcessorPort, OutboxMetricsPort | ✅ 완료 |
| #454 | NexonApiOutboxScheduler | NexonApiOutboxProcessorPort, NexonApiOutboxMetricsPort | ✅ 완료 |
| #455 | PopularCharacterWarmupScheduler | PopularCharacterTrackerPort, CacheWarmupPort | ✅ 완료 |
| #456 | NexonDataCollector | NexonDataCollectorPort | ✅ 완료 |
| #457 | Test 업데이트 | Port 기반 테스트 | ✅ 완료 |

### 비적용/예외

- 순수 도메인 로직 내부 호출은 Port로 추상화하지 않음 (과도한 인터페이스 생성 방지)
- 단일 모듈 내부의 단순 유틸/헬퍼는 예외 허용

## 결과

### 긍정적

- infra → app 역참조 0 (아키텍처 규칙 준수)
- 배치/리스너/인프라 이관이 점진적으로 가능
- 테스트 속도/안정성 향상 (Port mocking)
- Kotlin 마이그레이션 시 타입/보일러플레이트 감소

### 부정적 / 트레이드오프

- Port/Adapter 설계 비용 증가 (초기 설계 시간 필요)
- interface가 과도하게 늘어날 위험 → "필요한 만큼만" 규칙 준수
- Wiring 구조 정리 필요

## 검증 규칙

### ArchUnit 테스트

```kotlin
// module-infra → module-app 의존 금지
@ArchTest
val infraShouldNotDependOnApp: ArchRule = noClasses()
    .that().resideInAPackage("..infra..")
    .should().dependOnClassesThat()
    .resideInAPackage("..service.v2..")
    .orShould().dependOnClassesThat()
    .resideInAPackage("..service.v4..")
    .orShould().dependOnClassesThat()
    .resideInAPackage("..service.v5..")
    .check(importedClasses)
```

### CI 검증

```bash
# 역참조 확인
grep -r "import maple.expectation.service" module-infra/src --include="*.kt" --include="*.java" | wc -l
# Expected: 0

# 빌드/테스트
./gradlew clean build
./gradlew test -PfastTest
```

## 대안

### A1: 현 구조 유지 + 필요 시만 부분 이관

- **장점**: 단기 비용 낮음
- **단점**: 결합 문제 반복/누적, 기술부채 증가
- **기각 사유**: 출시 후 변경 비용 급증

### A2: Common에 Spring-Web 등 인프라 의존 허용

- **장점**: 구현 용이
- **단점**: 경계 무너짐, 유지보수 비용 증가
- **기각 사유**: Clean Architecture 원칙 위반

## 관련 문서

- ADR-002: 4-Module Separation + Kotlin Migration
- CLAUDE.md: Section 4 (Implementation Logic & SOLID), Section 16 (Proactive Refactoring)
- Port 인터페이스: `module-core/src/main/kotlin/maple/expectation/core/port/out/` (30개)

## 이력

| 날짜 | 상태 | 변경 사항 |
|------|------|-----------|
| 2026-02-28 | Proposed | 초기 초안 작성 |
| 2026-02-28 | Accepted | PR #448-457 Hexagonal Architecture 리팩토링 완료, 30개 Port 인터페이스 정의 |
