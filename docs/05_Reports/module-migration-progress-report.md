# 모듈 이관 진행 리포트

## 작성일
2026-02-28

## 개요
ADR-003 Hexagonal Architecture 및 ADR-004 Module-Core Migration에 따른 모듈 이관 작업 진행 상황

## 완료된 작업

### PR 머지 현황
| PR | 내용 | 이슈 | 상태 |
|----|------|------|------|
| #457 | Scheduler Port 기반 테스트 | - | ✅ 머지 |
| #458 | module-core 도메인 이관 Phase 1 | #415, #417, #418 | ✅ 머지 |
| #459 | Policy 도메인 이관 | #419 | ✅ 머지 |

### 이관된 module-core 구조
```
module-core/src/main/kotlin/maple/expectation/core/
├── calculator/
│   ├── domain/     # V2/V4 계산기 Port 및 Decorator
│   └── port/       # CubeRatePort, CubeCostPort, StarforceLookupPort, StatParserPort
├── starforce/
│   └── domain/     # StarforceConstants, StarforceCalculationEngine, NoljangProbabilityCalculator
├── flame/
│   ├── config/     # BossEquipmentRegistry, JobStatMapping
│   ├── component/  # FlameScoreResolver
│   ├── port/       # FlameTrialsPort
│   └── service/    # FlameTrialsService
├── policy/
│   ├── CostCalculationStrategy.kt
│   └── TableBasedCostStrategy.kt
├── domain/
│   ├── ExecutionContext.kt
│   └── model/
│       ├── CalculationResult.kt
│       └── PotentialGrade.kt
└── port/out/
    ├── PolicyPort.kt
    └── ... (기존 17개 Port)
```

### 닫힌 이슈
- ✅ #409: ADR 작성 - 4모듈 구조 정의
- ✅ #415: Calculator 도메인 이관
- ✅ #417: Flame 도메인 이관
- ✅ #418: Starforce 도메인 이관
- ✅ #419: Policy 도츠 이관

### 유예된 이슈 (높은 인프라 의존성)
- ⏸️ #416: Cube 도메인 이관
- ⏸️ #420: Facade 서비스 이관
- ⏸️ #421: V4 서비스 이관
- ⏸️ #422: V5 서비스 이관 (부분 이관 가능)
- ⏸️ #423: Monitoring 순수 로직 이관 (이미 Port 기반 적용됨)

## 분석 완료 사항

### Monitoring (#423)
- **결론**: 이관 불필요
- **사유**: 모든 스케줄러가 이미 Port 기반 아키텍처 적용됨
- 스케줄러는 Orchestration Layer, 실제 로직은 Port에 있음

### Web Controller (#411)
- **결론**: 단계적 이관 필요
- Phase 1: 순수 Request DTO 이관 (진행 중)
- Phase 2: Response DTO + Port 리팩토링
- Phase 3: 전체 Controller 이관

## 다음 단계 (ADR-005 전략 반영)

### Phase 1: 기반 정립 (P0)
1. **#410 Gradle 의존성 규칙 고정**
   - 의존성 방향 검증 (web → app → core ← infra)
   - ArchUnit 테스트 추가

2. **#435 Common 모듈 정리**
   - 공통 DTO, 에러 모델 분리
   - Spring-web 의존 제거

### Phase 2: 외부 계층 이관 (P1)
3. **#411-413 Web 이관**
4. **#414 Application 계층 정리**

### Phase 3: 인프라 이관 (P2)
5. **#424-434 Infra 이관**

### Phase 4: 검증 (P3)
6. **#439-443 통합 검증/문서화**

## 의존성 그래프 (목표)

```
module-web  ──────>  module-app  ──────>  module-core
                           ^                    ^
                           |                    |
                    module-infra ───────────────┘

module-common  ───>  (모든 모듈이 사용 가능)
```

## 검증 결과

| 항목 | 상태 |
|------|------|
| 빌드 | ✅ 성공 (58 tasks) |
| 테스트 | ✅ 통과 (745 tests) |
| ArchUnit | ✅ core_should_not_depend_on_web_or_infra |
| Spotless | ✅ 포맷팅 통과 |

## 통계

| 항목 | 수치 |
|------|------|
| 이관된 파일 | 30+ |
| 새로 생성된 Port | 8 |
| 닫힌 이슈 | 5 |
| 머지된 PR | 3 |
| Kotlin 변환 라인 | ~1,500 |

## 결론

ADR-003/004에 따른 1단계 이관 작업이 성공적으로 완료됨. 다음 단계는 ADR-005 전략에 따라 의존성 그래프를 안정화하고 Common 모듈을 정리하는 것.

---

**작성자**: Claude
**검토자**: Team
**버전**: 1.0.0
