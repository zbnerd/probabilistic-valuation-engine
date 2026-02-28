# PR 458-466 ADR 분석 보고서

**분석 일시**: 2026-02-28
**분석 대상**: PR #458 ~ #466 (총 9개 PR)
**관련 ADR**: ADR-004, ADR-005

---

## 1. 개요

PR 458-466은 ADR-004 (Module-Core 도메인 이관)와 ADR-005 (모듈 의존성 그래프 및 이관 전략)에 따른 아키텍처 리팩토링 작업입니다. 본 보고서는 각 PR의 아키텍처 변경 사항과 ADR 매핑을 분석합니다.

---

## 2. PR 요약 및 ADR 매핑

### 2.1 PR 목록

| PR | 제목 | 상태 | 관련 ADR | 아키텍처 변경 |
|----|------|------|----------|---------------|
| #458 | ADR-004 module-core 도메인 이관 (Phase 1) | MERGED | ADR-004 | 높음 |
| #459 | ADR-004 Policy 도메인 이관 | MERGED | ADR-004 | 높음 |
| #460 | ADR-005 모듈 의존성 그래프 및 이관 전략 수립 | MERGED | ADR-005 | 없음 (문서) |
| #461 | ADR-005 ArchUnit 모듈 의존성 규칙 검증 테스트 | MERGED | ADR-005 | 낮음 (테스트) |
| #462 | ADR-005 CubeCostPolicyTest 호환성 수정 | MERGED | ADR-004, ADR-005 | 낮음 (테스트) |
| #463 | stop hook에 세션 리포트 자동 생성 추가 | MERGED | 없음 | 없음 (도구) |
| #464 | ADR-005 Web Controller Migration - Port 추출 1단계 | MERGED | ADR-005 | 높음 |
| #465 | module-infra 및 module-core 구조 정리 | MERGED | ADR-004, ADR-005 | 중간 |
| #466 | ADR-005 DonationPort 인터페이스 및 어댑터 추가 | MERGED | ADR-005 | 높음 |

---

## 3. 상세 분석

### 3.1 PR #458: ADR-004 module-core 도메인 이관 (Phase 1)

**관련 이슈**: #415, #416, #417, #418
**머지 일시**: 2026-02-28T19:58:43Z

#### 주요 변경 사항

**이관된 도메인:**

1. **Calculator 도메인** (`module-core/calculator/`)
   - `domain/`: ExpectationCalculatorPort, EnhanceDecorator, BaseItem, EquipmentExpectationCalculatorPort, EquipmentEnhanceDecorator, BaseEquipmentItem
   - `port/`: CubeRatePort, CubeCostPort, StarforceLookupPort, StatParserPort

2. **Starforce 도메인** (`module-core/starforce/`)
   - `domain/`: StarforceConstants, StarforceCalculationEngine, NoljangProbabilityCalculator

3. **Flame 도메인** (`module-core/flame/`)
   - `config/`: BossEquipmentRegistry, JobStatMapping
   - `component/`: FlameScoreResolver
   - `port/`: FlameTrialsPort
   - `service/`: FlameTrialsService

4. **Cube 도메인** (분석만 수행, 이관 유예)
   - 높은 인프라 의존성으로 유예 결정
   - 별도 분석 보고서 작성됨

#### ADR-004 준수 여부

| 항목 | 상태 | 비고 |
|------|------|------|
| 순수 비즈니스 로직 이관 | ✅ | Calculator, Starforce, Flame 이관 완료 |
| Spring 의존성 제거 | ✅ | Core 모듈에 Spring 의존성 없음 |
| Port 인터페이스 정의 | ✅ | 4개 Port 정의 |
| Kotlin 마이그레이션 | ✅ | Core 파일 모두 Kotlin으로 작성 |

#### 검증 결과
- 빌드 성공 (58 tasks)
- 테스트 통과 (745 tests)
- ArchUnit 검증 통과

---

### 3.2 PR #459: ADR-004 Policy 도메인 이관

**관련 이슈**: #419
**머지 일시**: 2026-02-28T20:14:51Z

#### 주요 변경 사항

**이관된 구조:**
```
module-core/
├── domain/model/PotentialGrade.kt
├── policy/
│   ├── CostCalculationStrategy.kt
│   └── TableBasedCostStrategy.kt
└── port/out/PolicyPort.kt

module-infra/
└── adapter/policy/PolicyAdapter.kt
```

#### ADR-004 준수 여부

| 항목 | 상태 | 비고 |
|------|------|------|
| PolicyPort 인터페이스 정의 | ✅ | `module-core/port/out/PolicyPort.kt` |
| PolicyAdapter 구현 | ✅ | `module-infra/adapter/policy/PolicyAdapter.kt` |
| 의존성 방향 | ✅ | app → core ← infra |
| Kotlin fun interface | ✅ | `fun interface CostCalculationStrategy` |

---

### 3.3 PR #460: ADR-005 모듈 의존성 그래프 및 이관 전략 수립

**관련 이슈**: #410, #411
**머지 일시**: 2026-02-28T20:29:16Z

#### 주요 내용

**의존성 그래프 (목표):**
```
module-web  ──────>  module-app  ──────>  module-core
                           ^                    ^
                           |                    |
                    module-infra ───────────────┘
                    (implements ports)
```

**이관 순서 계획:**
1. #410 Gradle 의존성 규칙 고정
2. #435 Common 모듈 정리
3. #411-413 Web 이관
4. #414 Application 계층 정리
5. #424-434 Infra 이관
6. #439-443 통합 검증/문서화

#### 생성된 문서
- `docs/adr/ADR-005-module-dependency-strategy.md`
- `docs/05_Reports/module-migration-progress-report.md`

---

### 3.4 PR #461: ADR-005 ArchUnit 모듈 의존성 규칙 검증 테스트

**관련 이슈**: #410
**머지 일시**: 2026-02-28T20:33:32Z

#### 추가된 검증 규칙

| 규칙 | 설명 | 상태 |
|------|------|------|
| core → infra/web/app 금지 | Core는 순수 도메인만 | PASS |
| common → spring-web 금지 | Common은 가벼워야 함 | PASS |
| infra → app services 금지 | Infra는 App을 모름 | PASS |
| 순환 의존성 없음 | 전체 모듈 순환 검사 | PASS |

#### 파일
- `module-web/src/test/kotlin/maple/expectation/web/arch/ModuleDependencyTest.kt`

---

### 3.5 PR #462: ADR-005 CubeCostPolicyTest 호환성 수정

**관련 이슈**: #435
**머지 일시**: 2026-02-28T21:14:19Z

#### 작업 내용

PR #459 (Policy 도메인 이관) 후 누락된 테스트 수정:
- `CostCalculationStrategy` → `PolicyPort` 의존성 변경 반영
- `InvalidPotentialGradeException` → `IllegalArgumentException` 예외 타입 반영

#### module-common 검증 결과
- Spring 의존성 0개 확인
- 공통 예외 계층 48개 확인
- CircuitBreaker Marker 인터페이스 확인

---

### 3.6 PR #463: stop hook에 세션 리포트 자동 생성 추가

**머지 일시**: 2026-02-28T21:23:01Z

#### 작업 내용
- `.claude/hooks/stop-validation.sh`에 리포트 생성 로직 추가
- 세션 종료 시 `docs/05_Reports/`에 마크다운 리포트 자동 생성
- 최근 커밋, 변경 파일, 검증 결과 포함

**ADR 관련성**: 없음 (개발 도구 개선)

---

### 3.7 PR #464: ADR-005 Web Controller Migration - Port 추출 1단계

**관련 이슈**: #411, #412, #413
**머지 일시**: 2026-02-28T22:13:46Z

#### 주요 변경 사항

**추가된 Port 인터페이스:**
1. `GameCharacterPort.kt` - 캐릭터 조회 Port
2. `AuthPort.kt` - 인증 Port
3. `AuthCommand.kt`, `AuthResult.kt`, `TokenResult.kt` - Core DTO

**Adapter 구현:**
1. `GameCharacterPortAdapter.java` - GameCharacterService 래핑
2. `AuthPortAdapter.java` - AuthService 래핑

#### 의존성 그래프 변경
```
Before: module-web → module-app (직접 Service 의존)
After:  module-web → module-core(Port) ← module-app(Adapter)
```

#### 분석 결과 (5명 Opus 에이전트)
| 항목 | 결과 |
|------|------|
| Controller 의존성 | 9개 Service → 22개 메서드 |
| Port 추출 필요 | 9개 Port |
| DTO 이관 가능 | 7개 |
| DTO 삭제 가능 | 2개 (Kotlin 중복) |

---

### 3.8 PR #465: module-infra 및 module-core 구조 정리

**머지 일시**: 2026-02-28T22:30:30Z

#### 주요 변경 사항

**module-infra:**
- `infra/` → `infrastructure/` 디렉토리 통합
- Java → Kotlin 변환 (BufferRecoveryScheduler, LikeProcessor 등)
- 불필요한 Java 파일 4개 삭제

**module-core:**
- Kotlin 파일 이관: `src/main/java` → `src/main/kotlin` (14개)
- package-info.java 3개 삭제

**module-app:**
- Port 어댑터 `@Service` → `@Component` 변경 (ArchUnit 규칙 준수)

#### 트레이드 오프
- 어댑터는 비즈니스 서비스가 아니므로 `@Component` 사용
- infra/infrastructure 중복 디렉토리를 infrastructure로 통합

---

### 3.9 PR #466: ADR-005 DonationPort 인터페이스 및 어댑터 추가

**머지 일시**: 2026-02-28T22:40:35Z

#### 주요 변경 사항

**추가된 파일:**
1. `module-core/.../port/inbound/DonationPort.kt` - 인터페이스
2. `module-core/.../port/inbound/DonationCommand.kt` - DTO
3. `module-app/.../adapter/in/DonationPortAdapter.java` - 구현체

#### ADR-005 준수 여부
- Port 인터페이스를 module-core에 배치 ✅
- Adapter 구현체를 module-app에 배치 ✅
- 의존성 역전 원칙 준수 ✅

---

## 4. ADR 매핑 분석

### 4.1 ADR-004 (Module-Core 도메인 이관)

**누락된 내용 없음** - PR #458, #459에서 ADR-004의 주요 내용이 충실히 구현됨

**ADR-004 이관 현황:**

| 도메인 | 계획 상태 | 실제 상태 | 비고 |
|--------|----------|----------|------|
| Calculator | Phase 2 | ✅ 완료 | PR #458 |
| Starforce | Phase 2 | ✅ 완료 | PR #458 |
| Flame | Phase 2 | ✅ 완료 | PR #458 |
| Cube | Phase 2 | ⏸️ 유예 | 인프라 의존성 높음 |
| Policy | Phase 4 | ✅ 완료 | PR #459 |
| V4 Logic | Phase 4 | 🔲 예정 | - |
| V5 Logic | Phase 4 | 🔲 예정 | - |
| Monitoring | Phase 4 | 🔲 예정 | - |

**권장 업데이트:**
- ADR-004 상태를 "Proposed" → "Partially Implemented"로 변경 권장
- Phase 2 완료 항목 명시
- Cube 유예 사유 및 분석 보고서 링크 추가

---

### 4.2 ADR-005 (모듈 의존성 그래프 및 이관 전략)

**누락된 내용 없음** - PR #460, #461, #464, #466에서 ADR-005의 전략이 구현됨

**ADR-005 이관 현황:**

| Phase | 항목 | 상태 | 관련 PR |
|-------|------|------|---------|
| P0 | #410 Gradle 의존성 규칙 고정 | ✅ 완료 | #461 |
| P0 | #435 Common 모듈 정리 | ✅ 완료 | #462 |
| P1 | #411-413 Web 이관 | 🔄 진행중 | #464, #466 |
| P2 | #424-434 Infra 이관 | 🔲 예정 | - |
| P3 | #439-443 통합 검증/문서화 | 🔲 예정 | - |

**권장 업데이트:**
- ADR-005 상태를 "Proposed" → "In Progress"로 변경 권장
- Web 이관 진행 상황 (GameCharacterPort, AuthPort, DonationPort 완료) 추가
- 다음 단계 (LikePort, 추가 Port 추출) 명시

---

## 5. 아키텍처 변경 사항 요약

### 5.1 의존성 그래프 변화

**Before (PR #458 이전):**
```
module-app (모든 비즈니스 로직)
    ↓
module-infra (인프라 구현)
    ↓
module-common (공통 유틸)
```

**After (PR #466 이후):**
```
module-web  ──────>  module-app  ──────>  module-core
     |                   |                    ^
     |                   |                    |
     |                   └────────────────────┘
     |                        (Port 의존)
     v
module-infra ───────────────────┘
          (Port 구현)
```

### 5.2 Port/Adapter 구현 현황

| Port | 인터페이스 위치 | 구현체 위치 | 상태 |
|------|----------------|------------|------|
| CubeRatePort | module-core | module-app | ✅ |
| CubeCostPort | module-core | module-app | ✅ |
| StarforceLookupPort | module-core | module-app | ✅ |
| StatParserPort | module-core | module-app | ✅ |
| FlameTrialsPort | module-core | module-app | ✅ |
| PolicyPort | module-core | module-infra | ✅ |
| GameCharacterPort | module-core | module-app | ✅ |
| AuthPort | module-core | module-app | ✅ |
| DonationPort | module-core | module-app | ✅ |

---

## 6. 권장 사항

### 6.1 ADR 문서 업데이트

1. **ADR-004 상태 변경**
   - `Proposed` → `Partially Implemented`
   - Phase 2 완료 항목 업데이트
   - Cube 유예 사유 보강

2. **ADR-005 상태 변경**
   - `Proposed` → `In Progress`
   - Web 이관 진행 상황 추가
   - Port 추출 현황 테이블 추가

### 6.2 다음 단계

1. **Web Controller Migration (계속)**
   - LikePort, AlertPort 등 추가 Port 추출
   - Controller → module-web 이관
   - DTO 마이그레이션

2. **Infra 이관 (Phase 3)**
   - Batch/Cache/Redis/Client 구현체 이관
   - Adapter 패턴 적용

3. **통합 검증 (Phase 4)**
   - CI 파이프라인 업데이트
   - 성능 벤치마크
   - 문서화 완료

---

## 7. 결론

PR 458-466은 ADR-004와 ADR-005에 명시된 아키텍처 목표를 충실히 따르고 있습니다. 주요 성과:

1. **Core 도메인 이관**: Calculator, Starforce, Flame, Policy 도메인이 module-core로 이관됨
2. **Port/Adapter 패턴 적용**: 9개 Port 인터페이스와 구현체가 정의됨
3. **의존성 방향 정립**: web → app → core ← infra 구조 확립
4. **자동화된 검증**: ArchUnit 테스트로 의존성 규칙 검증

**ADR 문서 상태 업데이트가 권장됩니다.**

---

**보고서 작성자**: Claude (Architect)
**분석 일시**: 2026-02-28
**문서 버전**: 1.0.0
