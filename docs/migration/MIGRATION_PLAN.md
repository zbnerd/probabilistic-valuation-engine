# Java-to-Kotlin Migration Plan

## 분석 결과 종합

### 전체 현황
- **총 파일 수**: 552개 Java 파일
- **총 라인 수**: 132,164라인
- **현재 Kotlin 파일**: 0개 (전체 Java)

### 모듈별 통계
| 모듈 | 파일 수 | 라인 수 | 평균 라인/파일 | 난이도 |
|------|---------|---------|----------------|--------|
| module-infra | 239 | 58,192 | 243.6 | MEDIUM-HIGH |
| module-core | 49 | 7,804 | 159.3 | MEDIUM |
| module-common | 3 | 100 | 33.3 | LOW |
| **module-app** | **261** | **66,068** | **253.1** | **HIGH** | (**마이그레이션 안 함*)

### 주요 전환 과제
1. **Lombok 애노테이션**: 606개 발생
2. **Optional 사용**: 350회 발생
3. **체크 예외**: 181개 발생
4. **static 메서드**: 108개 파일
5. **인터페이스 구현**: 92개

## 의존성 기반 Phase 분할

### Phase 1: Foundation (의존 없음)
- **대상**: module-common, module-core
- **이유**: Leaf 노드로 다른 모듈에 의존하지 않음
- **선행 Phase**: 없음
- **전환 순서**: common → core

### Phase 2: Infrastructure (의존: Phase 1)
- **대상**: module-infra
- **이유**: module-core와 module-common에 의존
- **선행 Phase**: Phase 1 완료 후
- **주의사항**: Spring Bean, AOP, Redis 복잡성

### Phase 3: Infrastructure (의존: Phase 1, 2)
- **대상**: module-infra
- **이유**: module-core와 module-common에 의존
- **선행 Phase**: Phase 1 완료 후
- **주의사항**: Spring Bean, AOP, Redis 복잡성

## 상세 이슈 정의

---

### [Migration] Phase 1: module-common

**Title**: [Migration] Phase 1: module-common/shared

**Labels**: refactor, kotlin-migration

**Priority**: P1 (의존성 순서 기반)

## 대상 파일
- [ ] CommonException.java (30 lines, 난이도: Low)
- [ ] CommonErrorCode.java (40 lines, 난이도: Low)
- [ ] CommonUtils.java (30 lines, 난이도: Low)

## 의존성
- 의존: 없음
- 피의존: module-core
- 선행 Phase: 없음

## 전환 주의사항
- Lombok 사용: 있음 (@Slf4j)
- static method: 0개 → 필요 없음
- Java 호출처: 0곳 → 필요 없음
- 난이도: Low

## 완료 조건
- [ ] Java → Kotlin 전환
- [ ] .java 삭제
- [ ] compileJava compileKotlin 통과
- [ ] 피의존 모듈 컴파일 통과
- [ ] 테스트 통과

---

### [Migration] Phase 2: module-core

**Title**: [Migration] Phase 2: module-core/domain

**Labels**: refactor, kotlin-migration

**Priority**: P2 (의존성 순서 기반)

## 대상 파일
- [ ] FlameEquipCategory.java (150 lines, 난이도: Medium)
- [ ] FlameType.java (100 lines, 난이도: Medium)
- [ ] AlertMessage.java (200 lines, 난이도: Medium)
- [ ] AlertPriority.java (50 lines, 난이도: Low)
- [ ] CharacterId.java (80 lines, 난이도: Low)
- [ ] CubeRate.java (120 lines, 난이도: Medium)
- [ ] CubeType.java (60 lines, 난이도: Low)
- [ ] PotentialStat.java (150 lines, 난이도: Medium)
- [ ] StatParser.java (300 lines, 난이도: High)
- [ ] StatType.java (80 lines, 난이도: Low)
- [ ] DensePmf.java (400 lines, 난이도: High)
- [ ] SparsePmf.java (350 lines, 난이도: High)
- [ ] EquipmentData.java (500 lines, 난이도: High)
- [ ] CharacterEquipment.java (450 lines, 난이도: High)
- [ ] CharacterId.java (80 lines, 난이도: Low)
- [ ] CharacterLike.java (300 lines, 난이도: Medium)
- [ ] ProbabilityInvariantException.java (50 lines, 난이도: Low)
- [ ] 계산기 인터페이스들 (8 files, ~1200 lines, 난이도: Medium)
- [ ] 도메인 모델들 (20 files, ~3000 lines, 난이도: Medium)

## 의존성
- 의존: module-common
- 피의존: module-infra, module-app
- 선행 Phase: Phase 1

## 전환 주의사항
- Lombok 사용: 100개 이상 → data class 전환
- static method: 15개 → @JvmStatic 필요
- Java 호출처: module-infra (25곳), module-app (100곳)
- 난이도: Medium

## 완료 조건
- [ ] Java → Kotlin 전환
- [ ] .java 삭제
- [ ] compileJava compileKotlin 통과
- [ ] 피의존 모듈 컴파일 통과
- [ ] 테스트 통과

---

### [Migration] Phase 3: module-infra

**Title**: [Migration] Phase 3: module-infra/infrastructure

**Labels**: refactor, kotlin-migration

**Priority**: P3 (의존성 순서 기반)

## 대상 파일
- [ ] Redisson 관련 (20 files, ~4000 lines, 난이도: High)
- [ ] JPA Repository (15 files, ~3000 lines, 난이도: Medium)
- [ ] Spring Configuration (25 files, ~5000 lines, 난이도: High)
- [ ] AOP Aspects (10 files, ~2000 lines, 난이도: High)
- [ ] Cache Manager (8 files, ~1500 lines, 난이도: Medium)
- [ ] External API Client (12 files, ~2500 lines, 난이도: Medium)
- [ ] Queue/Message (15 files, ~3000 lines, 난이도: Medium)
- [ ] Rate Limiting (8 files, ~1500 lines, 난이도: Medium)
- [ ] Security/JWT (10 files, ~2000 lines, 난이도: High)
- [ ] Utility Classes (15 files, ~2000 lines, 난이도: Low)

## 의존성
- 의존: module-core, module-common
- 피의존: module-app
- 선행 Phase: Phase 1, 2

## 전환 주의사항
- Lombok 사용: 300개 이상 → data class, @JvmField
- static method: 50개 → companion object
- Java 호출처: module-app (200곳 이상)
- 난이도: MEDIUM-HIGH

## 완료 조건
- [ ] Java → Kotlin 전환
- [ ] .java 삭제
- [ ] compileJava compileKotlin 통과
- [ ] 피의존 모듈 컴파일 통과
- [ ] 테스트 통과

---

### [Migration] Phase 4: module-app

**Title**: [Migration] Phase 4: module-app/application

**Labels**: refactor, kotlin-migration

**Priority**: P4 (의존성 순서 기반)

## module-app 제외
> **module-app은 마이그레이션하지 않음** (비즈니스 로직이 너무 복잡하고 위험도가 높음

## 의존성
- 의존: module-infra, module-core, module-common
- 피의존: 없음
- 선행 Phase: Phase 1, 2, 3

## 전환 주의사항
- Lombok 사용: 200개 이상 → data class, @JvmOverloads
- static method: 40개 → companion object
- Java 호출처: 없음 (최상위)
- 난이도: HIGH

## 완료 조건
- [ ] Java → Kotlin 전환
- [ ] .java 삭제
- [ ] compileJava compileKotlin 통과
- [ ] 테스트 통과
- [ ] 통합 테스트 통과

---

## 전략적 제안

### 1. 점진적 전환 (Incremental Migration)
- 각 Phase 내에서도 작은 단위로 나누어 전환
- 한 번에 10-20개 파일씩 전환
- 테스트 주기 단축 (매주)

### 2. 공존 기간 (Coexistence Period)
- V2, V4, V5 버전이 공존하는 상태에서 전환
- Java-Kotlin 혼용 상태 허용
- Build.gradle에서 bothSources 설정

### 3. 테스트 전략
- 단위 테스트: 100% 커버리지 유지
- 통합 테스트: 각 Phase 완료 후 실행
- 성능 테스트: 전환 전후 비교

### 4. 롤백 계획
- Git 브랜치 전략
- 각 Phase 별 별도 브랜치
- 롤백 시점 정의 (컴파일 실패, 테스트 실패)

### 5. 리소스 계획
- **예상 소요 기간**: 3-4개월 (Phase 1~3)
- **팀 구성**: Kotlin 숙련자 1명 + Java 개발자 2-3명
- **훈련 필요**: Kotlin 기본 문법, Spring Kotlin 확장 기능

### 6. 위험 관리
- **고위험**: module-infra의 Spring Bean 변환
- **중위험**: module-core의 도메인 모델
- **저위험**: module-common의 단순 모델

### 7. 성과 측정
- 코드 복잡도 감소 (Cyclomatic Complexity)
- 컴파일 시간 단축
- 테스트 커버리지 증가
- Null Pointer Exception 감소

---

## 실행 로드맵

| Phase | 예상 기간 | 주요 목표 | 성공 기준 |
|-------|----------|----------|------------|
| Phase 1 | 2주 | 공통 유틸리티 전환 | 100% 컴파일 성공 |
| Phase 2 | 4주 | 코어 도메인 전환 | 모듈 테스트 통과 |
| Phase 3 | 6주 | 인프라 전환 | 통합 테스트 통과 |
| Phase 4 | 8주 | 애플리케이션 전환 | 전체 테스트 통과 |
| 검증 | 2주 | 성능/안정성 검증 | 프로덕션 배포 준비 |

**총 예상 기간: 22주 (약 5개월)**

---

## 모니터링 체크리스트

### 각 Phase 완료 시 확인 사항
- [ ] 모든 Java 파일이 Kotlin으로 전환되었는가?
- [ ] 컴파일 오류가 없는가?
- [ ] 테스트가 100% 통과하는가?
- [ ] 의존성이 정상 작동하는가?
- [ ] 성능 저하가 없는가?

### 전체 프로젝트 완료 시 확인 사항
- [ ] 모든 모듈이 Kotlin으로 전환되었는가?
- [ ] 프로덕션 환경에서 안정적으로 실행되는가?
- [ ] 기능 테스트가 모두 통과하는가?
- [ ] 문서가 최신으로 업데이트되었는가?
- [ ] 팀원들이 Kotlin을 숙련하게 사용하는가?