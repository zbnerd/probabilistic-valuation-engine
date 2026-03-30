# ADR-332: Like 패키지 Core/Infra 분리

## 상태
Proposed (2026-03-02)

## 컨텍스트
현재 `module-app/service/v2/like/`에 17개의 클래스가 도메인과 인프라가 혼재된 상태로 존재한다.

### 현재 구조
```
module-app/service/v2/like/
├── compensation/
│   ├── CompensationCommand.java        # 도메인 인터페이스 (Port)
│   └── RedisCompensationCommand.java   # Redis 구현체
├── dto/
│   └── FetchResult.java                # 도메인 DTO
├── event/
│   └── LikeSyncFailedEvent.java        # Spring ApplicationEvent
├── listener/
│   └── LikeSyncEventListener.java      # Spring EventListener
├── metrics/
│   └── LikeSyncMetricsRecorder.java    # Micrometer 메트릭
├── realtime/
│   ├── LikeEventPublisher.java         # 인터페이스
│   ├── LikeEventSubscriber.java        # 인터페이스
│   ├── dto/LikeEvent.java              # Redis 이벤트 DTO
│   └── impl/
│       ├── RedisLikeEventPublisher.java
│       ├── RedisLikeEventSubscriber.java
│       ├── ReliableRedisLikeEventPublisher.java
│       └── ReliableRedisLikeEventSubscriber.java
├── recovery/
│   └── OrphanKeyRecoveryService.java   # 운영 복구 서비스
└── strategy/
    ├── AtomicFetchStrategy.java        # 인터페이스
    ├── LuaScriptAtomicFetchStrategy.java  # Redis Lua 전략
    └── RenameAtomicFetchStrategy.java   # Redis RENAME 전략
```

### 문제점
1. **관심사 혼재**: 도메인 로직과 Redis 구현이 섞여 있음
2. **의존성 방향**: app → infra 의존이 아닌 infra → core 의존이어야 함 (DIP 위반)
3. **테스트 용이성**: Redis 없이 도메인 로직 테스트 불가

## 결정 사항

### Core로 이관 (도메인)
| 파일 | 대상 위치 | 이유 |
|------|-----------|------|
| `FetchResult.java` | `module-core/dto/like/` | 순수 데이터 홀더, Redis 무관 |
| `CompensationCommand.java` | `module-core/port/out/like/` | 도메인 포트 인터페이스 |

### Infra로 이관 (인프라)
| 파일 | 대상 위치 | 이유 |
|------|-----------|------|
| `RedisCompensationCommand.java` | `module-infra/queue/like/compensation/` | Redis 구현체 |
| `LikeSyncFailedEvent.java` | `module-infra/queue/like/event/` | Spring ApplicationEvent |
| `LikeSyncEventListener.java` | `module-infra/queue/like/listener/` | Spring EventListener |
| `LikeSyncMetricsRecorder.java` | `module-infra/queue/like/metrics/` | Micrometer 메트릭 |
| `OrphanKeyRecoveryService.java` | `module-infra/queue/like/recovery/` | 운영 복구 서비스 |
| `realtime/*` | `module-infra/queue/like/realtime/` | Redis Pub/Sub |
| `strategy/*` | `module-infra/queue/like/strategy/` | Redis 최적화 전략 |

### 최종 구조
```
module-core/
└── maple/expectation/core/
    ├── dto/like/FetchResult.java
    └── port/out/like/
        └── CompensationCommand.java

module-infra/
└── maple/expectation/infrastructure/queue/like/
    ├── compensation/RedisCompensationCommand.java
    ├── event/LikeSyncFailedEvent.java
    ├── listener/LikeSyncEventListener.java
    ├── metrics/LikeSyncMetricsRecorder.java
    ├── recovery/OrphanKeyRecoveryService.java
    ├── realtime/
    │   ├── LikeEvent.java
    │   ├── LikeEventPublisher.java
    │   ├── LikeEventSubscriber.java
    │   └── impl/
    │       ├── RedisLikeEventPublisher.java
    │       ├── RedisLikeEventSubscriber.java
    │       ├── ReliableRedisLikeEventPublisher.java
    │       └── ReliableRedisLikeEventSubscriber.java
    └── strategy/
        ├── AtomicFetchStrategy.java
        ├── LuaScriptAtomicFetchStrategy.java
        └── RenameAtomicFetchStrategy.java
```

## 이유

### 1. DIP 준수
- **Before**: app → Redis 직접 의존
- **After**: app → core Port → infra Adapter

### 2. 테스트 용이성
- 도메인 로직을 Mock 없이 단위 테스트 가능
- Redis 의존성 격리

### 3. 관심사 분리
- **core**: 순수 도메인 DTO, Port 인터페이스
- **infra**: Redis, Spring Event, Metrics

## 위험 요소
1. **import 경로 변경**: 17개 파일 참조 수정 필요
2. **테스트 수정**: 테스트 import 경로 업데이트
3. **순환 의존성**: core-app-infra 간 의존성 재확인 필요

## 이행 계획
1. [x] ADR 작성
2. [ ] core 포트/DTO 이관 (FetchResult, CompensationCommand)
3. [ ] infra 클래스 이관 (나머지 15개)
4. [ ] import 경로 수정
5. [ ] 테스트 검증

## 관련 문서
- ADR-009: Cache Service를 module-infra로 이관
- ADR-005: module-dependency-strategy
- CLAUDE.md Section 4: SOLID 원칙 (DIP)
