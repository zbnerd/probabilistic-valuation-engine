---
id: GR-004
category: backend/spring
severity: warning
keywords: [Optional, Null, Tap Pattern, Checked Exception, Method Reference]
---

# Optional Chaining & Modern Null Handling

## DON'T (안티패턴)

### 1. 명령형 null 체크 금지
if문으로 null을 체크하는 명령형 스타일을 금지합니다.

```java
// Bad (Imperative null check)
ValueWrapper wrapper = l1.get(key);
if (wrapper != null) {
    recordHit("L1");
    return wrapper;
}
wrapper = l2.get(key);
if (wrapper != null) {
    l1.put(key, wrapper.get());
    return wrapper;
}
return null;
```

### 2. Optional 내부에서 try-catch 금지
Optional.orElseGet() 내부에서 checked exception을 던지는 코드를 try-catch로 감싸는 것을 금지합니다.

```java
// Bad (섹션 11, 12 위반)
return Optional.ofNullable(l1.get(key))
    .or(() -> {
        try {
            return Optional.ofNullable(loadFromDatabase(key));  // checked exception
        } catch (Exception e) {
            throw new RuntimeException(e);  // 예외 변환 안티패턴
        }
    })
    .orElse(null);
```

### 3. 람다 3줄 초과 금지
람다 내부 로직이 3줄을 초과하거나 분기문(if/else)이 포함된다면 즉시 Private Method로 추출합니다.

```java
// Bad (Lambda Hell: 3줄 초과, 분기문 포함)
return Optional.ofNullable(user)
    .map(u -> {
        if (u.isActive()) {
            log.info("Active user: {}", u.getId());
            return u.toDto();
        } else {
            return null;
        }
    })
    .orElse(null);
```

### 4. 과도한 람다 중첩 금지
`executor.execute(() -> executor.execute(() -> ...))` 형태의 중첩 실행을 금지합니다.

```java
// Bad (중첩 람다: 가독성 최악)
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

### 5. Method Reference 미사용 금지
단순 메서드 호출에 람다 대신 메서드 참조를 사용하지 않는 것을 금지합니다.

```java
// Bad (불필요한 람다)
.stream()
.map(u -> transformer.transform(u))
.filter(d -> d.isValid())

// Bad (람다로 메서드 참조 가능한 경우)
Optional.ofNullable(data)
    .map(d -> this.process(d))
```

### 6. 왜 위험한가?
- **가독성 저하**: 중첩된 람다는 "괄호 지옥"으로 디버깅 어려움
- **옘외 파악 어려움**: try-catch로 예외 변환 시 스택 트레이스 끊김
- **코드 중복**: 비슷한 null 체크 로직이 반복됨
- **부수 효과 누락**: 캐시 적중 등 메트릭 기록이 누락되기 쉬움

## DO (베스트 프랙티스)

### 1. Optional 체이닝 사용
null 체크 로직은 **Optional 체이닝**으로 대체하여 선언적이고 가독성 높은 코드를 작성합니다.

```java
// Good (Declarative Optional chaining)
return Optional.ofNullable(l1.get(key))
        .map(w -> tap(w, "L1"))
        .or(() -> Optional.ofNullable(l2.get(key))
                .map(w -> { l1.put(key, w.get()); return tap(w, "L2"); }))
        .orElse(null);
```

### 2. Tap 패턴 (Side Effect with Return)
값을 반환하면서 부수 효과(메트릭 기록 등)를 실행합니다.

```java
// Good (Tap 패턴)
private ValueWrapper tap(ValueWrapper wrapper, String layer) {
    recordCacheHit(layer);
    return wrapper;
}

// 사용
return Optional.ofNullable(l1.get(key))
    .map(w -> tap(w, "L1"))  // 메트릭 기록 후 반환
    .orElse(null);
```

### 3. Checked Exception 구조적 분리
Optional 체이닝과 checked exception을 구조적으로 분리합니다.

```java
// Good (구조적 분리)
private <T> T getWithFallback(Object key, Callable<T> loader) throws Exception {
    // 1. Optional은 예외 없는 캐시 조회에만 사용
    T cached = getCachedValue(key);
    if (cached != null) {
        return cached;
    }

    // 2. 예외 발생 가능한 작업은 Optional 밖에서 직접 호출
    return loader.call();  // checked exception 자연 전파
}

private <T> T getCachedValue(Object key) {
    return Optional.ofNullable(l1.get(key))
            .map(w -> tapAndCast(w, "L1"))
            .orElse(null);  // 예외 없음, null 반환
}
```

### 4. 핵심 원칙
- **Optional 체이닝**: 예외 없는 작업만 (캐시 조회, 필터링)
- **checked exception**: Optional 밖에서 직접 호출
- **예외 변환**: LogicExecutor.executeWithTranslation() 사용

### 5. 람다 3줄 규칙 (Rule of Thumb)
람다 내부 로직이 **3줄**을 초과하거나 분기문(`if/else`)이 포함된다면, 즉시 **Private Method**로 추출합니다.

```java
// Bad (3줄 초과)
Optional.ofNullable(user)
    .map(u -> {
        if (u.isActive()) {
            log.info("Active: {}", u.getId());
            return u.toDto();
        }
        return null;
    })

// Good (메서드 추출: 선언적이고 깔끔함)
return Optional.ofNullable(user)
    .map(this::mapActiveUserToDto)
    .orElse(null);

// Private Helper Method
private UserDto mapActiveUserToDto(User user) {
    if (!user.isActive()) {
        return null;
    }
    log.info("Active user: {}", user.getId());
    return user.toDto();
}
```

### 6. Method Reference 우선
`() -> service.process(param)` 대신 `service::process` 또는 `this::process` 형태의 메서드 참조를 최우선으로 사용합니다.

```java
// Good (Method Reference)
return executor.execute(this::processActiveUser, context);

// Good (Method Reference 체이닝)
users.stream()
    .filter(User::isActive)
    .map(this::toDto)
    .toList();
```

### 7. Flattening (중첩 제거)
각 단계를 메서드로 분리하여 수직적 깊이를 줄입니다.

```java
// Good (Method Extraction: 선언적이고 깔끔함)
return executor.execute(() -> this.processActiveUser(id), context);

// Private Helper Method
private List<Dto> processActiveUser(Long id) {
    User user = findUserOrThrow(id);
    return user.isActive() ? processUserData(user) : List.of();
}
```

### 8. 기대 효과
- **선언적 코드**: 의도가 명확하게 드러나는 코드
- **부수 효과 명확**: Tap 패턴으로 메트릭 기록 등 부수 효과가 명시적
- **예외 처리 자연스러움**: Checked exception이 자연스럽게 전파됨
- **가독성 향상**: 3줄 규칙과 메서드 추출으로 "괄호 지옥" 방지

## 출처
- CLAUDE.md Section 4: Implementation Logic & SOLID - Optional Chaining Best Practice
- CLAUDE.md Section 15: Anti-Pattern: Lambda & Parenthesis Hell
