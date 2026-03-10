# claude.md

이 파일은 Claude Code(claude.ai/code)가 이 프로젝트에서 작업할 때 따라야 할 핵심 규칙을 정의합니다.

---

# RPI Workflow (Research - Plan - Implement)

**핵심 원칙:** 코드를 작성하기 전에 반드시 철저한 분석과 계획을 세운다.

## Phase 1: 조사 (Research)
- 관련 코드, 파일 구조, 데이터를 먼저 탐색 (Glob, Grep, Read)
- 기존 시스템과 의존성 파악
- 발견한 제약 사항, 문제점 요약 보고

## Phase 2: 계획 (Plan)
- 구체적이고 명확한 작업 계획 수립
- '어떤 파일'의 '어떤 부분'을 '어떻게' 수정할지 Step-by-step 작성
- **ADR 선행:** 구현 작업은 반드시 ADR 문서 먼저 작성 (`docs/adr/`)
- 계획 제시 후 **반드시 승인 대기**

## Phase 3: 실행 (Implement)
- 사용자가 계획을 승인했을 때만 코드 작성
- 합의된 계획에 따라서만 수정

---

# 코드 규칙 (Code Rules)

## 1. Zero Try-Catch Policy & LogicExecutor

**모든 패키지**에서 `try-catch` 및 `try-finally` 블록 사용 금지. 모든 실행 흐름과 예외 처리는 **LogicExecutor**에 위임.

| 패턴 | 메서드 | 용도 |
|------|--------|------|
| 1 | `execute(task, context)` | 일반 실행. 예외 발생 시 로그 기록 후 상위 전파 |
| 2 | `executeVoid(task, context)` | 반환값 없는 작업 |
| 3 | `executeOrDefault(task, default, context)` | 예외 발생 시 기본값 반환 |
| 4 | `executeWithRecovery(task, recovery, context)` | 예외 발생 시 복구 로직 실행 |
| 5 | `executeWithFinally(task, finalizer, context)` | 자원 해제 등 finally 필요 시 |
| 6 | `executeWithTranslation(task, translator, context)` | 기술적 예외를 도메인 예외로 변환 |

**허용 예외:** LogicExecutor 구현체 내부, AOP 순환참조 방지

## 2. Anti-Pattern: Lambda Hell

람다 내부 로직이 **3줄**을 초과하거나 분기문이 포함되면 즉시 **Private Method**로 추출.

```java
// Bad
return executor.execute(() -> {
    User user = repo.findById(id).orElseThrow(...);
    if (user.isActive()) { return process(user); }
    return List.of();
}, context);

// Good
return executor.execute(() -> this.processActiveUser(id), context);
```

## 3. Anti-Pattern: BigDecimal(Double) 금지

```java
// Bad - 부동소수점 오차
new BigDecimal(0.1)  // → 0.10000000000000000555...

// Good
new BigDecimal("0.1")
BigDecimal.valueOf(0.1)
```

## 4. Optional Chaining Best Practice

null 체크는 **Optional 체이닝**으로 대체.

```java
// Bad
ValueWrapper wrapper = l1.get(key);
if (wrapper != null) { return wrapper; }

// Good
return Optional.ofNullable(l1.get(key))
        .map(w -> tap(w, "L1"))
        .orElse(null);
```

**Checked Exception 구조적 분리:** Optional.orElseGet() 안에서 try-catch 금지. 예외 발생 가능한 작업은 Optional 밖에서 직접 호출.

## 5. Anti-Pattern: Error Handling

- **Catch and Ignore:** 예외를 잡고 무시 금지
- **Hardcoded Messages:** 에러 메시지는 `ErrorCode` Enum에서 관리
- **Standard Output:** `e.printStackTrace()` 금지, `@Slf4j` 사용
- **Raw Thread:** `new Thread()`, `Future` 직접 사용 금지

---

# 아키텍처 가드레일 (Architecture Guardrails)

## 6. Stateless Architecture (절대 준수)

**금지 패턴:**
- `HttpSession`, `@SessionScope`, `@SessionAttributes` 사용 금지
- `static mutable` 상태 금지

**상태 저장소:** Redis, MySQL, MongoDB만 사용

## 7. Advisory Lock & Single Flight 규칙

- **Lock Scope:** `pg_try_advisory_xact_lock`(트랜잭션 스코프)만 사용. 세션 스코프(`pg_advisory_lock`)는 HikariCP에서 위험
- **Pattern:** Leader/Follower 패턴 필수. Leader는 계산 후 L2 저장, Follower는 L2 폴링 (최대 5초)
- **AOP 주의:** 같은 클래스 내부에서 `@Transactional` 메서드 직접 호출 금지 (프록시 미작동)
- **Key 일치:** 락 키, 캐시 키, NOTIFY 페이로드는 반드시 동일한 생성 로직 사용

## 8. TieredCache 흐름

```
L1 (Caffeine) → L2 (PostgreSQL UNLOGGED) → SingleFlight → Loader
```

## 9. Virtual Thread 주의사항

`synchronized` 블록 안에서 blocking하면 carrier thread pinning 발생. `ReentrantLock` 사용.

---

# 작업 규칙 (Workflow Rules)

## 10. Definition of Done

- [ ] ADR 문서 작성 (구현 작업만)
- [ ] Unit 테스트 통과 (`./gradlew test`)
- [ ] CLAUDE.md 원칙 준수
- [ ] 통합테스트 금지 (Testcontainers 포함) - Issue #207

## 11. 검증 명령어

```bash
./gradlew compileKotlin compileJava  # 컴파일 확인
./gradlew test                        # 전체 테스트
```

## 12. Flaky Test Prevention

- `Thread.sleep()` 금지 → `Awaitility` 사용
- 테스트 간 상태 공유 금지
- `@DirtiesContext` 남용 금지

---

# 상세 문서 (참조)

| 주제 | 위치 |
|------|------|
| 인프라 (Redis, Cache, Security) | [docs/03_Technical_Guides/infrastructure.md](docs/03_Technical_Guides/infrastructure.md) |
| 비동기 & 동시성 | [docs/03_Technical_Guides/async-concurrency.md](docs/03_Technical_Guides/async-concurrency.md) |
| 테스트 가이드 | [docs/03_Technical_Guides/testing-guide.md](docs/03_Technical_Guides/testing-guide.md) |
| 멀티 에이전트 프로토콜 | [docs/00_Start_Here/multi-agent-protocol.md](docs/00_Start_Here/multi-agent-protocol.md) |
| 카오스 엔지니어링 | [docs/02_Chaos_Engineering/](docs/02_Chaos_Engineering/) |
| 서비스 모듈 | [docs/03_Technical_Guides/service-modules.md](docs/03_Technical_Guides/service-modules.md) |
| Scale-out 분석 | [docs/05_Reports/](docs/05_Reports/) |
| ADR | [docs/adr/](docs/adr/) |
