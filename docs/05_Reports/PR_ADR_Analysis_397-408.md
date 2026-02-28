# PR 397-408 ADR 분석 리포트

## 요약

| 항목 | 수치 |
|------|------|
| 분석 PR 수 | 12개 |
| ADR 작성 필요 | 0개 (기존 ADR로 커버) |
| 기존 ADR 매핑 | 5개 PR |
| 문서화만 해당 | 6개 PR |
| 버그/호환성 수정 | 5개 PR |

**핵심 결론:** PR 397-408은 모두 기존 ADR(ADR-003, ADR-005, ADR-036, ADR-001)의 범위 내에서 수행된 작업으로, **신규 ADR 작성이 필요하지 않습니다.**

---

## PR별 분석

### PR #397: Java to Kotlin migration test compatibility fixes

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No |
| **관련 ADR** | ADR-001 (options nullability) |
| **조치** | ADR 불필요 (테스트 호환성 수정만) |

**상세:**
- Kotlin non-null 파라미터 테스트 수정 (NullPointerException 예상)
- RedisBufferStrategyTest lenient mock 오버라이드
- Java accessor 구문 수정 (getXxx() 메서드)
- 742 tests pass (0 failures)

---

### PR #398: Architecture refactoring - BufferRecoveryScheduler migration

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | Yes |
| **관련 ADR** | **ADR-036** (monitoring/config → module-infra 이관) |
| **조치** | 이미 ADR 존재 |

**상세:**
- BufferRecoveryScheduler → module-infra 이관
- Java → Kotlin migration (monitoring collectors, schedulers)
- Port 인터페이스 추가 (BackoffStrategy, AtomicFetchStrategy, LikeEventPort)
- infra → app 역의존성 제거

**ADR-036 매핑:** Phase 1-6 이관 전략의 일부로 수행됨

---

### PR #399: Architecture realignment - monitoring/config migration to module-infra

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | Yes |
| **관련 ADR** | **ADR-036** (monitoring/config → module-infra 이관) |
| **조치** | 이미 ADR 존재 |

**상세:**
- BufferStatusQuery Port 인터페이스 생성 (module-core)
- RedisBufferRepositoryImpl이 BufferStatusQuery 구현
- module-app/src/main/kotlin/maple/expectation/monitoring/ 삭제 (9개 Kotlin 파일)
- Java config 파일 11개 삭제

**ADR-036 매핑:** Phase 1-2 완료 (monitoring 중복 제거, config 중복 제거)

---

### PR #400: Web module extraction - GlobalExceptionHandler migration

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | Yes (신규 모듈 생성) |
| **관련 ADR** | **ADR-005** (모듈 의존성 그래프 및 이관 전략) |
| **조치** | ADR-005 Phase 2 진행 중으로 표기 권장 |

**상세:**
- GlobalExceptionHandler → module-web 이관
- PiiMaskingFilter 제거 (module-infra 보안 레이어로 이동)
- Spring isolation 테스트 업데이트
- verify-guardrails 스킬 추가

**ADR-005 매핑:**
```
Phase 2: 외부 계층 이관 (P1)
3. #411-413 Web 이관
   - Controller → module-web ✓ (진행 중)
   - Filter → module-web
   - WebConfig → module-web
```

**권장 조치:** ADR-005 상태를 "Proposed" → "Partially Implemented"로 업데이트

---

### PR #401: Kotlin compiler warnings fixes

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No |
| **관련 ADR** | ADR-002 (module-separation-kotlin) |
| **조치** | ADR 불필요 |

**상세:**
- BaseDto.kt: 중복 `open` modifier 제거
- CubeCalculationInput.kt: 불필요한 null 체크 제거

---

### PR #402: ResilientLockStrategy 다이어그램 및 ADR 통합

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No (문서화만) |
| **관련 ADR** | 없음 |
| **조치** | ADR 불필요 |

**상세:**
- Class Diagram: LockStrategy 계층 구조
- 3-Tier Lock Architecture: Resilient → Redis → MySQL
- Sequence Diagrams: 정상 흐름 및 Fallback 흐름
- ADR 문서 통합 (docs/adr → docs/01_ADR)

---

### PR #403: 포트폴리오 기술 심화 분석 문서

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No (문서화만) |
| **관련 ADR** | 없음 |
| **조치** | ADR 불필요 |

**상세:**
- 7개 핵심 성과에 대한 Mermaid 다이어그램
- MySQL 최적화, Cache Stampede, Testcontainers
- CompletableFuture, Outbox, ResilientLockStrategy, Chaos Engineering

---

### PR #404: Cache Stampede Before 다이어그램

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No (문서화만) |
| **관련 ADR** | ADR-003 (tiered-cache-singleflight) |
| **조치** | ADR 불필요 |

**상세:**
- Before: Cache Stampede 발생 다이어그램 추가
- 100개 요청 동시 DB 유입 시각화

---

### PR #405: Nexon API Outbox 다이어그램

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No (문서화만) |
| **관련 ADR** | ADR-016 (nexon-api-outbox-pattern) |
| **조치** | ADR 불필요 |

**상세:**
- Nexon API Outbox 다이어그램 16:9 비율로 개선
- 5단계 구조로 명확한 흐름 표현

---

### PR #406: 포트폴리오 다이어그램 TD+LR 혼합 레이아웃

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No (문서화만) |
| **관련 ADR** | 없음 |
| **조치** | ADR 불필요 |

**상세:**
- Testcontainers 다이어그램: TB → TD+LR 서브그래프 구조
- ResilientLockStrategy 다이어그램: TB → TD+LR 서브그래프 구조

---

### PR #407: Kotlin nullable receiver 컴파일 에러 수정

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No |
| **관련 ADR** | **ADR-001** (options nullability) |
| **조치** | ADR-001 범위 내 수정 |

**상세:**
- `opt.trim().isNotEmpty()` → `opt?.trim()?.isNotEmpty() == true`
- ADR-001의 연장선상에 있는 수정

---

### PR #408: P1 보안 이슈 수정 (@PreAuthorize, Kotlin nullable)

| 항목 | 내용 |
|------|------|
| **아키텍처 변경** | No (보안 수정만) |
| **관련 ADR** | 없음 |
| **조치** | ADR 불필요 |

**상세:**
- GameCharacterControllerV5 @PreAuthorize 추가 (permitAll 명시)
- CubeCalculationInput Kotlin nullable 수정

**남은 P1 이슈 (별도 PR 필요):**
- Rate Limiting fail-open → fail-closed (ADR 필요)
- Race Condition 분산 락 (신중한 설계 필요)

---

## 기존 ADR 매핑 요약

| ADR | 관련 PR | 상태 |
|-----|---------|------|
| **ADR-001** (options nullability) | #397, #407, #408 | Accepted |
| **ADR-003** (Hexagonal Architecture) | #398, #399 | Proposed |
| **ADR-005** (모듈 의존성 전략) | #400 | Proposed → Partially Implemented 권장 |
| **ADR-036** (monitoring/config 이관) | #398, #399 | Proposed → Partially Implemented 권장 |

---

## 권장사항

### 1. ADR 상태 업데이트

**ADR-005와 ADR-036의 상태를 업데이트하는 것을 권장합니다:**

```markdown
# ADR-005, ADR-036 공통
## 상태
Partially Implemented (2026-02-28)

## 구현 진행
- Phase 1: 완료
- Phase 2: 진행 중 (PR #400)
```

### 2. 향후 PR에 대한 ADR 작성 기준

| 변경 유형 | ADR 필요 여부 |
|-----------|---------------|
| 모듈 분리/이관 | 기존 ADR 업데이트 또는 새 ADR |
| Web module extraction | ADR-005 확장 |
| Security 이슈 | 보안 관련 섹션 추가 (별도 ADR 불필요) |
| 문서/다이어그램만 | ADR 불필요 |
| 버그 수정 | ADR 불필요 |

### 3. 남은 P1 이슈에 대한 ADR 필요성

**Rate Limiting fail-open → fail-closed 변경**은 새로운 ADR이 필요할 수 있습니다:
- 현재: Rate Limit 실패 시 요청 허용 (fail-open)
- 권장: Rate Limit 실패 시 요청 차단 (fail-closed)
- 보안 vs 가용성 트레이드오프 분석 필요

---

## 결론

PR 397-408은 모두 **기존 ADR의 범위 내**에서 수행된 작업으로, 신규 ADR 작성은 필요하지 않습니다. 다만, ADR-005와 ADR-036의 상태를 "Proposed"에서 "Partially Implemented"로 업데이트하여 구현 진행 상황을 반영하는 것을 권장합니다.

---

**작성일:** 2026-02-28
**작성자:** Architect Agent
**검토자:** Team Lead
