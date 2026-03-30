# probabilistic-valuation-engine 종합 데이터 흐름 다이어그램

> **작성일:** 2026-03-10
> **분석 방식:** 실제 코드베이스 심층 분석 (4개 에이전트 병렬 분석)
> **버전:** 2.0.0
> **검증 상태:** 실제 코드 기반 검증 완료

---

## 1. 핵심 기술 스택 (실제 코드 기반)

### 1.1 언어 및 프레임워크
| 기술 | 버전 | 적용 위치 |
|------|------|----------|
| **Java 21** | Virtual Threads, Records | module-app, module-web |
| **Kotlin** | - | module-infra, module-core, module-common |
| **Spring Boot** | 3.5.4 | 전체 |
| **Spring Data JPA** | - | Repository 계층 |

### 1.2 인프라 (실제 구현 기반)
| 기술 | 구현체 | 용도 |
|------|--------|------|
| **L1 Cache** | Caffeine | 로컬 캐시 (TieredCache.l1) |
| **L2 Cache** | PostgreSQL / Redis (Redisson) | 분산 캐시 (TieredCache.l2) |
| **Lock** | PostgresAdvisoryLock, RedisLock | 분산 락 |
| **Resilience** | Resilience4j | Circuit Breaker, Retry |
| **Database** | PostgreSQL 16 + GZIP | 영구 저장소 (메인) |
| **Pub/Sub** | PostgreSQL LISTEN/NOTIFY | 캐시 무효화, 이벤트 전파 |

### 1.3 모듈 구조 (settings.gradle 기반)
```
probabilistic-valuation-engine/
├── module-core/       # 도메인 모델, Port 인터페이스
├── module-common/     # 예외, 유틸리티, 응답 타입
├── module-infra/      # 인프라 구현체 (Kotlin)
├── module-app/        # 애플리케이션 서비스 (Java)
├── module-web/        # 컨트롤러, 필터, DTO
└── module-chaos-test/ # 카오스 테스트
```

---

## 2. 시스템 전체 데이터 흐름도

```mermaid
flowchart TB
    subgraph Client["클라이언트 계층"]
        Browser["브라우저/모바일"]
    end

    subgraph Edge["엣지 계층 (module-web)"]
        RL["Rate Limiter<br/>(Bucket4j)"]
        JWT["JWT Filter"]
        MDC["MDC Filter<br/>(TraceId)"]
    end

    subgraph AppService["애플리케이션 서비스 (module-app)"]
        subgraph V4Facade["V4 Facade"]
            V4Svc["EquipmentExpectationServiceV4"]
            V4Cache["ExpectationCacheCoordinator"]
            V4Persist["ExpectationPersistenceService"]
            V4Helper["PresetCalculationHelper"]
        end

        subgraph V2Core["V2 핵심 서비스"]
            CharFacade["GameCharacterFacade"]
            CharSvc["GameCharacterService"]
            CharSync["GameCharacterSynchronizer"]
            LikeSvc["LikeSyncService"]
        end

        subgraph Calculator["계산기 모듈"]
            CalcFactory["EquipmentExpectationCalculatorFactory"]
            BaseItem["BaseEquipmentItem"]
            BlackCube["BlackCubeDecoratorV4"]
            AddCube["AdditionalCubeDecoratorV4"]
            Starforce["StarforceDecoratorV4"]
        end

        subgraph CubeEngine["큐브 엔진"]
            CubeSvc["CubeServiceImpl"]
            CubeDP["DpModeInferrer<br/>+ Convolution"]
        end
    end

    subgraph Infra["인프라 계층 (module-infra)"]
        subgraph CacheLayer["캐시 계층"]
            TieredCache["TieredCache<br/>(L1 + L2)"]
            L1Cache["L1: Caffeine"]
            L2Cache["L2: Redis/PostgreSQL"]
            SingleFlight["DistributedSingleFlight<br/>(Redis Lock)"]
        end

        subgraph LockLayer["락 계층"]
            RedisLock["RedisDistributedLockStrategy"]
            MySqlLock["MySqlNamedLockStrategy"]
            PgLock["PostgresLockStrategy"]
            ResilientLock["ResilientLockStrategy<br/>(Redis → PostgreSQL Fallback)"]
        end

        subgraph ResilienceLayer["회복 탄력성"]
            CB["CircuitBreaker<br/>(50%/10회)"]
            Retry["Retry (3회)"]
            TL["TimeLimiter (28s)"]
            PostgreSQLState["PostgreSQLHealthState<br/>(HEALTHY→DEGRADED→RECOVERING)"]
        end

        subgraph ExternalLayer["외부 API"]
            NexonClient["ResilientNexonApiClient"]
            RealClient["RealNexonApiClient<br/>(WebClient)"]
            Fallback["NexonApiFallbackService"]
        end
    end

    subgraph Storage["저장소"]
        PostgreSQL[("PostgreSQL 8.0<br/>+ GZIP 압축")]
        Redis[("Redis<br/>Master-Slave + Sentinel")]
    end

    subgraph External["외부"]
        NexonAPI["Nexon Open API"]
    end

    %% 클라이언트 → 엣지
    Browser --> RL --> JWT --> MDC

    %% 엣지 → 서비스
    MDC --> V4Svc
    MDC --> CharFacade

    %% V4 서비스 내부
    V4Svc --> V4Cache
    V4Svc --> V4Persist
    V4Svc --> CharFacade
    V4Svc --> CalcFactory

    %% 계산기 체인
    CalcFactory --> BaseItem
    BaseItem --> BlackCube
    BlackCube --> AddCube
    AddCube --> Starforce

    %% V2 서비스 내부
    CharFacade --> CharSvc
    CharFacade --> CharSync
    CharSvc --> TieredCache

    %% 캐시 계층
    TieredCache --> L1Cache
    L1Cache -.->|"MISS"| L2Cache
    L2Cache -.->|"Backfill"| L1Cache
    TieredCache --> SingleFlight

    %% 락 계층
    SingleFlight --> ResilientLock
    ResilientLock --> RedisLock
    RedisLock -.->|"Fallback"| MySqlLock

    %% 회복 탄력성
    CharSvc --> CB --> Retry --> TL --> NexonClient
    NexonClient --> RealClient
    RealClient --> NexonAPI
    CB -.->|"OPEN"| Fallback
    Fallback --> PostgreSQL

    %% 저장소
    L2Cache --> Redis
    V4Persist --> PostgreSQL
    CharSvc --> PostgreSQL

    %% 스타일
    classDef client fill:#e1f5fe
    classDef edge fill:#fff3e0
    classDef v4 fill:#c8e6c9
    classDef v2 fill:#a5d6a7
    classDef calc fill:#dcedc8
    classDef infra fill:#fce4ec
    classDef storage fill:#f3e5f5
    classDef external fill:#e0f2f1

    class Browser client
    class RL,JWT,MDC edge
    class V4Svc,V4Cache,V4Persist,V4Helper v4
    class CharFacade,CharSvc,CharSync,LikeSvc v2
    class CalcFactory,BaseItem,BlackCube,AddCube,Starforce,CubeSvc,CubeDP calc
    class TieredCache,L1Cache,L2Cache,SingleFlight,RedisLock,MySqlLock,PgLock,ResilientLock,CB,Retry,TL,PostgreSQLState,NexonClient,RealClient,Fallback infra
    class PostgreSQL,Redis storage
    class NexonAPI external
```

---

## 3. V4 기대값 계산 상세 시퀀스

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant V4Svc as EquipmentExpectationServiceV4
    participant CacheCoord as ExpectationCacheCoordinator
    participant TieredCache as TieredCache
    participant L1 as L1 Caffeine
    participant L2 as L2 Redis
    participant SF as SingleFlight (Redis Lock)
    participant CharFacade as GameCharacterFacade
    participant CharSvc as GameCharacterService
    participant EquipProvider as EquipmentDataProvider
    participant NexonClient as ResilientNexonApiClient
    participant CalcFactory as CalculatorFactory
    participant DB as PostgreSQL

    Client->>V4Svc: calculateExpectationAsync(ign)
    V4Svc->>V4Svc: supplyAsync (equipmentExecutor)
    activate V4Svc

    V4Svc->>CacheCoord: getOrCalculate(ign, force, loader)
    CacheCoord->>TieredCache: get(key, Callable)

    %% L1 조회
    TieredCache->>L1: l1.get(key)
    alt L1 HIT
        L1-->>TieredCache: ValueWrapper
        TieredCache-->>CacheCoord: 캐시된 결과
    else L1 MISS
        %% L2 조회
        TieredCache->>L2: l2.get(key)
        alt L2 HIT
            L2-->>TieredCache: ValueWrapper
            TieredCache->>L1: l1.put(key, value) [Backfill]
            TieredCache-->>CacheCoord: 캐시된 결과
        else L2 MISS
            %% SingleFlight 분산 락
            TieredCache->>SF: tryLock(cache:sf:key)
            alt Lock 획득 (Leader)
                SF-->>TieredCache: acquired=true
                TieredCache->>TieredCache: Double Check L2
                TieredCache->>V4Svc: loader.call() [실제 계산]

                %% 캐릭터 조회
                V4Svc->>CharFacade: findCharacter(ign)
                CharFacade->>CharSvc: getCharacterIfExist(ign)
                CharSvc-->>CharFacade: Optional<GameCharacter>

                alt 캐릭터 존재
                    CharFacade-->>V4Svc: GameCharacter
                else 캐릭터 없음
                    V4Svc->>CharSvc: createNewCharacter(ign)
                    CharSvc->>NexonClient: API 호출
                    NexonClient-->>CharSvc: 캐릭터 데이터
                    CharSvc->>DB: 저장 (비동기)
                    CharSvc-->>V4Svc: GameCharacter
                end

                %% 장비 데이터 로드
                V4Svc->>EquipProvider: getRawEquipmentData(characterId)
                EquipProvider->>NexonClient: API 호출 (Resilience4j)
                NexonClient-->>EquipProvider: JSON (GZIP)
                EquipProvider-->>V4Svc: byte[]

                %% 프리셋 계산 (병렬)
                loop 프리셋 1~3
                    V4Svc->>CalcFactory: create(inputs)
                    CalcFactory->>CalcFactory: Decorator Chain 조립
                    CalcFactory-->>V4Svc: EquipmentExpectationCalculator
                    V4Svc->>CalcFactory: calculateCost()
                    CalcFactory-->>V4Svc: BigDecimal
                end

                V4Svc-->>TieredCache: EquipmentExpectationResponseV4
                TieredCache->>L2: l2.put(key, value)
                TieredCache->>L1: l1.put(key, value)
            else Lock 대기 (Follower)
                SF-->>TieredCache: 대기 후 Leader 결과 반환
            end
        end
    end

    CacheCoord-->>V4Svc: 결과
    V4Svc->>DB: 영속화 (비동기 Write-Behind)
    V4Svc-->>Client: CompletableFuture<Response>
    deactivate V4Svc
```

---

## 4. TieredCache 상세 흐름 (실제 코드 기반)

```mermaid
flowchart TB
    subgraph Request["캐시 요청"]
        Start["get(key, Callable)"]
    end

    subgraph L1Layer["L1: Caffeine"]
        L1Get["l1.get(key)"]
        L1Check{"null?"}
        L1Hit["반환 + L1 Hit Counter++"]
    end

    subgraph L2Layer["L2: Redis/PostgreSQL"]
        L2Get["l2.get(key)"]
        L2Check{"null?"}
        L2Hit["반환 + L2 Hit Counter++"]
        Backfill["l1.put(key, value)<br/>(Backfill)"]
    end

    subgraph SingleFlight["SingleFlight (분산 락)"]
        LockKey["lockKey = cache:sf:name:key"]
        TryLock["redisOperationPort.tryLock()"]
        LockCheck{"acquired?"}
        DoubleCheck["L2 Double Check"]
        Loader["valueLoader.call()"]
        WaitLock["Lock 대기"]
    end

    subgraph Store["캐시 저장"]
        StoreL2["l2.put(key, value)"]
        StoreL1["l1.put(key, value)"]
        PublishInvalidation["Pub/Sub 무효화 이벤트"]
    end

    subgraph Metrics["메트릭"]
        HitL1["cache.hit{layer=L1}"]
        HitL2["cache.hit{layer=L2}"]
        Miss["cache.miss"]
        LockFail["cache.lock.failure"]
    end

    Start --> L1Get
    L1Get --> L1Check
    L1Check -->|"HIT"| L1Hit
    L1Hit --> HitL1

    L1Check -->|"MISS"| L2Get
    L2Get --> L2Check
    L2Check -->|"HIT"| Backfill
    Backfill --> L2Hit
    L2Hit --> HitL2

    L2Check -->|"MISS"| LockKey
    LockKey --> TryLock
    TryLock --> LockCheck

    LockCheck -->|"Leader"| DoubleCheck
    DoubleCheck -->|"여전히 MISS"| Loader
    Loader --> Miss
    Loader --> StoreL2
    StoreL2 --> StoreL1
    StoreL1 --> PublishInvalidation

    LockCheck -->|"Follower"| WaitLock
    WaitLock --> DoubleCheck

    style L1Check fill:#e3f2fd
    style L2Check fill:#fce4ec
    style LockCheck fill:#fff3e0
    style Loader fill:#e8f5e9
```

---

## 5. Lock Strategy 선택 다이어그램 (실제 구현 기반)

```mermaid
flowchart TB
    subgraph Config["설정 (lock.impl)"]
        Redis["redis (기본값)"]
        PostgreSQL["postgresql"]
        PostgreSQL["postgres"]
    end

    subgraph Strategies["Lock 구현체"]
        RedisLock["RedisDistributedLockStrategy<br/>- Redisson RLock<br/>- Watchdog 자동 갱신<br/>- < 1ms 지연"]
        MySqlLock["MySqlNamedLockStrategy<br/>- GET_LOCK/RELEASE_LOCK<br/>- 세션 고정 방식"]
        PgLock["PostgresLockStrategy<br/>- pg_try_advisory_lock<br/>- FNV-1a 해시<br/>- 1-3ms 지연"]
        ResilientLock["ResilientLockStrategy<br/>- Redis → PostgreSQL Fallback<br/>- Circuit Breaker 적용"]
    end

    subgraph Usage["사용 사례"]
        SingleFlight["SingleFlight 락<br/>(캐시 스탬프 방지)"]
        LeaderElection["리더 선출<br/>(캐릭터 동기화)"]
        CriticalSection["임계 영역 보호"]
    end

    Redis --> RedisLock
    PostgreSQL --> MySqlLock
    PostgreSQL --> PgLock

    RedisLock --> ResilientLock
    MySqlLock -.->|"Fallback"| ResilientLock

    ResilientLock --> SingleFlight
    PgLock --> LeaderElection
    RedisLock --> CriticalSection

    style ResilientLock fill:#4caf50,color:#fff
    style PgLock fill:#2196f3,color:#fff
```

---

## 6. 회복 탄력성 (Resilience4j) 구조

```mermaid
flowchart TB
    subgraph Request["서비스 호출"]
        Svc["GameCharacterService"]
    end

    subgraph ResilienceStack["Resilience4j 스택"]
        subgraph TimeLimiter["TimeLimiter"]
            TLConfig["timeout: 28초<br/>cancelRunningFuture: true"]
        end

        subgraph CircuitBreaker["Circuit Breaker"]
            CBConfig["slidingWindow: 10<br/>failureThreshold: 50%<br/>waitDuration: 10s"]
        end

        subgraph Retry["Retry"]
            RTConfig["maxAttempts: 3<br/>waitDuration: 500ms<br/>대상: TimeoutException<br/>WebClientRequestException"]
        end

        subgraph Bulkhead["Bulkhead"]
            BHConfig["maxConcurrent: 50<br/>maxWait: 500ms"]
        end
    end

    subgraph PostgreSQLState["PostgreSQL 상태 머신"]
        Healthy["HEALTHY<br/>정상"]
        Degraded["DEGRADED<br/>TTL 무한, Fallback 활성"]
        Recovering["RECOVERING<br/>Compensation Sync"]
    end

    subgraph Target["대상"]
        API["Nexon Open API"]
        FB["Fallback Service<br/>(DB + 직접 호출)"]
    end

    Svc --> TLConfig
    TLConfig --> CBConfig
    CBConfig --> RTConfig
    RTConfig --> BHConfig
    BHConfig --> API

    %% 상태 전이
    Healthy -->|"CB OPEN"| Degraded
    Degraded -->|"CB CLOSED"| Recovering
    Recovering -->|"5초"| Healthy

    %% Fallback
    CBConfig -.->|"OPEN"| FB

    style Healthy fill:#4caf50,color:#fff
    style Degraded fill:#f44336,color:#fff
    style Recovering fill:#ff9800,color:#fff
```

---

## 7. LogicExecutor 실행 패턴 (실제 코드 기반)

```mermaid
flowchart TB
    subgraph Patterns["6가지 실행 패턴"]

        subgraph P1["패턴 1: execute"]
            P1Code["execute(task, context)<br/>일반 실행<br/>예외 시 로그 + 전파"]
        end

        subgraph P2["패턴 2: executeVoid"]
            P2Code["executeVoid(task, context)<br/>반환값 없는 작업<br/>Runnable 실행"]
        end

        subgraph P3["패턴 3: executeOrDefault"]
            P3Code["executeOrDefault(task, default, context)<br/>안전한 기본값 반환<br/>Negative Cache 확인 등"]
        end

        subgraph P4["패턴 4: executeWithRecovery"]
            P4Code["executeWithRecovery(task, recovery, context)<br/>예외 복구<br/>Fallback 로직 실행"]
        end

        subgraph P5["패턴 5: executeWithFinally"]
            P5Code["executeWithFinally(task, finalizer, context)<br/>자원 해제<br/>finally 블록 필요 시"]
        end

        subgraph P6["패턴 6: executeWithTranslation"]
            P6Code["executeWithTranslation(task, translator, context)<br/>예외 변환<br/>Checked → RuntimeException"]
        end
    end

    subgraph Context["TaskContext 구조"]
        Format["component:operation:dynamicValue<br/>예: Cache:Get:user123"]
        Purpose["메트릭 카디널리티 통제<br/>로그 추적"]
    end

    subgraph Example["실제 사용 예시"]
        CacheGet["TieredCache.get()<br/>→ execute()"]
        CachePut["TieredCache.put()<br/>→ executeOrDefault()"]
        CharFind["캐릭터 조회<br/>→ executeOrDefault()"]
        ApiCall["Nexon API 호출<br/>→ executeWithTranslation()"]
    end

    P1 --> CacheGet
    P3 --> CachePut
    P3 --> CharFind
    P6 --> ApiCall

    style P1 fill:#e3f2fd
    style P3 fill:#e8f5e9
    style P6 fill:#fff3e0
```

---

## 8. Decorator Chain (V4 계산기)

```mermaid
flowchart LR
    subgraph Factory["EquipmentExpectationCalculatorFactory"]
        Create["create(inputs)"]
    end

    subgraph Chain["Decorator Chain"]
        Base["BaseEquipmentItem<br/>cost = ZERO"]

        subgraph BlackCube["BlackCubeDecoratorV4"]
            BlackLogic["윗잠 블랙큐브<br/>기하분포 기대 시행<br/>Infinity → ZERO"]
        end

        subgraph AddCube["AdditionalCubeDecoratorV4"]
            AddLogic["아랫잠 에디셔널<br/>추가 잠재 옵션"]
        end

        subgraph Starforce["StarforceDecoratorV4"]
            StarLogic["스타포스 0~25성<br/>Lookup Table O(1)<br/>파괴 위험 포함"]
        end
    end

    subgraph Result["결과"]
        CostBreakdown["CostBreakdown<br/>- blackCubeCost<br/>- redCubeCost<br/>- additionalCubeCost<br/>- starforceCost"]
        TotalCost["totalExpectedCost<br/>(BigDecimal)"]
    end

    Create --> Base
    Base -->|"잠재 보유 시"| BlackCube
    BlackCube -->|"추가잠재 보유 시"| AddCube
    AddCube -->|"스타포스 보유 시"| Starforce
    Starforce --> CostBreakdown
    CostBreakdown --> TotalCost

    style Base fill:#e3f2fd
    style BlackCube fill:#1a1a1a,color:#fff
    style AddCube fill:#4caf50,color:#fff
    style Starforce fill:#ff9800,color:#fff
```

---

## 9. PostgreSQL 상태 머신 (Dynamic TTL)

```mermaid
stateDiagram-v2
    [*] --> HEALTHY: 애플리케이션 시작

    HEALTHY --> DEGRADED: PostgreSQL DOWN 감지<br/>(Circuit Breaker OPEN)
    DEGRADED --> RECOVERING: PostgreSQL UP 감지<br/>(Circuit Breaker CLOSED)
    RECOVERING --> HEALTHY: 5초 경과<br/>(Debounce)

    state HEALTHY {
        [*] --> NormalCache
        NormalCache: TTL 정상 (5~15분)
        NormalCache: PostgreSQL 캐시 사용
    }

    state DEGRADED {
        [*] --> FallbackMode
        FallbackMode: TTL 제거 (PERSIST)
        FallbackMode: Nexon API 직접 호출
        FallbackMode: PostgreSQL 캐시 사용
    }

    state RECOVERING {
        [*] --> CompensationSync
        CompensationSync: 기존 캐시 TTL 복원
        CompensationSync: 백로그 처리
    }
```

---

## 10. 모듈 의존성 그래프 (DIP 준수)

```mermaid
graph TB
    subgraph App["module-app (Application)"]
        Services["Application Services"]
        Workers["Workers"]
        Adapters["Port Adapters"]
    end

    subgraph Web["module-web (Presentation)"]
        Controllers["Controllers"]
        Filters["Filters"]
        DTOs["DTOs"]
    end

    subgraph Core["module-core (Domain)"]
        Domain["Domain Models"]
        Ports["Port Interfaces"]
        Calculator["Calculator Interfaces"]
    end

    subgraph Infra["module-infra (Infrastructure)"]
        CacheImpl["Cache Implementations"]
        LockImpl["Lock Implementations"]
        RepoImpl["Repository Implementations"]
        External["External API Clients"]
    end

    subgraph Common["module-common (Shared)"]
        Exceptions["Exceptions"]
        Utils["Utilities"]
        Executor["LogicExecutor"]
    end

    %% 의존성 (DIP: 인터페이스 의존)
    Web --> App
    App --> Core
    App --> Common
    Infra --> Core
    Infra --> Common
    Core --> Common

    %% Port-Adapter 패턴
    Services -.->|"의존"| Ports
    Adapters -.->|"구현"| Ports
    Infra -.->|"구현"| Ports

    classDef app fill:#e8f5e9
    classDef web fill:#e3f2fd
    classDef core fill:#fff3e0
    classDef infra fill:#fce4ec
    classDef common fill:#f3e5f5

    class Services,Workers,Adapters app
    class Controllers,Filters,DTOs web
    class Domain,Ports,Calculator core
    class CacheImpl,LockImpl,RepoImpl,External infra
    class Exceptions,Utils,Executor common
```

---

## 11. 예외 처리 계층 구조 (실제 구현 기반)

```mermaid
classDiagram
    class BaseException {
        <<abstract>>
        +ErrorCode errorCode
        +String dynamicMessage
    }

    class ClientBaseException {
        <<4xx>>
        +CircuitBreakerIgnoreMarker
    }

    class ServerBaseException {
        <<5xx>>
        +CircuitBreakerRecordMarker
    }

    %% Client Exceptions (4xx)
    class CharacterNotFoundException {
        +ErrorCode.CHARACTER_NOT_FOUND
    }
    class SelfLikeNotAllowedException {
        +ErrorCode.SELF_LIKE_NOT_ALLOWED
    }
    class DuplicateLikeException {
        +ErrorCode.DUPLICATE_LIKE
    }
    class InvalidApiKeyException {
        +ErrorCode.INVALID_API_KEY
    }

    %% Server Exceptions (5xx)
    class ExternalApiException {
        +ErrorCode.EXTERNAL_API_ERROR
    }
    class ApiTimeoutException {
        +ErrorCode.API_TIMEOUT
    }
    class CompressionException {
        +ErrorCode.COMPRESSION_ERROR
    }
    class DistributedLockException {
        +ErrorCode.DISTRIBUTED_LOCK_ERROR
    }

    BaseException <|-- ClientBaseException
    BaseException <|-- ServerBaseException

    ClientBaseException <|-- CharacterNotFoundException
    ClientBaseException <|-- SelfLikeNotAllowedException
    ClientBaseException <|-- DuplicateLikeException
    ClientBaseException <|-- InvalidApiKeyException

    ServerBaseException <|-- ExternalApiException
    ServerBaseException <|-- ApiTimeoutException
    ServerBaseException <|-- CompressionException
    ServerBaseException <|-- DistributedLockException

    note for ClientBaseException "Circuit Breaker가 무시\n(비즈니스 예외)"
    note for ServerBaseException "Circuit Breaker가 기록\n(시스템 장애)"
```

---

## 12. 캐시 무효화 (Pub/Sub)

```mermaid
sequenceDiagram
    participant App1 as App Instance 1
    participant Cache1 as L1 Cache (App1)
    participant Redis as Redis Pub/Sub
    participant Cache2 as L1 Cache (App2)
    participant App2 as App Instance 2

    %% 캐시 갱신
    App1->>Cache1: put(key, value)
    Cache1->>Redis: PUBLISH cache:evict {name, key}

    %% 무효화 전파
    Redis-->>Cache2: 메시지 수신
    Cache2->>Cache2: l1.evict(key)

    Note over App1,App2: Scale-out 환경에서 L1 일관성 유지

    %% 전체 무효화
    App1->>Redis: PUBLISH cache:clearAll {name}
    Redis-->>Cache2: 메시지 수신
    Cache2->>Cache2: l1.clear()
```

---

## 13. 핵심 설계 패턴 요약

| 패턴 | 적용 위치 | 실제 구현체 |
|------|-----------|-------------|
| **Facade** | V4 서비스 | `EquipmentExpectationServiceV4` |
| **Decorator** | 계산기 | `BaseEquipmentItem` → `BlackCube` → `Additional` → `Starforce` |
| **Strategy** | 락, 결제 | `LockStrategy`, `PaymentStrategy` |
| **Factory** | 계산기 생성 | `EquipmentExpectationCalculatorFactory` |
| **Template Method** | 실행 | `LogicExecutor` (6가지 패턴) |
| **SingleFlight** | 캐시 | `TieredCache` + Redis Lock |
| **Circuit Breaker** | 외부 API | `Resilience4j` + PostgreSQL 상태 머신 |
| **State Machine** | PostgreSQL 상태 | `HEALTHY` → `DEGRADED` → `RECOVERING` |
| **Port-Adapter** | 의존성 역전 | `Core Port` ↔ `Infra Adapter` |
| **Write-Behind** | 영속화 | `ExpectationPersistenceService` |
| **Pub/Sub** | 캐시 무효화 | `RedisCacheInvalidationPublisher/Subscriber` |

---

## 14. 검증 명령어

```bash
# 활성화된 Lock Strategy 확인
curl -s http://localhost:8080/actuator/loggers | jq '.loggers | keys | .[] | select(contains("Lock"))'

# TieredCache 메트릭 확인
curl -s http://localhost:8080/actuator/metrics/cache.hit | jq
curl -s http://localhost:8080/actuator/metrics/cache.miss | jq

# Circuit Breaker 상태 확인
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'

# V4 서비스 타임아웃 테스트
wrk -t4 -c100 -d30s --latency http://localhost:8080/api/v4/character/test/expectation

# PostgreSQL 상태 확인 (DEGRADED 감지)
curl -s http://localhost:8080/actuator/health | jq '.components.postgresql'
```

---

## 15. 분석 참여 에이전트

| 에이전트 | 분석 영역 | 주요 발견 |
|----------|-----------|-----------|
| **structure-analyzer** | 모듈 구조 | 6개 모듈, DIP 준수 |
| **infra-analyzer** | 인프라 계층 | TieredCache, 4가지 Lock Strategy, PostgreSQL 상태 머신 |
| **business-analyzer** | 비즈니스 서비스 | V4 Facade 분해, LogicExecutor 6패턴, Decorator Chain |
| **api-analyzer** | 외부 API 연동 | Nexon API 4개 엔드포인트, Resilience4j 설정, Fallback |

---

*작성일: 2026-03-10*
*분석 방식: 4개 에이전트 병렬 분석 + 실제 코드 검증*
*다음 검토일: 2026-04-10*
