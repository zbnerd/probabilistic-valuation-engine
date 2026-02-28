# ADR-005: 모듈 의존성 그래프 및 이관 전략

## 상태
In Progress (2026-02-28)

## 컨텍스트

**현재 문제:**
1. infra → app 역참조 발생 (Strong Coupling)
2. 모듈 간 의존성 방향이 불명확
3. 이관 작업 시 결합도 증가 위험

**목표:**
- 비즈니스(Usecase/Domain)는 인프라를 모르게 하고, 인프라가 비즈니스를 구현한다

## 결정

### 모듈 역할 정의

| 모듈 | 역할 | 의존성 |
|------|------|--------|
| **module-core** | 순수 도메인, Port 인터페이스 | 없음 (Spring 의존 금지) |
| **module-app** | 유즈케이스, 트랜잭션 경계 | → module-core |
| **module-infra** | Port 구현체, 기술 의존 | → module-core |
| **module-web** | Controller, Filter, DTO | → module-app |
| **module-common** | 공통 DTO, 에러, 유틸 | 없음 (가볍게) |

### 의존성 그래프 (정답 형태)

```
module-web  ──────>  module-app  ──────>  module-core
                           ^                    ^
                           |                    |
                    module-infra ───────────────┘
                    (implements ports)

module-common  ───>  (모든 모듈이 사용 가능, 단 가볍게)
```

### 절대 금지 룰

| 금지 | 이유 |
|------|------|
| ❌ module-core → module-infra | 도메인이 Redis/JPA/OpenAI를 알면 끝남 |
| ❌ module-app → module-web | 유즈케이스가 HTTP 개념을 알면 레이어 깨짐 |
| ❌ module-common → spring-web | common이 web 의존하면 전파됨 |

### Port 위치 전략

**패턴 A (강추): Port를 module-core에 배치**

```
Port 인터페이스: module-core/port/out/
Usecase: module-app/service/
Adapter 구현: module-infra/adapter/
```

장점: infra가 app을 몰라도 됨 (결합도 최저)

## 이관 순서

### Phase 1: 기반 정립 (P0)
1. **#410 Gradle 의존성 규칙 고정**
   - build.gradle 의존성 방향 검증
   - ArchUnit 테스트 추가

2. **#435 Common 모듈 정리**
   - 공통 DTO, 에러 모델 분리
   - Spring-web 의존 제거

### Phase 2: 외부 계층 이관 (P1)
3. **#411-413 Web 이관**
   - Controller → module-web
   - Filter → module-web
   - WebConfig → module-web

4. **#414 Application 계층 정리**
   - 유즈케이스/서비스 정리
   - 트랜잭션 경계 명확화

### Phase 3: 인프라 이관 (P2)
5. **#424-434 Infra 이관**
   - Batch/Cache/Redis/Client 구현체
   - Adapter 패턴 적용

### Phase 4: 검증 (P3)
6. **#439-443 통합 검증/문서화**
   - CI 파이프라인 업데이트
   - ADR 상태 업데이트

## 패키지 구조

```
module-core/
├── domain/           # Entity, VO, Policy
├── port/
│   ├── in/          # Inbound Port (선택)
│   └── out/         # Outbound Port (Repository, Client)

module-app/
├── application/     # Usecase, Service
├── dto/            # 유즈케이스 내부 DTO

module-infra/
├── adapter/
│   ├── outgoing/   # Persistence, Client
│   └── incoming/   # Event Listener
├── config/         # Spring Config

module-web/
├── controller/
├── filter/
├── dto/            # Request/Response DTO
├── config/         # WebConfig

module-common/
├── error/          # 공통 에러 모델
├── dto/            # 공통 응답/페이지
├── util/           # 공통 유틸
```

## 10초 체크리스트

| 질문 | 위치 |
|------|------|
| 비즈니스 규칙인가? | domain/app |
| 기술 구현인가? | infra |
| HTTP/웹인가? | web |
| 여러 모듈이 공유하나? | common (가볍게!) |
| infra가 app 타입 참조? | Port를 domain으로 올려라 |

## 근거

1. **결합도 감소**: 의존성 방향 고정으로 변경 파급 최소화
2. **테스트 용이성**: Port mocking으로 단위 테스트 가능
3. **확장성**: 모듈 경계 명확으로 MSA 분리 가능
4. **Kotlin 마이그레이션**: Core부터 Kotlin 전환 용이

## 관련 문서

- ADR-003: Hexagonal Architecture 채택
- ADR-004: Module-Core 도메인 이관
- CLAUDE.md: Section 4 (SOLID), Section 16 (Proactive Refactoring)

## 이력

| 날짜 | 상태 | 변경 사항 |
|------|------|-----------|
| 2026-02-28 | Proposed | 의존성 그래프 및 이관 전략 수립 |
