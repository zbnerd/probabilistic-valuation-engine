# ADR-350: Cube 도메인 이관 분석 보고서

## 상태
Analysis Complete - Migration Deferred (2026-02-28)

## 분석 결과

### 현재 cube 패키지 구조
```
module-app/src/main/java/maple/expectation/service/v2/cube/
├── AbstractCubeDecorator.java           # Generic template (V2/V4 공통)
├── AbstractCubeDecoratorV2.java         # V2 Long 타입 데코레이터
├── AbstractCubeDecoratorV4.java         # V4 BigDecimal 타입 데코레이터
├── component/
│   ├── CubeDpCalculator.java            # DP 기반 계산 (@Cacheable)
│   ├── CubeSlotCountResolver.java       # 슬롯 수 결정
│   ├── StatValueExtractor.java          # 스탯 기여도 추출
│   ├── SlotDistributionBuilder.java     # 슬롯별 분포 생성
│   └── DpModeInferrer.java              # DP 모드 추론
├── dto/
│   ├── SparsePmf.java                   # 희소 확률질량함수
│   └── DensePmf.java                    # 밀집 확률질량함수
└── config/
    ├── TableMassConfig.java             # 테이블 질량 검증 설정
    └── CubeEngineFeatureFlag.java       # 엔진 Feature Flag
```

### 의존성 분석

#### Core Dependencies (module-core에서 사용 가능)
- `maple.expectation.core.domain.stat.StatType` ✅
- `maple.expectation.core.domain.stat.StatParser` ✅
- `maple.expectation.core.probability.ProbabilityConvolver` ✅
- `maple.expectation.core.probability.TailProbabilityCalculator` ✅

#### App-Specific Dependencies (module-app 종속)
- `maple.expectation.service.v2.CubeTrialsProvider` ❌
- `maple.expectation.service.v2.policy.CubeCostPolicy` ❌
- `maple.expectation.service.v2.calculator.EnhanceDecorator` ❌
- `maple.expectation.service.v2.calculator.ExpectationCalculator` ❌
- `maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator` ❌
- `maple.expectation.domain.v2.CubeType` ❌
- `maple.expectation.domain.repository.CubeProbabilityRepository` ❌
- `maple.expectation.dto.CubeCalculationInput` ❌
- `maple.expectation.infrastructure.executor.LogicExecutor` ❌
- Spring Framework (`@Component`, `@Cacheable`) ❌

## 이관 제약 사항

### 1. 순환 의존성 문제
```
module-core → module-app (CubeTrialsProvider, CubeCostPolicy)
     ↓
module-app → module-core (StatType, StatParser)
```
**문제:** Core가 App에 의존하는 순환 의존성 발생

### 2. Infrastructure 의존성
Cube 컴포넌트들은 Spring Framework 인프라에 의존:
- `@Component`: Bean 등록
- `@Cacheable`: 캐시 추상화
- `LogicExecutor`: 예외 처리

### 3. Repository 의존성
`SlotDistributionBuilder`는 JPA Repository에 직접 의존:
```java
private final CubeProbabilityRepository repository;
```

## 권장 사항

### Option A: 현재 상태 유지 (권장)
**이유:**
1. Cube 도메인은 이미 `service/v2/cube/` 패키지로 잘 정리됨
2. 핵심 로직(SparsePmf, DensePmf)은 이미 독립적
3. Decorator 패턴으로 V2/V4가 분리됨
4. DP 컴포넌트들은 @Cacheable로 인해 Spring 컨텍스트 필수

**장점:**
- 리스크 없이 안정적
- 기존 테스트 커버리지 유지
- 캐시 동작 보장

### Option B: module-infra로 이관 (대안 1)
**구조:**
```
module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/cube/
├── component/           # DP 계산 컴포넌트
├── config/              # 설정
└── repository/          # Repository Adapter
```

**이유:**
- Infra 계층에 적합 (캐시, DB 의존)
- Core Port 인터페이스만 module-core에 남김

**단점:**
- Port 인터페이스 분리 작업 필요
- 리팩토링 범위 큼

### Option C: module-app 내에서 정리 (대안 2)
**구조:**
```
module-app/src/main/java/maple/expectation/service/v2/cube/
├── domain/              # 도메인 모델 (SparsePmf, DensePmf)
├── decorator/           # Decorator 계층
├── calculator/          # DP 계산기
└── config/              # 설정
```

**이유:**
- 단일 모듈 내에서 책임 분리
- 이관 리스크 최소화

## 향후 로드맵

### Phase 1: 정리만 수행 (즉시 실행 가능)
1. 패키지 구조 재조직
2. Kotlin으로 변환 (Java → Kotlin)
3. 문서화 개선

### Phase 2: Port 정의 (ADR-004 참조)
1. CubeRatePort, CubeCostPort 이미 정의됨 ✅
2. 추가 Port 인터페이스 필요 시 정의
3. module-infra에 Adapter 구현

### Phase 3: 점진적 이관
1. 순수 비즈니스 로직만 module-core로 이관
2. Infra 의존성은 module-infra로 분리
3. App 계층은 Core Port만 의존

## 결론

**Cube 도메인의 module-core 직접 이관은 권장하지 않음.**

### 근거
1. **높은 Infra 의존성:** Spring Framework, JPA Repository에 의존
2. **순환 의존성 위험:** Core → App → Core 순환
3. **캐시 의존성:** @Cacheable은 Spring 컨텍스트 필수
4. **안정성:** 현재 구조가 이미 잘 작동 중

### 대안
- **단기:** module-app 내에서 패키지 정리
- **중기:** 필요 Port만 module-core에 정의
- **장기:** ADR-004 Phase 4에서 전체 재검토

## 관련 문서
- ADR-004: Module-Core 도메인 이관
- CLAUDE.md: Section 4 (Implementation Logic & SOLID)
- docs/09_Plans/2026-02-27-module-separation-design.md

---
**문서 버전**: 1.0.0
**최종 업데이트**: 2026-02-28
**작성자**: Claude (Executor)
**검증자**: Team
