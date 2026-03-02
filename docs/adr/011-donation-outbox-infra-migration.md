# ADR-011: Donation Outbox를 module-infra로 이관

## 상태
Proposed (2026-03-02)

## 컨텍스트

### 현재 상황
`module-app/service/v2/donation/outbox/`에 5개의 아웃박스 관련 클래스가 위치해 있다. 이들은 Transactional Outbox 패턴의 구현체로, 메시지 발행, DLQ 처리, 메트릭 수집 등 **순수 인프라 관심사**를 담당한다.

### 현재 구조
```
module-app/src/main/java/maple/expectation/service/v2/donation/outbox/
├── DlqAdminService.java         # DLQ 관리 (운영용)
├── DlqHandler.java              # Dead Letter Queue 처리
├── OutboxFetchFacade.java       # Outbox 조회 전용
├── OutboxMetrics.java           # Prometheus 메트릭
└── OutboxProcessor.java         # Outbox 메인 처리 (폴링/발행)

module-app/src/test/java/maple/expectation/service/v2/donation/outbox/
├── DlqAdminServiceTest.java
├── DlqHandlerTest.java
└── OutboxProcessorTest.java
```

### 클래스별 의존성 분석

| 클래스 | Spring 의존 | DB 의존 | Redis 의존 | 메트릭 | 판단 |
|--------|------------|---------|-----------|--------|------|
| OutboxProcessor | @Service, @Transactional | JPA Repository | - | Counter | **Infra** |
| OutboxMetrics | @Component | JPA Repository | - | MeterRegistry | **Infra** |
| DlqHandler | @Service | DLQ Repository | - | Counter | **Infra** |
| DlqAdminService | @Service | JPA Repository | - | - | **Infra** |
| OutboxFetchFacade | @Service | JPA Repository | - | - | **Infra** |

### Core vs Infra 판단 체크리스트

**Infra 신호 (모두 해당):**
- [x] `@Service`, `@Component`, `@Transactional` 사용
- [x] JPA Repository 직접 주입
- [x] `MeterRegistry`, `Counter` (Prometheus) 사용
- [x] `LogicExecutor`, `TransactionTemplate` 사용
- [x] 스케줄링/폴링/메시지 발행 담당

**Core 신호 (해당 없음):**
- [ ] 순수 Java/Kotlin만 사용
- [ ] 도메인 규칙/계산 로직
- [ ] 인터페이스만 의존

### 문제점
1. **아키텍처 위반**: 인프라 구현체가 애플리케이션 계층에 위치
2. **SRP 위반**: OutboxProcessor가 폴링 + 발행 + 재시도 + 메트릭까지 담당
3. **테스트 복잡도**: module-app 테스트 시 인프라 의존성 모킹 필요
4. **의존성 방향**: service → infra 직접 참조 (ADR-005 위반)

## 결정

**donation/outbox 전체를 module-infra로 이관**한다.

### 이관 대상

| 파일 | 현재 위치 | 목표 위치 |
|------|----------|----------|
| OutboxProcessor.java | module-app/.../donation/outbox/ | module-infra/.../donation/outbox/ |
| OutboxMetrics.java | module-app/.../donation/outbox/ | module-infra/.../donation/outbox/ |
| DlqHandler.java | module-app/.../donation/outbox/ | module-infra/.../donation/dlq/ |
| DlqAdminService.java | module-app/.../donation/outbox/ | module-infra/.../donation/dlq/ |
| OutboxFetchFacade.java | module-app/.../donation/outbox/ | module-infra/.../donation/outbox/ |
| *Test.java (3개) | module-app/.../donation/outbox/ | module-infra/.../donation/outbox/ |

### 이관 후 구조
```
module-infra/src/main/kotlin/maple/expectation/infrastructure/donation/
├── outbox/
│   ├── OutboxProcessor.kt        # @Service, DB 폴링, 메시지 발행
│   ├── OutboxMetrics.kt          # @Component, Prometheus 메트릭
│   └── OutboxFetchFacade.kt      # @Service, 조회 전용
│
└── dlq/
    ├── DlqHandler.kt             # @Service, 장애 메시지 처리
    └── DlqAdminService.kt        # @Service, 운영용 DLQ 관리

module-infra/src/test/kotlin/maple/expectation/infrastructure/donation/
├── outbox/
│   └── OutboxProcessorTest.kt
└── dlq/
    ├── DlqHandlerTest.kt
    └── DlqAdminServiceTest.kt
```

### Java → Kotlin 변환
이관 시 Kotlin으로 변환하여 module-infra의 일관성 유지 (ADR-006 준수)

## 결과

### 긍정적 효과
1. **아키텍처 준수**: 인프라 구현체가 infra 계층에 위치
2. **의존성 방향 정상화**: app → infra 단방향
3. **테스트 격리**: infra 테스트가 module-infra에서 독립 실행
4. **MSA 대비**: donation 도메인 분리 시 outbox가 함께 이동 가능

### 부정적 효과 / 리스크

| 리스크 | 완화책 |
|--------|--------|
| Import 경로 변경 | IDE 리팩토링 기능 활용 |
| 테스트 경로 변경 | 테스트 코드 함께 이관 |
| Kotlin 변환 오류 | 컴파일 후 단위 테스트 검증 |

## 이행 계획

### Step 1: 파일 이관
- [ ] OutboxProcessor.java → module-infra/donation/outbox/OutboxProcessor.kt
- [ ] OutboxMetrics.java → module-infra/donation/outbox/OutboxMetrics.kt
- [ ] OutboxFetchFacade.java → module-infra/donation/outbox/OutboxFetchFacade.kt
- [ ] DlqHandler.java → module-infra/donation/dlq/DlqHandler.kt
- [ ] DlqAdminService.java → module-infra/donation/dlq/DlqAdminService.kt

### Step 2: 테스트 이관
- [ ] OutboxProcessorTest.java → module-infra/
- [ ] DlqHandlerTest.java → module-infra/
- [ ] DlqAdminServiceTest.java → module-infra/

### Step 3: 검증
- [ ] `./gradlew :module-infra:compileKotlin`
- [ ] `./gradlew :module-infra:test --tests "*Outbox*"`
- [ ] `./gradlew :module-infra:test --tests "*Dlq*"`
- [ ] `./gradlew build`

### Step 4: 문서 업데이트
- [ ] CLAUDE.md Service Modules 섹션 업데이트
- [ ] ADR-010 진행 상황 업데이트

## 검증 방법

### 컴파일 타임
```bash
# module-infra 컴파일
./gradlew :module-infra:compileKotlin

# 전체 빌드
./gradlew clean build -x test
```

### 테스트
```bash
# Outbox 관련 테스트
./gradlew :module-infra:test --tests "*Outbox*"
./gradlew :module-infra:test --tests "*Dlq*"

# 전체 테스트
./gradlew test
```

### 아키텍처 검증
```bash
# verify-module-structure 스킬 실행
/verify-module-structure
```

## 관련 문서
- ADR-003: Hexagonal Architecture 채택
- ADR-005: 모듈 의존성 그래프 및 이관 전략
- ADR-006: Java to Kotlin Migration Strategy
- ADR-010: Service Layer 모듈화 전략 (Phase 1 - outbox 이관)
- CLAUDE.md Section 12: Zero Try-Catch Policy

## 이력

| 날짜 | 상태 | 변경 사항 |
|------|------|-----------|
| 2026-03-02 | Proposed | 초기 초안 작성 |
