 # ADR-328: Service Layer 모듈화 전략 (Gradle Multi-Module vs Package 규칙)

## 상태
Proposed (2026-03-01)

## 컨텍스트

### 현재 상황
프로젝트는 ADR-003, ADR-005에 따라 Hexagonal Architecture 기반 4-Module 구조(module-core, module-app, module-infra, module-web)로 정리되었다. 그러나 `module-app/service/v2/` 패키지가 비대해지는 문제가 발생했다.

### 기존 이관 작업 현황 (PR #448-475, ADR-003/004/005/009)

**이미 완료된 이관:**

| 대상 | 이관 위치 | 관련 PR/ADR |
|------|----------|-------------|
| Calculator 도메인 (25개) | `module-core/calculator/` | ADR-004, #415 |
| Flame 도메인 (10개) | `module-core/flame/` | ADR-004, #417 |
| Starforce 도메인 (10개) | `module-core/starforce/` | ADR-004, #418 |
| Policy 도메인 (5개) | `module-core/policy/` | ADR-004, #419 |
| Port 인터페이스 (30개+) | `module-core/port/` | ADR-003, #448-457 |
| Controller 6개 | `module-web/controller/` | ADR-005, #470-475 |
| Alert 인프라 | `module-infra/alert/` | ADR-005 |
| Cache 인프라 | `module-infra/cache/` | ADR-009 (진행중) |
| Executor/Resilience | `module-infra/executor/` | ADR-005 |

**현재 module-core 구조 (약 70개 파일):**
```
module-core/src/main/kotlin/maple/expectation/core/
├── calculator/          # ExpectationCalculatorPort, CubeRateCalculator
├── flame/              # FlameTrialsService, FlameScoreResolver
├── starforce/          # StarforceCalculationEngine, NoljangProbabilityCalculator
├── policy/             # CostCalculationStrategy, TableBasedCostStrategy
├── domain/             # CharacterId, Page, PotentialStat 등 VO
├── probability/        # FlameDpCalculator, ProbabilityConvolver
└── port/
    ├── inbound/        # AdminPort, AuthPort, DonationPort, ExpectationV4Port (7개)
    └── outbound/       # AlertPort, QueueWriterPort, LikeSyncPort 등 (30개+)
```

**현재 module-infra 구조 (약 100개 파일):**
```
module-infra/src/main/kotlin/maple/expectation/infrastructure/
├── adapter/            # QueueWriterAdapter, OcidQueryAdapter, PolicyAdapter
├── alert/              # StatelessAlertService, DiscordAlertChannel
├── aop/                # BufferedLikeAspect, LockAspect, LoggingAspect
├── cache/              # TieredCache, TieredCacheManager
├── config/             # 30개 설정 클래스
├── executor/           # CheckedLogicExecutor, ResilientExecutor
└── concurrency/        # DistributedSingleFlightService
```

### 현재 service/v2 구조 (97개 파일 - 여전히 비대)
```
module-app/src/main/java/maple/expectation/service/v2/
├── calculator/          # 기대값 계산 (impl, v4) - 일부 core 이관됨
├── cube/               # 큐브 확률 (component, config, dto) - 이관 유예됨
├── like/               # 좋아요 버퍼 (strategy, realtime, recovery, compensation)
├── cache/              # 캐시 서비스 (→ ADR-009로 이관 중)
├── auth/               # 인증/세션 (ApiKeyValidator, TokenService, SessionService)
├── alert/              # Discord 알림
├── donation/           # 후원 (outbox, listener, payment)
├── outbox/             # Nexon API 아웃박스
├── starforce/          # 스타포스 룩업테이블 - 일부 core 이관됨
├── policy/             # 비용 정책 - core 이관됨
├── worker/             # DB 워커
├── facade/             # Facade 패턴
├── shutdown/           # 종료 데이터 복구
├── flame/              # 플레임 계산 - 일부 core 이관됨
└── [Root Services]     # GameCharacterService, LikeSyncService 등 15개
```

### 문제점: Service 패키지의 다중 관심사 혼재 (Violating SRP)

`service/v2/` 패키지가 **하나의 패키지에 너무 많은 관심사를 담당**하여 지나치게 비대해짐.

#### 1) 외부 시스템 연동 클라이언트/커넥터 → ❌ infra/integration/
```
❌ service에 위치 (현재):
   - monitoring/copilot/client/PrometheusClient.java
   - monitoring/copilot/ingestor/GrafanaJsonIngestor.java

✅ 적절한 위치:
   - infrastructure/integration/prometheus/
   - infrastructure/client/grafana/

💡 서비스는 "Prometheus에서 메트릭 조회"라는 Port 인터페이스만 알아야 함
```

#### 2) 알림 전송 구현체 → ❌ infra/notification/
```
❌ service에 위치 (현재):
   - expectation/service/v2/alert/DiscordAlertService.java
   - expectation/service/v2/alert/Dto/DiscordMessage.java
   - expectation/service/v2/alert/DiscordMessageFactory.java
   - monitoring/copilot/notifier/DiscordNotifier.java

✅ 적절한 위치:
   - infrastructure/notification/discord/
   - infrastructure/alert/channel/

💡 유스케이스(서비스)가 아니라 "메시지 포맷/전송 채널 구현"이라 인프라 관심사
```

#### 3) 캐시/스토리지/Redis 구현 → ❌ infra/cache/
```
❌ service에 위치 (현재):
   - expectation/service/v2/cache/AbstractTieredCacheService.java
   - expectation/service/v2/cache/RedisLikeRelationBufferAdapter.java
   - expectation/service/v2/cache/LikeBufferStorage.java (Caffeine)
   - expectation/service/v2/cache/EquipmentCacheService.java

✅ 적절한 위치:
   - infrastructure/cache/tiered/
   - infrastructure/cache/like/
   - infrastructure/cache/equipment/

💡 "캐시를 어떻게 구현했는지"는 인프라 관심사. 서비스에는 CachePort 인터페이스만 노출
```

#### 4) 배치/스케줄러 실행 코드 → ❌ infra/batch/
```
❌ service에 위치 (현재):
   - expectation/scheduler/ExpectationBatchWriteScheduler.java
   - expectation/service/v2/worker/EquipmentDbWorker.java
   - expectation/service/v2/worker/GameCharacterWorker.java
   - monitoring/copilot/scheduler/*Scheduler.java

✅ 적절한 위치:
   - infrastructure/batch/scheduler/
   - infrastructure/batch/worker/

💡 "언제 돌릴지/잡 실행"은 배치 인프라 관심사
```

#### 5) 기타 인프라 성격 코드
| 현재 위치 | 파일 | 적절한 위치 |
|----------|------|------------|
| `like/realtime/` | RedisLikeEventPublisher, RedisLikeEventSubscriber | infra/pubsub/ |
| `like/strategy/` | LuaScriptAtomicFetchStrategy | infra/cache/strategy/ |
| `outbox/` | NexonApiOutboxProcessor, NexonApiDlqHandler | infra/outbox/ |
| `shutdown/` | ShutdownDataPersistenceService, RedisEquipmentPersistenceTrackerAdapter | infra/persistence/ |

#### 영향 요약
- **SRP 위반**: service가 비즈니스 로직 + 인프라 어댑터 + 이벤트 처리 + 배치까지 담당
- **의존성 복잡도 증가**: service ↔ infra 양방향 의존 발생 위험
- **테스트 어려움**: 인프라 의존성 때문에 service 단위 테스트 시 mock 과다
- **변경 영향 분석 곤란**: 97개 파일에서 특정 기능 변경 시 영향 범위 파악 어려움
- **개발자 온보딩 혼란**: "서비스에 뭐가 있어야 하지?" 경계 불명확

#### 결합도 강화 문제 (Tight Coupling)
```
문제: Service가 구현체에 직접 의존 → 변경 파급 증가

Before (현재 - 강한 결합):
┌─────────────────────────────────────────────────────────────┐
│  LikeSyncService                                            │
│    ├── new RedisLikeRelationBuffer(redissonClient)  ← 구현체 │
│    ├── new LikeBufferStorage(caffeineCache)         ← 구현체 │
│    └── new LuaScriptAtomicFetchStrategy(redis)      ← 구현체 │
└─────────────────────────────────────────────────────────────┘
         ↓ 변경 시
  - Redis → Memcached 전환 시 Service 코드 수정 필요
  - Caffeine → Ehcache 전환 시 Service 코드 수정 필요
  - 테스트 시 모든 인프라 구현체를 mock해야 함

After (Port 기반 - 느슨한 결합):
┌─────────────────────────────────────────────────────────────┐
│  LikeSyncService                                            │
│    ├── LikeBufferPort (interface)                   ← Port  │
│    └── LikeRelationBufferPort (interface)           ← Port  │
└─────────────────────────────────────────────────────────────┘
         ↑ 구현체는 infra에서 주입
  - Redis → Memcached 전환 시 infra adapter만 수정
  - Service 코드 변경 없음 (DIP 준수)
  - 테스트 시 Port만 mock하면 됨
```

**결합도로 인한 구체적 문제:**
1. **기술 교체 비용**: Cache 라이브러리 교체(Caffeine → Ehcache) 시 Service 코드 수정
2. **테스트 복잡도**: Service 단위 테스트 위해 Redis, Caffeine 등 모든 인프라 mock 필요
3. **순환 의존 위험**: infra ↔ service 양방향 참조 발생 가능 (ADR-003에서 이미 경험)
4. **Scale-out 제약**: Service가 Redis에 직접 의존하면 로컬 캐시로 전환 어려움

### 기존 아키텍처 자산
- **ADR-003**: Hexagonal Architecture (Ports & Adapters) 채택 - 30개+ Port 정의
- **ADR-004**: Module-Core 도메인 이관 - calculator, flame, starforce, policy 이관 완료, cube 유예
- **ADR-005**: 모듈 의존성 그래프 확립 (app → core ← infra) - Controller 6개 이관 완료
- **ADR-009**: Cache Service → module-infra 이관 진행 중
- **PR #448-457**: Port 기반 리팩토링 완료
- **PR #470-475**: Web Controller 이관 완료

## 결정 드라이버

| 드라이버 | 설명 | 우선순위 |
|----------|------|----------|
| **의존성 방향 강제** | infra → app 역참조를 구조적으로 차단 | P0 |
| **변경 영향 격리** | 특정 도메인 변경이 다른 도메인에 전파되지 않아야 함 | P0 |
| **점진적 전환** | 한 번에 모든 코드를 이관할 수 없음 | P1 |
| **빌드/배포 영향** | 모듈 분리로 인한 빌드 시간 증가 최소화 | P1 |
| **팀 협업** | 모듈 경계가 명확하여 병렬 개발 가능 | P2 |
| **MSA 분리 가능성** | 향후 서비스 분리 시 용이해야 함 | P2 |

## 고려한 옵션

### Option A: 현행 유지 + ArchUnit 패키지 규칙 강제

**구조:**
```
module-app/service/v2/
├── calculator/
├── cube/
├── like/
└── ...

+ ArchUnit 규칙으로 패키지 간 의존성 제어
```

**장점:**
- 변경 비용 최소 (코드 이동 없음)
- 빌드 시간 영향 없음
- 점진적 규칙 추가 가능

**단점:**
- 규칙 위반이 컴파일 타임에 감지되지 않음 (테스트 실행 시에만)
- 모듈 경계가 논리적일 뿐 물리적이지 않음
- 순환 의존이 발생해도 실행까지 알 수 없음
- MSA 분리 시 전체 재구성 필요

### Option B: Gradle Multi-Module 분리 (모듈러 모놀리스) ✓ 선택

**구조:**
```
settings.gradle
├── module-core           # 순수 도메인, Port 인터페이스
├── module-app            # 유즈케이스, 트랜잭션 경계
├── module-infra          # Port 구현체, 기술 의존
├── module-web            # Controller, Filter, DTO
│
├── module-expectation    # [NEW] 기대값 계산 도메인
│   ├── calculator/
│   ├── cube/
│   ├── starforce/
│   └── flame/
│
├── module-like           # [NEW] 좋아요 도메인
│   ├── buffer/
│   ├── sync/
│   └── recovery/
│
├── module-donation       # [NEW] 후원 도메인
│   ├── payment/
│   ├── outbox/
│   └── listener/
│
└── module-notification   # [NEW] 알림 도메인
    └── alert/
```

**장점:**
- **컴파일 타임 의존성 검증**: 순환 의존, 역참조가 빌드 실패로 즉시 감지
- **명확한 모듈 경계**: 물리적 분리로 변경 영향 범위 명확
- **점진적 MSA 분리**: 향후 특정 모듈만 별도 서비스로 분리 가능
- **빌드 캐싱**: 변경된 모듈만 재빌드
- **협업 격리**: 모듈별 독립 개발 가능

**단점:**
- 초기 이관 비용 발생
- Gradle 설정 복잡도 증가
- 빌드 시간 소폭 증가 (최초 1회)
- IDE 인덱싱 시간 증가

### Option C: 즉시 MSA 분리

**기각 사유:**
- 현재 트래픽 규모에서 오버엔지니어링
- 운영 복잡도 급증 (서비스 간 통신, 분산 트랜잭션)
- 팀 규모 대비 과도한 초기 비용

## 결정

**Option B: Gradle Multi-Module 분리**를 채택한다.

### 선택 근거

1. **구조적 강제력**: ArchUnit은 테스트 실행 시에만 검증하지만, Gradle 모듈은 컴파일 타임에 의존성 위반을 차단
2. **재발 방지**: 물리적 모듈 경계로 인해 "실수로" 역참조하는 것이 불가능
3. **기존 자산 활용**: ADR-003, ADR-005의 Hexagonal 구조를 모듈 레벨로 확장
4. **점진적 전환**: 한 번에 모든 것을 바꾸지 않고, 우선순위에 따라 하나씩 분리

### 추천 모듈 구조

```
[Core Layer]
module-common          # 공통 DTO, 에러, 유틸 (의존성 없음)
module-core            # 순수 도메인, Port 인터페이스 (Spring 의존 금지)

[Domain Layer - NEW]
module-expectation     # 기대값 계산 (calculator, cube, starforce, flame)
module-like            # 좋아요 (buffer, sync, recovery, realtime)
module-donation        # 후원 (payment, outbox, listener)
module-notification    # 알림 (discord, alert)
module-auth            # 인증 (token, session, apikey)

[Application Layer]
module-app             # 유즈케이스, Facade, 트랜잭션 경계

[Infrastructure Layer]
module-infra           # Port 구현체, Redis, DB, External API

[Interface Layer]
module-web             # Controller, Filter, DTO

[Test Layer]
module-chaos-test      # 카오스 테스트
```

### 의존성 규칙

```
module-web      → module-app, module-expectation, module-like, ...
module-app      → module-core, module-expectation, module-like, ...
module-expectation → module-core
module-like     → module-core
module-infra    → module-core (Port 구현)

금지:
❌ module-expectation → module-app
❌ module-expectation → module-infra
❌ module-infra → module-app
```

## 결과

### 긍정적 효과
1. **컴파일 타임 검증**: 의존성 위반이 빌드 실패로 즉시 감지
2. **변경 영향 격리**: 특정 도메인 변경이 타 도메인에 전파되지 않음
3. **MSA 준비**: 향후 module-like, module-donation 등을 별도 서비스로 분리 가능
4. **협업 효율**: 모듈별 독립 개발, 명확한 코드 소유권

### 부정적 효과 / 리스크

| 리스크 | 완화책 |
|--------|--------|
| 초기 이관 비용 | 점진적 전환 (Phase별) |
| 빌드 시간 증가 | Gradle Build Cache 활용 |
| 모듈 경계 설계 실패 | ADR-005 의존성 그래프 준수 |
| 순환 의존 발생 | Port 인터페이스로 분리 |
| 개발자 학습 비용 | 모듈 구조 문서화, 온보딩 가이드 |

## 이행 계획

### 사전 완료 작업 (ADR-003/004/005/009)
- [x] Calculator/Flame/Starforce/Policy → module-core 이관 (ADR-004)
- [x] Port 인터페이스 30개+ 정의 (ADR-003, PR #448-457)
- [x] Controller 6개 → module-web 이관 (ADR-005, PR #470-475)
- [x] Alert 인프라 → module-infra/alert/ 이관 (ADR-005)
- [ ] Cache Service → module-infra 이관 (ADR-009, 진행중)

### Phase 1: Infra 계층 정리 (2주) - service/v2 → module-infra
**목표:** service에서 인프라 구현체를 제거하여 SRP 회복

| 이관 대상 | 현재 위치 | 목표 위치 | Port 필요 |
|----------|----------|----------|-----------|
| `cache/*` | service/v2/cache/ | module-infra/cache/ | LikeBufferPort (ADR-009) |
| `like/realtime/*` | service/v2/like/realtime/ | module-infra/cache/like/ | LikeEventPort (존재) |
| `like/strategy/*` | service/v2/like/strategy/ | module-infra/cache/like/ | AtomicFetchStrategy (존재) |
| `worker/*` | service/v2/worker/ | module-infra/batch/ | 없음 (Infra 내부) |
| `outbox/*` | service/v2/outbox/ | module-infra/outbox/ | NexonApiOutboxPort (존재) |
| `alert/*` | service/v2/alert/ | module-infra/alert/ | AlertPort (존재) |

**작업 항목:**
1. [ ] ADR-009 완료: LikeBufferPort, LikeRelationBufferPort 정의 및 이관
2. [ ] `like/realtime/` Redis Pub/Sub 구현체 → module-infra 이관
3. [ ] `worker/` DB 배치 워커 → module-infra/batch/ 이관
4. [ ] `outbox/` 아웃박스 구현체 → module-infra/outbox/ 이관
5. [ ] 각 이관 후 단위 테스트 검증

### Phase 2: Cube 도메인 재검토 (1주)
**상황:** ADR-004에서 Cube 이관을 유예했으나, CubeRatePort, CubeCostPort가 이미 정의됨

**옵션:**
1. **module-core/cube/ 이관**: 순수 계산 로직만 이관 (권장)
2. **현행 유지**: service/v2/cube/ 유지

**결정 필요:**
- [ ] Cube 이관 재검토 (ADR-004 Phase 4)

### Phase 3: 새로운 도메인 모듈 생성 여부 결정 (1주)
**기준:** service/v2 정리 후 잔여 파일 수가 30개 이상이면 새 모듈 생성 검토

**후보:**
- `module-like`: like 관련 서비스 (현재 20개 파일)
- `module-donation`: donation 관련 서비스 (현재 10개 파일)
- `module-auth`: auth 관련 서비스 (현재 8개 파일)

**결정:**
- [ ] Phase 1 완료 후 잔여 파일 분석
- [ ] 새 모듈 생성 vs 기존 module-app 유지 결정

### Phase 4: ArchUnit 규칙 강화 및 CI 적용 (1주)
1. [ ] `service` → `infra` 직접 참조 금지 규칙 추가
2. [ ] Port 통해서만 infra 접근 허용 규칙
3. [ ] CI 파이프라인에 ArchUnit 테스트 추가
4. [ ] 문서화 업데이트 (CLAUDE.md, ROADMAP.md)

## 검증 방법

### 컴파일 타임
```bash
# 역참조 확인 (빌드 실패해야 정상)
# module-expectation에서 module-app import 시도
./gradlew :module-expectation:compileJava  # 실패 예상
```

### ArchUnit 테스트
```kotlin
@ArchTest
val domainModuleShouldNotDependOnApp: ArchRule = noClasses()
    .that().resideInAPackage("..expectation..")
    .should().dependOnClassesThat()
    .resideInAPackage("..service.v2..")
    .because("Domain modules must not depend on application layer")
```

### CI 검증
```bash
# 전체 빌드
./gradlew clean build

# 모듈별 테스트
./gradlew :module-expectation:test
./gradlew :module-like:test
```

## 대안 비교표

| 기준 | Option A (ArchUnit) | Option B (Multi-Module) | Option C (MSA) |
|------|---------------------|------------------------|----------------|
| 검증 시점 | 테스트 타임 | 컴파일 타임 | 런타임 |
| 변경 비용 | 낮음 | 중간 | 높음 |
| 재발 방지 | 약함 | 강함 | 강함 |
| MSA 준비 | 없음 | 있음 | 완료 |
| 빌드 시간 | 변화 없음 | +10% | +50%+ |
| 운영 복잡도 | 낮음 | 낮음 | 높음 |

## 관련 문서
- ADR-003: Hexagonal Architecture 채택
- ADR-005: 모듈 의존성 그래프 및 이관 전략
- ADR-009: Cache Service를 module-infra로 이관
- CLAUDE.md: Section 4 (SOLID), Section 16 (Proactive Refactoring)

## 이력

| 날짜 | 상태 | 변경 사항 |
|------|------|-----------|
| 2026-03-01 | Proposed | 초기 초안 작성, Option B 선택 근거 제시 |
| 2026-03-01 | Updated | 기존 이관 작업 현황(PR #448-475, ADR-003/004/005/009) 반영 |
| 2026-03-01 | Updated | 구체적 위반 사례 추가 (외부연동/알림/캐시/배치/결합도 문제) |
