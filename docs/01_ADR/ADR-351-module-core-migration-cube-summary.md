# ADR-351: Cube 도메인 이관 작업 요약

## 작업 개요
- **날짜**: 2026-02-28
- **작업 유형**: 도메인 이관 분석 및 보고서 작성
- **상태**: 분석 완료, 이관 유예 (Migration Deferred)

## 실행한 작업

### 1. 현황 분석 ✅
- Cube 패키지 파일 목록 파악 (12개 파일)
- 의존성 분석 (Core vs App-specific)
- 순환 의존성 식별

### 2. 이관 시도 및 제약 확인 ✅
- module-core로의 이관 시도
- 의존성 문제로 인한 롤백
- 대안 방안 모색

### 3. 문서화 ✅
- 상세 분석 보고서 작성
- 권장 사항 제시
- 향후 로드맵 정의

## 분석 결과

### 이관 제약 사항
1. **높은 Infra 의존성**: Spring Framework (@Component, @Cacheable), JPA Repository
2. **순환 의존성 위험**: Core → App → Core
3. **캐시 의존성**: @Cacheable은 Spring 컨텍스트 필수
4. **안정성**: 현재 구조가 이미 잘 작동 중

### 기존 Port 재사용 ✅
- `CubeRatePort.kt`: 큐브 성공률 조회
- `CubeCostPort.kt`: 큐브 비용 계산

이 Port들은 이미 module-core에 정의되어 있으며, cube 도메인에서 사용 가능합니다.

## 권장 사항

### Option A: 현재 상태 유지 (권장) ⭐
- Cube 도메인은 `service/v2/cube/` 패키지로 유지
- 핵심 로직은 이미 잘 분리됨
- 리스크 없이 안정적

### Option B: module-infra로 이관 (대안)
- Infra 계층에 적합
- Port 인터페이스 분리 작업 필요

### Option C: module-app 내 정리 (대안)
- 단일 모듈 내 책임 분리
- 이관 리스크 최소화

## 생성된 문서

1. **`docs/adr/ADR-004-module-core-migration-cube-report.md`**
   - 상세 분석 보고서
   - 의존성 매트릭스
   - 3가지 대안 비교

2. **`docs/adr/ADR-004-module-core-migration-cube-summary.md`** (본 문서)
   - 작업 요약
   - 실행 결과

## 검증 결과

### Build Status: ✅ PASS
```bash
./gradlew clean build -x test
BUILD SUCCESSFUL in 59s
58 actionable tasks: 58 executed
```

### Compilation: ✅ PASS
- module-core: Kotlin compilation successful
- module-app: Java/Kotlin compilation successful
- 모든 모듈 정상 빌드

## 향후 작업

### Phase 1: 정리 (즉시 실행 가능)
- [ ] 패키지 구조 재조직 (domain/decorator/calculator/config)
- [ ] Java → Kotlin 변환
- [ ] 문서화 개선

### Phase 2: Port 정의 (ADR-004 참조)
- [x] CubeRatePort 정의 완료
- [x] CubeCostPort 정의 완료
- [ ] 추가 Port 필요 시 정의

### Phase 3: 점진적 이관
- [ ] 순수 비즈니스 로직만 module-core로 이관
- [ ] Infra 의존성은 module-infra로 분리
- [ ] App 계층은 Core Port만 의존

## 결론

**Cube 도메인의 module-core 직접 이관은 권장하지 않음.**

### 근거
1. 현재 구조가 이미 안정적이고 잘 작동 중
2. Infra 의존성이 높아 Core 이관 시 순환 의존성 발생
3. Port 인터페이스(CubeRatePort, CubeCostPort)가 이미 정의되어 있어 헥사고날 아키텍처 준수

### 다음 단계
- 현재 구조 유지하며 기능 개발 집중
- 필요 시 ADR-004 Phase 4에서 재검토

---
**작성자**: Claude (Executor)
**승인자**: TBD
**문서 버전**: 1.0.0
**최종 업데이트**: 2026-02-28
