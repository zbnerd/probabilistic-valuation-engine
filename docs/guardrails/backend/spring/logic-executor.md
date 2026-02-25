---
id: GR-001
category: backend/spring
severity: critical
keywords: [try, catch, try-catch, exception handling, LogicExecutor, execute, executeOrDefault, executeWithRecovery]
---

# LogicExecutor 사용 (Zero Try-Catch Policy)

## 개요

**비즈니스 로직, 인프라 모듈, 글로벌 모듈 전체**에서 `try-catch` 및 `try-finally` 블록 사용을 **엄격히 금지**합니다. 모든 실행 흐름과 예외 처리는 **`LogicExecutor`** 템플릿에 위임하여 일관성 있는 예외 처리와 로깅을 보장합니다.

> **적용 범위:** `service/`, `scheduler/`, `config/`, `global/`, `aop/` 등 **모든 패키지**
>
> **설계 근거:** 예외 처리 로직이 중복되면 일관성 없는 에러 핸들링, 로깅 누락, 디버깅 어려움이 발생합니다. LogicExecutor는 이를 중앙화하여 구조화된 로그와 일관된 예외 처리를 제공합니다.

## 핵심 원칙

### 1. 모든 예외 처리는 LogicExecutor에 위임

직접 `try-catch`를 작성하는 대신 LogicExecutor의 6가지 패턴을 사용합니다.

### 2. 구조화된 로그 제공

TaskContext를 통해 도메인, 작업, 식별자가 포함된 구조화된 로그를 자동 기록합니다.

### 3. 예외 변환 지원

Checked exception을 도메인 예외로 변환하는 `executeWithTranslation` 패턴을 제공합니다.

## DON'T (안티패턴)

### 1. 직접 try-catch 사용

```java
// Bad - 직접 try-catch 사용
public String badMethod() {
    try {
        return repository.findById(1L);
    } catch (Exception e) {
        log.error("Error", e);
        return null;
    }
}
```

**위험성:**
- 예외 처리 로직이 중복 코드 발생
- 일관성 없는 에러 핸들링
- 로깅 누락 가능성
- 디버깅 어려움
- TaskContext를 통한 구조화된 로그 누락

### 2. try-finally 직접 사용

```java
// Bad - 직접 try-finally 사용
public void badMethod() {
    RLock lock = redissonClient.getLock("key");
    boolean acquired = false;
    try {
        acquired = lock.tryLock();
        // 작업 수행
    } finally {
        if (acquired && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**위험성:**
- unlock() 예외로 unlock() 호출 누락 위험
- 구조화된 로그 누락
- 예외 처리 로직 중복

### 3. 예외 변환 시 RuntimeException 사용

```java
// Bad - RuntimeException으로 변환
public Object badMethod() {
    try {
        return externalApi.call();
    } catch (IOException e) {
        throw new RuntimeException(e);  // 예외 변환 안티패턴
    }
}
```

**위험성:**
- 비즈니스 맥락 없는 모호한 예외
- Circuit Breaker 오작동 유발
- 스택 트레이스 끊김

### 4. 람다 괄호 지옥 (Lambda Hell)

```java
// Bad - 과도한 람다 중첩
return executor.execute(() -> {
    User user = repo.findById(id).orElseThrow(() -> new RuntimeException("..."));
    if (user.isActive()) {
        return otherService.process(user.getData().stream()
            .filter(d -> d.isValid())
            .map(d -> {
                // ... complex logic ...
                return d.toDto();
            }).toList());
    }
}, context);
```

**위험성:**
- 가독성 최악 ("괄호 지옥")
- 디버깅 어려움
- 람다 내부 로직이 3줄 초과

## DO (베스트 프랙티스)

### 1. LogicExecutor 6가지 패턴

| 패턴 | 메서드 | 용도 | 예시 |
| :--- | :--- | :--- | :--- |
| **패턴 1** | `execute(task, context)` | 일반적인 실행. 예외 발생 시 로그 기록 후 상위 전파. | `executor.execute(() -> service.process(id), context)` |
| **패턴 2** | `executeVoid(task, context)` | 반환값이 없는 작업(Runnable) 실행. | `executor.executeVoid(() -> service.delete(id), context)` |
| **패턴 3** | `executeOrDefault(task, default, context)` | 예외 발생 시 안전하게 기본값 반환 (조회 로직 등). | `executor.executeOrDefault(() -> repo.findById(id), null, context)` |
| **패턴 4** | `executeWithRecovery(task, recovery, context)` | 예외 발생 시 특정 복구 로직(람다) 실행. | `executor.executeWithRecovery(task, e -> fallback(), context)` |
| **패턴 5** | `executeWithFinally(task, finalizer, context)` | 자원 해제 등 `finally` 블록이 반드시 필요한 경우 사용. | `executor.executeWithFinally(task, () -> cleanup(), context)` |
| **패턴 6** | `executeWithTranslation(task, translator, context)` | 기술적 예외(IOException 등)를 도메인 예외로 변환. | `executor.executeWithTranslation(task, ExceptionTranslator.forNexonApi(), context)` |

### 2. 패턴 1: execute (일반 실행)

```java
// Good - LogicExecutor 사용
@Service
@RequiredArgsConstructor
public class GoodService {
    private final LogicExecutor executor;
    private final Repository repository;

    public String goodMethod(Long id) {
        return executor.execute(
            () -> repository.findById(id),
            TaskContext.of("GoodService", "FindById", id)
        );
    }
}
```

**동작:**
- 성공 시: 결과 반환
- 실패 시: 로그 기록 후 예외 상위 전파

### 3. 패턴 3: executeOrDefault (기본값 반환)

```java
// Good - 조회 로직에 기본값 반환
public Optional<User> findById(Long id) {
    return executor.executeOrDefault(
        () -> repository.findById(id),
        Optional.empty(),
        TaskContext.of("UserService", "FindById", id)
    );
}
```

**동작:**
- 성공 시: 결과 반환
- 실패 시: 기본값 반환 (Optional.empty())
- 로그 레벨: WARN (예상 가능한 실패)

### 4. 패턴 5: executeWithFinally (자원 해제)

```java
// Good - 분산 락 자원 해제
public <T> T executeWithLock(String key, Supplier<T> task) {
    RLock lock = redissonClient.getLock(key);
    return executor.executeWithFinally(
        () -> {
            boolean acquired = lock.tryLock(30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ConcurrentModificationException("Lock acquisition failed");
            }
            return task.get();
        },
        () -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        },
        TaskContext.of("Lock", "Execute", key)
    );
}
```

**동작:**
- 성공/실패 무관하게 finalizer 실행
- finally 블록의 예외는 로그 기록 후 무시

### 5. 패턴 6: executeWithTranslation (예외 변환)

```java
// Good - Checked exception을 도메인 예외로 변환
public NexonApiCharacterResponse fetchCharacter(String ign) {
    return executor.executeWithTranslation(
        () -> nexonApiClient.getCharacter(ign),
        ExceptionTranslator.forNexonApi(),
        TaskContext.of("NexonApi", "GetCharacter", ign)
    );
}

// ExceptionTransformer 내부
public static ExceptionTransformer<NexonApiCharacterResponse> forNexonApi() {
    return cause -> {
        if (cause instanceof IOException io) {
            return new NexonApiTimeoutException(
                ErrorCode.API_TIMEOUT,
                io,
                ign
            );
        }
        return new InternalServerException(ErrorCode.INTERNAL_ERROR, cause);
    };
}
```

**동작:**
- IOException 발생 시: NexonApiTimeoutException(ServerBaseException)으로 변환
- 기타 예외: InternalServerException으로 변환
- Circuit Breaker: ServerBaseException은 CircuitBreakerRecordMarker 구현

### 6. 람다 3줄 규칙 (Rule of Thumb)

```java
// Bad (3줄 초과, 분기문 포함)
return executor.execute(() -> {
    User user = repo.findById(id);
    if (user.isActive()) {
        log.info("Active user: {}", user.getId());
        return user.toDto();
    }
    return null;
}, context);

// Good (메서드 추출: 선언적이고 깔끔함)
return executor.execute(() -> this.processActiveUser(id), context);

// Private Helper Method
private UserDto processActiveUser(Long id) {
    User user = repo.findById(id);
    if (!user.isActive()) {
        return null;
    }
    log.info("Active user: {}", user.getId());
    return user.toDto();
}
```

**핵심:**
- 람다 내부 로직이 **3줄**을 초과하거나 분기문(`if/else`)이 포함되면 즉시 **Private Method**로 추출
- Method Reference 우선: `this::processActiveUser`

### 7. Method Reference 우선

```java
// Good (Method Reference)
return executor.execute(this::processActiveUser, context);

// Good (Method Reference 체이닝)
users.stream()
    .filter(User::isActive)
    .map(this::toDto)
    .toList();
```

## 허용 예외 (LogicExecutor 순환참조/구조적 제약)

다음 경우에는 직접 try-catch 사용이 허용됩니다:

| 컴포넌트 | 사유 | 예시 |
|--------|------|------|
| `TraceAspect` | AOP에서 LogicExecutor 호출 시 순환참조 발생 | try-catch 사용 허용 |
| `DefaultLogicExecutor`, `DefaultCheckedLogicExecutor` | LogicExecutor 구현체 내부 | 직접 예외 처리 필요 |
| `ExecutionPipeline` | LogicExecutor 실행 파이프라인 내부 | 순환 참조 방지 |
| `TaskDecorator` (ExecutorConfig) | Runnable 래핑 구조로 LogicExecutor 적용 불가 | MDC/ThreadLocal 전파 |
| JPA Entity (`DonationOutbox` 등) | Spring Bean 주입 불가 | Section 11 규칙에 따라 직접 예외 변환 |

## 코드 예시

### 전체 예시: Facade + LogicExecutor

```java
@Facade
@RequiredArgsConstructor
public class GameCharacterFacade {
    private final GameCharacterService gameCharacterService;
    private final LogicExecutor executor;
    private final DistributedLockStrategy lockStrategy;

    public CharacterDto process(String ign) {
        return executor.execute(
            () -> lockStrategy.executeWithLock(
                "character:" + ign,
                () -> gameCharacterService.calculate(ign)
            ),
            TaskContext.of("GameCharacterFacade", "Process", ign)
        );
    }
}

@Service
@RequiredArgsConstructor
public class GameCharacterService {
    private final LogicExecutor executor;
    private final CharacterRepository repository;
    private final NexonApiClient nexonApiClient;

    public CharacterDto calculate(String ign) {
        return executor.executeWithTranslation(
            () -> {
                Character character = repository.findById(ign)
                    .orElseGet(() -> fetchFromNexonApi(ign));
                return calculateCost(character);
            },
            ExceptionTranslator.forCharacterCalculation(),
            TaskContext.of("GameCharacterService", "Calculate", ign)
        );
    }

    private Character fetchFromNexonApi(String ign) {
        return executor.executeWithTranslation(
            () -> nexonApiClient.getCharacter(ign),
            ExceptionTranslator.forNexonApi(),
            TaskContext.of("GameCharacterService", "FetchFromNexon", ign)
        );
    }
}
```

## 관련 문서 링크

### 상위 문서
- [CLAUDE.md](../../../../CLAUDE.md) Section 12: Zero Try-Catch Policy & LogicExecutor (lines 303-343)
- [CLAUDE.md](../../../../CLAUDE.md) Section 15: Anti-Pattern: Lambda & Parenthesis Hell (lines 383-414)

### 기술 가이드
- [infrastructure.md](../../../../03_Technical_Guides/infrastructure.md) Section 7: AOP & Facade Pattern (lines 32-43)
- [infrastructure.md](../../../../03_Technical_Guides/infrastructure.md) Section 8-1: Redis Lua Script & Cluster Hash Tag (lines 58-287)
- [logic_executor_policy_pipeline.md](../../../../03_Technical_Guides/logic_executor_policy_pipeline.md) - LogicExecutor 파이프라인 상세

### 관련 ADR
- [ADR-044: LogicExecutor Zero Try-Catch](../../../../01_ADR/ADR-044-logicexecutor-zero-try-catch.md)

### 관련 Guardrails
- [exception-handling.md](./exception-handling.md) - 예외 처리 전략 (ClientBaseException/ServerBaseException)
- [aop-facade.md](./aop-facade.md) - AOP & Facade Pattern
- [optional-chaining.md](./optional-chaining.md) - Optional Chaining & Tap Pattern
- [circuit-breaker.md](../resilience/circuit-breaker.md) - Circuit Breaker Pattern
- [marker-interface.md](../resilience/marker-interface.md) - Marker Interface Pattern

## 검증 명령어

```bash
# LogicExecutor 사용 확인
grep -r "try {" src/main/java --include="*.java" | grep -v "DefaultLogicExecutor\|TraceAspect\|ExecutionPipeline"

# 람다 3줄 초과 확인
grep -r "executor.execute" src/main/java --include="*.java" -A 5

# RuntimeException 사용 확인 (금지)
grep -r "new RuntimeException" src/main/java --include="*.java"

# TaskContext 사용 확인
grep -r "TaskContext.of" src/main/java --include="*.java"
```
