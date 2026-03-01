# PR 390-396 ADR 분석 리포트

**분석 일시**: 2026-03-01
**분석자**: Architect Agent
**대상 PR**: 390, 391, 392, 393, 394, 395, 396

---

## 요약

| 항목 | 수량 |
|------|------|
| 분석 PR 수 | 7개 |
| 아키텍처 변경 포함 | 7개 |
| ADR 작성 필요 | 1개 (ADR-006) |
| 기존 ADR 매핑 | 2개 (ADR-002, ADR-003) |

---

## PR별 분석

### PR #390: Java-to-Kotlin 마이그레이션 Phase 1-1, 2-1, 2-2

| 항목 | 내용 |
|------|------|
| **제목** | feat: Java-to-Kotlin 마이그레이션 Phase 1-1, 2-1, 2-2 완료 |
| **아키텍처 변경** | ✅ Yes |
| **변경 규모** | 28개 파일 변환 (module-common, module-core) |
| **관련 ADR** | ADR-002 (4-Module Separation + Kotlin Migration) |
| **조치** | ADR-002에서 이미 다루고 있음. 추가 ADR 불필요 |

**주요 변경사항:**
- module-common: 100% Kotlin 완료 (CommonErrorCodeTest 변환)
- module-core/domain/flame/: 5개 파일 변환 (FlameEquipCategory, FlameType 등)
- module-core/domain/model/: 7개 파일 변환 (AlertMessage, CharacterId 등)
- module-core/domain/stat/: 2개 파일 변환 (StatParser, StatType)
- Java 호환성 어노테이션 추가 (`@get:JvmName`, `@JvmStatic`, `@JvmField`)

---

### PR #391: Java-to-Kotlin 마이그레이션 Phase 2-3, 3-1~3-5

| 항목 | 내용 |
|------|------|
| **제목** | feat: Java-to-Kotlin 마이그레이션 Phase 2-3, 3-1~3-5 완료 |
| **아키텍처 변경** | ✅ Yes |
| **변경 규모** | 대규모 (module-core, module-infra 전체) |
| **관련 ADR** | ADR-002 (4-Module Separation + Kotlin Migration) |
| **조치** | ADR-002에서 이미 다루고 있음. 추가 ADR 불필요 |

**주요 변경사항:**
- module-core Phase 2-3: 도메인 이벤트, 비용 포맷터, 포트 인터페이스, 계산기
- module-infra Phase 3-1~3-5: Redis/Cache, JPA Repository, Spring Config, AOP Aspects, External API Client
- 변환 규칙 수립: `@Data` → `data class`, `static` → `@JvmStatic` in `companion object`
- Java-Kotlin interop 수정: `@JvmStatic`, `@get:JvmName`, nullable 처리

---

### PR #392: Cube decorators and DTOs Kotlin migration

| 항목 | 내용 |
|------|------|
| **제목** | feat: Java to Kotlin migration for Cube decorators and DTOs |
| **아키텍처 변경** | ✅ Yes (Java-Kotlin Builder 패턴 interop) |
| **변경 규모** | 6개 파일 변환 |
| **관련 ADR** | ADR-002 (4-Module Separation + Kotlin Migration) |
| **조치** | ADR-002에서 이미 다루고 있음. 추가 ADR 불필요 |

**주요 변경사항:**
- CubeCalculationInput.java → Kotlin data class (Builder 패턴 유지)
- BlackCubeDecorator, RedCubeDecoratorV4, AdditionalCubeDecoratorV4, BlackCubeDecoratorV4 변환
- GoldenMasterTests 변환
- Boolean 프로퍼티 `isXxx()` 네이밍 컨벤션 유지 (`@get:JvmName`)

---

### PR #393: Guardrails documentation with hook patterns

| 항목 | 내용 |
|------|------|
| **제목** | docs: Add guardrails documentation with hook patterns |
| **아키텍처 변경** | ✅ Yes (**새로운 Guardrails 시스템 도입**) |
| **변경 규모** | 42개 파일 추가 (docs/guardrails/) |
| **관련 ADR** | **없음 - ADR-006 작성 필요** |
| **조치** | **ADR-006 신규 작성 필요** |

**주요 변경사항:**
- INDEX.json v1.2.0: 24개 가드레일 패턴 정의
- Architecture guardrails (5개): ADR decisions, Stateless, V2/V4 modules, System design, Multi-agent
- Backend/Spring guardrails (6개): LogicExecutor, Exception handling, AOP/Facade, Optional chaining, SOLID
- Backend/Resilience guardrails (3개): Circuit Breaker, Marker Interface, Fallback
- Testing guardrails (6개): Unit test, Flaky test prevention, Concurrency test, Chaos engineering
- PreToolUse 훅 테스트: 6개 패턴 즉시 차단 확인

**이것은 중요한 아키텍처 결정입니다.** Guardrails 시스템은 코드 품질을 자동으로 검증하는 새로운 메커니즘입니다.

---

### PR #394: Layer 2 AI Context Injection to guardrails hook

| 항목 | 내용 |
|------|------|
| **제목** | feat: Add Layer 2 AI Context Injection to guardrails hook |
| **아키텍처 변경** | ✅ Yes (2계층 검증 시스템) |
| **변경 규모** | 1개 파일 (pre-tool-use.sh) |
| **관련 ADR** | ADR-006 (PR #393에서 시작) |
| **조치** | ADR-006에 포함 |

**주요 변경사항:**
- Layer 1: Regex 패턴 매칭 (6개 패턴 즉시 차단)
- Layer 2: AI Context Injection (13개 복잡한 패턴)
- 총 커버리지: 19/24 패턴 (100%)

---

### PR #395: Java to Kotlin migration issues #377, #378

| 항목 | 내용 |
|------|------|
| **제목** | feat: Java to Kotlin migration for issues #377, #378 |
| **아키텍처 변경** | ✅ Yes |
| **변경 규모** | 6개 파일 변환 |
| **관련 ADR** | ADR-002 (4-Module Separation + Kotlin Migration) |
| **조치** | ADR-002에서 이미 다루고 있음. 추가 ADR 불필요 |

**주요 변경사항:**
- AsyncResponseUtils.java → AsyncResponseUtils.kt
- AsyncUtils.java → AsyncUtils.kt
- GlobalExceptionHandler.java → GlobalExceptionHandler.kt
- `object` 선언으로 utility class 구현
- Kotlin `when` expression, `is` 연산자, string templates 적용

---

### PR #396: Guardrails INDEX v2.0.0 Kotlin-compatible upgrade

| 항목 | 내용 |
|------|------|
| **제목** | feat: Guardrails INDEX v2.0.0 Kotlin-compatible upgrade |
| **아키텍처 변경** | ✅ Yes (88개 패턴으로 확장, Kotlin 호환) |
| **변경 규모** | 57개 파일 (INDEX.json v2.0.0 + 55개 가드레일 문서) |
| **관련 ADR** | ADR-006 (PR #393에서 시작) |
| **조치** | ADR-006에 포함 |

**주요 변경사항:**
- INDEX.json v2.0.0: `languages: ["java", "kotlin"]` 필드 추가
- 88개 가드레일 패턴으로 확장 (기존 24개 → 88개)
- AI 판단 패턴 4개 전환 (regex 제거 → keywords + AI 판단)
- Kotlin 호환 regex 수정
- HOOK_GUIDE.md 신규 작성 (PreToolUse/PostToolUse 구현 가이드)
- TEST_REPORT.md 및 guardrail-test.sh 추가

---

## 작성된 ADR 목록

### ADR-006: Guardrails 시스템 도입 (신규 작성 필요)

**상태**: Proposed → Accepted

**PR 매핑:**
- PR #393: 초기 Guardrails 시스템 (24개 패턴)
- PR #394: Layer 2 AI Context Injection
- PR #396: v2.0.0 Kotlin 호환 업그레이드 (88개 패턴)

**핵심 내용:**
1. **2계층 검증 구조**
   - Layer 1: Regex 패턴 매칭 (즉시 차단)
   - Layer 2: AI Context Injection (복잡한 패턴)

2. **88개 가드레일 패턴**
   - Architecture (17개): Stateless, ADR decisions, System design
   - Backend/Spring (23개): LogicExecutor, Exception handling, SOLID
   - Backend/Resilience (8개): Circuit Breaker, Marker Interface
   - Backend/Cache (8개): TieredCache, Single-flight
   - Backend/Concurrency (6개): Async patterns, Thread pool
   - Testing (6개): Unit test, Flaky test prevention
   - Infrastructure (5개): Redis, Scale-out
   - Database (1개): Connection pool
   - Coding Style (1개): FQCN usage

3. **Kotlin 호환성**
   - `languages: ["java", "kotlin"]` 필드
   - Kotlin regex 패턴 지원 (`throw\s+(?:new\s+)?RuntimeException`)

---

## 기존 ADR 매핑 요약

| ADR | 관련 PR | 매핑 상태 |
|-----|---------|----------|
| ADR-002 | #390, #391, #392, #395 | ✅ 이미 다루고 있음 |
| ADR-003 | #390, #391 (Port 인터페이스) | ✅ 이미 다루고 있음 |
| ADR-005 | #391 (모듈 의존성) | ✅ 이미 다루고 있음 |
| **ADR-006** | **#393, #394, #396** | ⚠️ **신규 작성 필요** |

---

## 결론 및 권장사항

### 1. ADR-006 작성 (필수)

Guardrails 시스템은 **중요한 아키텍처 결정**입니다. 다음 내용을 포함해야 합니다:

```markdown
# ADR-006: Guardrails 시스템 도입

## 상태
Accepted (2026-03-01)

## 컨텍스트
- 코드 품질 자동 검증 필요
- Java-Kotlin 마이그레이션 중 규칙 준수 확인 필요
- CLAUDE.md 규칙 자동 검증 필요

## 결정
2계층 Guardrails 시스템 도입:
- Layer 1: Regex 패턴 매칭
- Layer 2: AI Context Injection

88개 가드레일 패턴 정의 (INDEX.json v2.0.0)

## 근거
1. 코드 리뷰 비용 감소
2. 일관된 코드 품질 유지
3. 마이그레이션 중 규칙 위반 조기 감지

## 관련 PR
- #393: 초기 시스템 (24개 패턴)
- #394: Layer 2 AI Context Injection
- #396: v2.0.0 Kotlin 호환 (88개 패턴)
```

### 2. ADR-002 업데이트 (선택)

Java-to-Kotlin 마이그레이션 진척도를 반영하여 ADR-002에 다음 내용 추가 고려:
- Phase별 완료 상태
- Java-Kotlin interop 패턴 요약
- Builder 패턴 유지 전략

### 3. 리포트 보관

이 리포트는 `docs/05_Reports/PR_ADR_Analysis_390-396.md`에 보관됩니다.

---

## 부록: 파일 변경 통계

| PR | 추가 | 삭제 | 총 변경 |
|----|------|------|---------|
| #390 | 270+ | 28 files | 28 files |
| #391 | 1000+ | 1000+ | 118 files |
| #392 | 919 | 905 | 12 files |
| #393 | 8000+ | 0 | 42 files |
| #394 | 261 | 0 | 1 file |
| #395 | 541 | 760 | 6 files |
| #396 | 5000+ | 300+ | 57 files |

---

**작성 완료**: 2026-03-01
