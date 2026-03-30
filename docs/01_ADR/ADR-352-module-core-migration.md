# ADR-352: Module-Core 도메인 이관

## 상태
Partially Implemented (2026-02-28)

## 컨텍스트

**현재 구조 문제:**
1. **모든 코드가 module-app에 집중**: 347개 파일 중 45-55%가 module-core로 이관해야 할 도메인 로직
2. **의존성 혼란**: 계산 로직과 인프라 구현이 같은 모듈에 혼재
3. **테스트 어려움**: 순수 비즈니스 로직에 인프라 의존성 강제
4. **확장성 제한**: 신규 기능 추가 시 기존 모듈 전체 영향

**관련 이슈:**
- #415: Calculator Domain 분리 (순수 계산 로직)
- #416: Cube Domain 분리 (크래프트 계산)
- #417: Flame Domain 분리 (플레임 계산)
- #418: Starforce Domain 분리 (스타포스 계산)

**기존 구조:**
```
module-app (347개 파일)
├── service/v2/calculator/    # 기대값 계산 (V2: Long)
├── service/v2/cube/          # 크래프트 계산
├── service/v2/flame/         # 플레임 계산
├── service/v2/starforce/     # 스타포스 계산
├── service/v4/               # V4: BigDecimal (추가 계산)
├── service/v5/               # V5: CQRS 분리
└── application/              # 애플리케이션 서비스
```

## 결정

**module-core로 도메인 로직 이관**을 Port-Based Architecture로 진행한다.

### 핵심 원칙

```
web → core ← infra ✅ (올바른 의존성 방향)
core → web/infra ❌ (금지)
```

1. **순수 비즈니스 로직**: module-core로 이관
2. **Port/Adapter 패턴**: Core가 Port만 알고 Infra가 구현
3. **인프라 구현**: Cache, DB, 외부 API는 module-infra로 분리
4. **의존성 역전**: Application Layer는 Core Port만 의존

### 이관 대상

| 도메인 | 현재 위치 | 이관 위치 | 주요 파일 수 |
|--------|----------|----------|--------------|
| Calculator | `service/v2/calculator/` | `core/calculator/` | ~25 |
| Cube | `service/v2/cube/` | `core/cube/` | ~15 |
| Flame | `service/v2/flame/` | `core/flame/` | ~10 |
| Starforce | `service/v2/starforce/` | `core/starforce/` | ~10 |
| Policy | `service/v2/policy/` | `core/policy/` | ~5 |
| V4/V5 Logic | `service/v4/`, `service/v5/` | `core/v4/`, `core/v5/` | ~30 |
| Monitoring | `scheduler/`, `batch/` | `core/monitoring/` | ~20 |

### 목표 구조

```
module-core/src/main/kotlin/maple/expectation/core/
├── calculator/              # 순수 계산 엔진
│   ├── domain/            # 계산 모델
│   ├── application/       # Use Case
│   ├── port/              # Port 인터페이스
│   └── strategy/          # 계산 전략
├── cube/                  # 크래프트 도메인
│   ├── domain/           # 크래프트 모델
│   ├── application/     # 크래프트 Use Case
│   └── port/            # 크래프트 Port
├── flame/                # 플레임 도메인
│   ├── domain/          # 플레임 모델
│   ├── application/     # 플레임 Use Case
│   └── port/            # 플레임 Port
├── starforce/            # 스타포스 도메인
│   ├── domain/          # 스타포스 모델
│   ├── application/     # 스타포스 Use Case
│   └── port/            # 스타포스 Port
├── policy/              # 비용 정책
│   ├── domain/          # 정책 모델
│   └── application/     # 정책 Apply Use Case
├── v4/                  # V4 계산 로직
│   ├── calculator/      # V4 계산기
│   ├── application/     # V4 Use Case
│   └── port/           # V4 Port
├── v5/                  # V5 계산 로직
│   ├── calculator/      # V5 계산기
│   ├── application/     # V5 Use Case
│   └── port/           # V5 Port
├── monitoring/          # 모니터링 로직
│   ├── application/     # 모니터링 Use Case
│   └── port/           # 모니터링 Port
└── port/                # 공통 Port
    ├── in/              # Inbound Port
    └── out/             # Outbound Port
```

## 근거

### 1. SOLID 원칙 준수
- **SRP**: 각 도메인이 단일 책임을 가짐
- **OCP**: Port/Adapter로 확장 가능성 보장
- **DIP**: Core가 Implementation에 의존하지 않음

### 2. 테스트 용이성 향상
- 순수 비즈니스 로직만으로 단위 테스트 가능
- Infra 구현은 독립적으로 테스트 가능
- Mock을 사용한 빠른 테스트 가능

### 3. 확장성 증대
- 신규 도메인 추가 시 Core만 확장
- 인프라 변경이 Core에 영향 없음
- Kotlin 마이그레이션 용이

### 4. 유지보수성 개선
- 관심사 분리로 변경 파급 범위 최소화
- 각 도메인이 독립적으로 개발/배포 가능
- 코드 재사용성 향상

## 적용 범위

### 우선순위 높음 (Phase 2: #415-418)

| 도메인 | 이관 파일 | 의존성 Port |
|--------|-----------|-------------|
| Calculator | ExpectationCalculator, EnhanceDecorator | CalcPort, RatePort |
| Cube | CubeDecorator, CubeDpCalculator | CubePort, RatePort |
| Flame | FlameTrialsService, FlameScoreResolver | FlamePort, StatPort |
| Starforce | StarforceLookupTable, StarforceCalculator | StarforcePort, DataPort |

### 우선순위 중간 (Phase 4: #419-423)

| 도메인 | 이관 파일 | 주요 변경 |
|--------|-----------|-----------|
| Policy | CostCalculationStrategy, CubeCostPolicy | PolicyPort |
| V4 Logic | EquipmentExpectationCalculatorV4 | CalculatorPort |
| V5 Logic | EquipmentExpectationCalculatorV5 | CalculatorPort |
| Monitoring | MonitoringReportJob | MonitorPort |

## 이관 절차

### Phase 2: Core 도메인 이관 (#415-418)

1. **Port 인터페이스 정의**
   ```kotlin
   // module-core/src/main/kotlin/maple/expectation/core/port/out/
   interface CalcPort {
       fun calculateEnhanceCost(enhanceLevel: Int): BigDecimal
   }

   interface RatePort {
       fun getSuccessRate(enhanceLevel: Int): BigDecimal
   }
   ```

2. **Core 도메인 이관**
   ```kotlin
   // module-core/src/main/kotlin/maple/expectation/core/calculator/
   class ExpectationCalculator(
       private val calcPort: CalcPort,
       private val ratePort: RatePort
   ) : CalculatorPort {
       fun calculate(): BigDecimal = calcPort.calculateEnhanceCost(10)
   }
   ```

3. **Infra Adapter 구현**
   ```kotlin
   // module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/
   class EnhanceCostCalculatorAdapter : CalcPort {
       fun calculateEnhanceCost(enhanceLevel: Int): BigDecimal =
           // DB 조회 계산 로직
   }
   ```

4. **Application Layer 수정**
   ```java
   // module-app/src/main/java/maple/expectation/application/
   @Service
   public class CubeApplicationService {
       private final CalculatorPort calculatorPort;

       public CubeApplicationService(CalculatorPort calculatorPort) {
           this.calculatorPort = calculatorPort; // Port만 의존
       }
   }
   ```

### 단계별 검증

1. **이관 단계별로 테스트**: 각 단계 완료 후 전체 테스트
2. **의존성 검증**: ArchUnit으로 순환 의존 검사
3. **동작 검증**: 기능 동작 동일성 확인
4. **성능 검증**: 이관 전후 성능 차이 측정

## 검증 규칙

### ArchUnit 테스트

```kotlin
@AnalyzeClasses(packages = "maple.expectation")
class ModuleDependencyTest {

    @ArchTest
    val coreShouldNotDependOnInfraOrWeb = noClasses()
        .that().resideInAPackage("..core..")
        .should().not().dependOnClassesThat()
        .resideInAnyPackage("..infra..", "..web..")

    @ArchTest
    val appShouldOnlyDependOnCorePorts = noClasses()
        .that().resideInAPackage("..application..")
        .should().onlyDependOnClassesThat()
        .resideInAnyPackage("..core..", "..common..")
}
```

### 동작 검증 테스트

```kotlin
class CoreMigrationVerificationTest {

    @Test
    fun `Calculator domain 이관 후 동일성 검증`() {
        val originalResult = originalCalculator.calculate()
        val newResult = newCalculator.calculate()
        assertEquals(originalResult, newResult)
    }
}
```

### 성능 검증

```bash
# 이관 전후 성능 비교
./gradlew benchmark:run --args="before-migration"
./gradlew benchmark:run --args="after-migration"
```

## 위험 요소 및 대응책

### 위험 요소

| 위험 | 확률 | 영향도 | 대응 방안 |
|------|------|--------|-----------|
| 순환 의존 | Medium | High | Port First 설계, ArchUnit 자동 검증 |
| 성능 저하 | Low | High | 프로파일링, 캐시 전략 |
| 기능 변형 | Low | High | Golden Master 테스트, Characterization 테스트 |
| 이관 오류 | Medium | Medium | 단계별 이관, Rollback 계획 수립 |

### 대응 전략

1. **점진적 이관**: 각 도메인별로 이관 후 검증
2. **Rollback 메커니즘**: 이관 실패 시 빠른 복귀
3. **테스트 강화**: 이관 단계별로 테스트 범위 확장
4. **문서화**: 이관 과정 상세 기록

## 대안

### A1: 모든 코드 한번에 이관
- **장점**: 단기간 완료 가능
- **단점**: 위험도 높음, 디버깅 어려움
- **기각 사유**: 테스트 커버리지 부족 시 대응 불가능

### A2: Layer 별 이관
- **장점**: 명확한 경계
- **단점**: Port/Adapter 패턴 미준수
- **기각 사유**: 장기적인 확장성 저하

### A3: 도메인별 모듈 분리
- **장점**: 완전한 모듈화
- **단점**: 프로젝트 복잡성 증가
- **기각 사유**: 초기 투자 비용 과다

## 관련 문서

- ADR-003: Hexagonal Architecture (Ports & Adapters) 채택
- CLAUDE.md: Section 4 (Implementation Logic & SOLID), Section 16 (Proactive Refactoring)
- docs/09_Plans/2026-02-27-module-separation-design.md: 전체 모듈 분리 계획

## Phase 1: Facade 이관 분석 (2026-02-28)

### 결정: **DEFERRED** (유예)

**분석 결과**: `service/v2/facade/` 패키지 이관을 **유예**하고 ADR-003 Port 기반 리팩토링 선행 권장.

### 차단 요인 (Blockers)

| 차단 요인 | 심각도 | 해결 방안 |
|----------|--------|----------|
| GameCharacterService 의존 | P0 | ADR-003 리팩토링 후 CharacterPort 추출 |
| RedissonClient 직접 사용 | P0 | DistributedLockPort 정의 필요 |
| Infra 패키지 의존 (LogicExecutor 등) | P1 | Port 추출 또는 module-app 유지 |
| 순환 의존성 위험 | P0 | 서비스 계층 리팩토링 선행 |

### 권장사항

1. **Facade는 Application Layer 패턴**: `module-app`에 유지하는 것이 Clean Layered Architecture에 부합
2. **Port 기반 리팩토링 선행**: `GameCharacterService` → `CharacterPort` 변환 후 재검토
3. **동시성 제어 분리**: `DistributedLockPort` 정의하여 Core의 Infra 의존 제거

### 상세 분석 보고서

- 문서: `docs/adr/facade-migration-analysis.md`
- 분석일: 2026-02-28
- 결론: Phase 2 (GameCharacterService 리팩토링) 완료 후 재검토

## 이력

| 날짜 | 상태 | 변경 사항 |
|------|------|-----------|
| 2026-02-28 | Proposed | 초기 초안 작성, Port-Based Architecture 설계 |
| 2026-02-28 | Approved | 의존성 분리 전략 검증 |
| 2026-02-28 | Updated | Facade 이관 분석 완료, 유예 결정 (Phase 1) |

---

**문서 버전**: 1.0.0
**최종 업데이트**: 2026-02-28
**작성자**: Claude
**검증자**: Team