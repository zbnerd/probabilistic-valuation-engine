# 11장: 코드의 철학 — Zero Try-Catch와 LogicExecutor

> ADR-044: LogicExecutor Zero Try-Catch Policy
>
> "try-catch를 쓰지 마라. 대신, 생각한 후에 써라."

---

## 철학의 탄생

1장에서 다룬 도미노 장애의 원인 중 하나는 **try-catch 남용**이었다.

```java
// 1장 시절의 코드
public Data getData(String id) {
    try {
        return repository.findById(id);
    } catch (Exception e) {
        return null;  // 예외를 삼킨다. 아무 로그도 없다.
    }
}
```

이 패턴이 코드베이스 전체에 퍼져 있었다. 예외는 잡히지만 삼켜진다. 호출자는 실패를 알 수 없다. 로그에도 남지 않는다. 장애가 발생해도 원인을 추적할 수 없다.

"이건 고쳐야 해." — 단순한 다짐이 아니었다. 이것은 **코드의 철학**이었다.

---

## 원칙: 모든 try-catch를 금지한다

ADR-044는 명확했다:

> **모든 패키지에서 try-catch 및 try-finally 블록 사용 금지.**
> **모든 실행 흐름과 예외 처리는 LogicExecutor에 위임.**

왜? try-catch를 직접 쓰면 이렇게 되기 때문이다:

1. **일관성 없는 에러 처리.** 개발자마다 다르게 처리한다. A는 로그를 남기고, B는 무시하고, C는 상위에 전파한다.
2. **에러 삼키기.** catch 블록이 비어 있거나 `return null`만 있는 경우.
3. **컨텍스트 손실.** 어디서, 왜, 무슨 작업 중에 에러가 났는지 모른다.
4. **중복 코드.** 모든 서비스 메서드가 같은 try-catch 패턴을 반복한다.

---

## LogicExecutor: 7가지 실행 패턴

LogicExecutor는 try-catch를 7가지 명명된 패턴으로 대체한다. 각 패턴은 명확한 의도를 가진다.

### 패턴 1: execute — 기본 실행

```kotlin
// 예외 발생 시 로그 기록 후 상위 전파
return executor.execute(
    { repository.findById(id) },
    TaskContext.of("UserService", "findById", id)
)
```

실패하면 로그에 컨텍스트가 남는다: "[UserService] findById 실패 (key=id)". 그리고 예외는 상위로 전파된다. 삼키지 않는다.

### 패턴 2: executeVoid — 반환값 없는 작업

```kotlin
executor.executeVoid(
    { eventPublisher.publish(event) },
    TaskContext.of("EventService", "publish", event.id)
)
```

### 패턴 3: executeOrDefault — 기본값 반환

```kotlin
// 예외 발생 시 기본값 반환
val data = executor.executeOrDefault(
    { expensiveCalculation(input) },
    Data.EMPTY,
    TaskContext.of("CalcService", "calculate", input)
)
```

실패하면 `Data.EMPTY`를 반환한다. null이 아닌 명시적인 기본값.

### 패턴 4: executeOrCatch — 복구 로직

```kotlin
// 예외 발생 시 번역된 예외로 복구
val result = executor.executeOrCatch(
    { externalApi.call(request) },
    { ex -> handleApiError(ex) },
    TaskContext.of("ExternalApi", "call", request.id)
)
```

실패하면 복구 로직이 실행된다. 번역된 예외(도메인 예외)가 전달된다.

### 패턴 5: executeWithFallback — 폴백 실행

```kotlin
// 예외 발생 시 원본 예외로 폴백
val data = executor.executeWithFallback(
    { primaryDataSource.load(key) },
    { ex -> fallbackDataSource.load(key) },
    TaskContext.of("DataService", "load", key)
)
```

1차 소스가 실패하면 2차 소스에서 가져온다. 원본 예외가 전달되므로 폴백 로직에서 원인을 알 수 있다.

### 패턴 6: executeWithFinally — 자원 해제

```kotlin
// finally 블록 명시적 지정
val result = executor.executeWithFinally(
    { processResource(resource) },
    { resource.close() },
    TaskContext.of("ResourceService", "process", resource.id)
)
```

### 패턴 7: executeWithTranslation — 예외 변환

```kotlin
// 기술적 예외를 도메인 예외로 변환
val data = executor.executeWithTranslation(
    { webClient.call(url) },
    { ex -> ExternalServiceException("NexonAPI", ex) },
    TaskContext.of("NexonApi", "call", ocid)
)
```

WebClient의 기술적 예외를 도메인 의미 있는 예외로 변환한다.

---

## TaskContext: 장애의 맥락

모든 패턴의 공통점은 `TaskContext`를 받는다는 것이다.

```kotlin
data class TaskContext(
    val module: String,    // "UserService"
    val task: String,      // "findById"
    val key: String,       // "abc123"
)
```

에러가 발생하면:

```
[ERROR] [UserService] findById 실패
  key: abc123
  exception: NoSuchElementException
  duration: 234ms
  thread: virtual-thread-123
```

무엇이, 어디서, 왜, 얼마나 오래 걸렸는지. 모든 맥락이 로그에 남는다.

---

## Lambda Hell 금지

LogicExecutor를 쓰다 보면 람다 안에 너무 많은 로직을 넣게 된다. 이것도 규칙으로 막았다.

```kotlin
// Anti-Pattern: Lambda Hell (3줄 초과)
return executor.execute({
    val user = repository.findById(id).orElseThrow()
    if (user.isActive()) {
        return@execute processActiveUser(user)
    }
    return@execute processInactiveUser(user)
}, context)

// Good: Private Method 추출
return executor.execute(
    { processUser(id) },
    context
)

private fun processUser(id: String): Data {
    val user = repository.findById(id).orElseThrow()
    return if (user.isActive()) processActiveUser(user) else processInactiveUser(user)
}
```

람다 안의 로직이 **3줄을 초과**하거나 **분기문이 포함**되면 즉시 Private Method로 추출.

---

## 검증: try-catch 탐지기

이 정책을 강제하기 위해 검증 스크립트를 만들었다.

```bash
# 프로젝트 내 try-catch 사용 탐지
# LogicExecutor 구현체 내부는 예외
grep -rn "try\s*{" --include="*.kt" --include="*.java" \
  | grep -v "LogicExecutor" \
  | grep -v "test/"
```

0건이어야 한다. 0건이 아니면 CI가 실패한다.

---

## 교훈

**1. 일관성은 규율에서 나온다.**

try-catch를 금지하는 것은 자유를 제한하는 것이 아니다. 모든 에러 처리를 일관되게 만드는 것이다.

**2. 맥락이 없는 에러 로그는 쓰레기다.**

"NullPointerException"만 있는 로그는 쓸모가 없다. 어디서, 무엇을, 왜 했는지가 있어야 한다.

**3. 명명된 패턴은 의도를 명확히 한다.**

`executeOrDefault`를 보면 "기본값을 반환하겠다"는 의도가 즉시 보인다. `try-catch-return-null`은 의도가 불분명하다.

**4. Lambda Hell은 가독성의 적이다.**

람다 안에 로직이 많으면 읽기 어렵고 테스트하기 어렵다. Private Method로 추출하면 테스트도 쉽고 읽기도 쉽다.

---

> **다음 장:** [12장: 완성 — 7,347 RPS, 실패가 만든 성과](12_7347_rps.md)
