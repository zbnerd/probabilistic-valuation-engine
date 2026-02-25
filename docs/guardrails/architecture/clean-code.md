---
id: GR-ARCH-040
category: architecture
severity: critical
keywords: [lambda, method-reference, optional-chaining, tap-pattern, checked-exception, clean-code]
languages: [java, kotlin]
---

# Clean Code & Lambda Hell Guardrails

## Overview

LogicExecutor 도입으로 `try-catch`는 제거되었지만, 과도한 람다 중첩으로 인한 **"괄호 지옥(Lambda Hell)"**이 발생해서는 안 됩니다. 이 가드레일은 가독성 높은 코드 작성을 위한 규칙을 정의합니다.

---

## GR-ARCH-040: 3-Line Rule (람다 3줄 초과 금지)

### DON'T (안티패턴)

```java
// 안티패턴 1: 람다 내부 로직이 3줄 초과
return executor.execute(() -> {
    User user = repo.findById(id).orElseThrow(() -> new RuntimeException("..."));
    if (user.isActive()) {
        return otherService.process(user.getData().stream()
            .filter(d -> d.isValid())
            .map(d -> {
                // ... 복잡한 로직 ...
                return d.toDto();
            }).toList());
    }
}, context);

// 안티패턴 2: 중첩 람다 (람다 안에 람다)
return executor.execute(() -> executor.execute(() -> {
    return repository.findById(id)
        .map(item -> {
            return transformer.transform(item);
        })
        .orElse(null);
}, context), context);
```

**위험성:**
- 디버깅 어려움 (스택 트레이스가 람다로 표시)
- 가독성 최악 (중첩 depth 증가)
- 코드 재사용 불가

### DO (베스트 프랙티스)

```java
// Good: 람다 내부 로직이 3줄 이내
return executor.execute(() -> this.processActiveUser(id), context);

// Private Helper Method로 추출
private List<Dto> processActiveUser(Long id) {
    User user = findUserOrThrow(id);
    return user.isActive() ? processUserData(user) : List.of();
}

// Good: 중첩 람다 제거
public Result process(Long id) {
    return executor.execute(() -> this.findAndTransform(id), context);
}

private Result findAndTransform(Long id) {
    return repository.findById(id)
        .map(this::transform)
        .orElse(null);
}

private Result transform(Item item) {
    return transformer.transform(item);
}
```

**핵심 규칙:**
- 람다 내부 로직이 **3줄 초과** 시 → **Private Method 추출**
- 분기문(`if/else`) 포함 시 → **Private Method 추출**
- 중첩 람다 금지 (`execute(() -> execute(() → ...))`)

### 출처
- CLAUDE.md Section 15: Anti-Pattern: Lambda & Parenthesis Hell

---

## GR-ARCH-041: Method Reference 우선 사용

### DON'T (안티패턴)

```java
// 안티패턴 1: 불필요한 람다 사용
list.forEach(item -> service.process(item));
list.stream().map(item -> item.getName()).toList();
list.stream().filter(item -> item.isValid()).toList();

// 안티패턴 2: this::method 대신 람다 사용
return executor.execute(() -> this.processData(id), context);
```

**위험성:**
- 불필요한 람다 오버헤드
- 가독성 저하
- IDE 최적화 기능 미활용

### DO (베스트 프랙티스)

```java
// Good: Method Reference 사용
list.forEach(service::process);
list.stream().map(Item::getName).toList();
list.stream().filter(Item::isValid).toList();

// Good: this::method 사용
return executor.execute(this::processData, context);

// Good: 정적 메서드 참조
list.stream()
    .map(Dto::fromEntity)
    .toList();

// Good: 생성자 참조
list.stream()
    .map(ItemDto::new)
    .toList();
```

**핵심 규칙:**
- `item → service.process(item)` → `service::process`
- `item → this.process(item)` → `this::process`
- `item → Item.from(item)` → `Item::from`
- `() -> new Item()` → `Item::new`

**예외: 람다 사용이 필요한 경우**
```java
// 람다 사용이 적절한 경우 (파라미터 변형 필요)
list.stream()
    .map(item -> {
        Item transformed = transformer.apply(item);
        transformed.setTimestamp(LocalDateTime.now());
        return transformed;
    })
    .toList();
// 이 경우에도 메서드로 추출 권장
list.stream()
    .map(this::transformWithTimestamp)
    .toList();
```

### 출처
- CLAUDE.md Section 15: Anti-Pattern: Lambda & Parenthesis Hell

---

## GR-ARCH-042: Optional Chaining Best Practice

### DON'T (안티패턴)

```java
// 안티패턴 1: Imperative null check (명령형 null 체크)
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

// 안티패턴 2: 중첩 if-else 지옥
public User getUser(String id) {
    if (cache != null) {
        User user = cache.get(id);
        if (user != null) {
            return user;
        }
    }
    if (repository != null) {
        return repository.findById(id);
    }
    return null;
}
```

**위험성:**
- 가독성 최악 (중첩 depth 증가)
- NPE 위험 (null 체크 누락)
- 부수 효과(Metric 기록 등) 처리 어려움

### DO (베스트 프랙티스)

```java
// Good: Optional 체이닝으로 선언적 null 처리
return Optional.ofNullable(l1.get(key))
        .map(w -> tap(w, "L1"))
        .or(() -> Optional.ofNullable(l2.get(key))
                .map(w -> {
                    l1.put(key, w.get());
                    return tap(w, "L2");
                }))
        .orElse(null);

// Tap 패턴 (Side Effect with Return)
private ValueWrapper tap(ValueWrapper wrapper, String layer) {
    recordCacheHit(layer);  // 부수 효과 실행
    return wrapper;  // 값을 반환하여 체이닝 유지
}

// Good: 복잡한 로직은 메서드로 분리
public User getUser(String id) {
    return getCachedValue(id)
        .orElseGet(() -> loadFromDatabase(id));
}

private Optional<User> getCachedValue(String id) {
    return Optional.ofNullable(cache)
        .map(c -> c.get(id));
}

private User loadFromDatabase(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new UserNotFoundException(id));
}
```

**핵심 규칙:**
- **Optional 체이닝**: 선언적이고 가독성 높은 null 처리
- **Tap 패턴**: 값을 반환하면서 부수 효과(메트릭 기록) 실행
- **메서드 분리**: 복잡한 체이닝은 Private Method로 추출

### 출처
- CLAUDE.md Section 4: Optional Chaining Best Practice (Modern Null Handling)

---

## GR-ARCH-043: Checked Exception 구조적 분리

### DON'T (안티패턴)

```java
// 안티패턴 1: Optional 내에서 try-catch로 감싸기
private <T> T getWithFallback(Object key) {
    return Optional.ofNullable(l1.get(key))
        .or(() -> {
            try {
                // ❌ try-catch로 checked exception 감싸서 RuntimeException 변환
                return Optional.ofNullable(loadFromDatabase(key));
            } catch (IOException e) {
                throw new RuntimeException(e);  // 예외 타입 손실
            }
        })
        .orElse(null);
}

// 안티패턴 2: RuntimeException으로 변환
.orElseGet(() -> {
    try {
        return loadFromDatabase(key);
    } catch (Exception e) {
        throw new RuntimeException(e);  // 섹션 11, 12 위반
    }
})
```

**위험성:**
- 예외 타입 정보 손실
- LogicExecutor의 예외 처리 우회
- 섹션 11, 12 위반 (CLAUDE.md)

### DO (베스트 프랙티스)

```java
// Good: 구조적 분리로 checked exception 자연 전파
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

// LogicExecutor와 함께 사용 (예외 변환)
return executor.executeWithTranslation(
    () -> loadFromDatabase(key),
    IOException.class,
    e -> new ServerBaseException("Database load failed: " + key, e),
    TaskContext.of("Cache", "LoadWithFallback", key)
);
```

**핵심 규칙:**
- **Optional 체이닝**: 예외 없는 작업만 (캐시 조회, 필터링)
- **checked exception**: Optional 밖에서 직접 호출
- **예외 변환**: LogicExecutor.executeWithTranslation() 사용
- **구조적 분리**: 예외 발생 가능/불가능 영역 명확히 분리

### 출처
- CLAUDE.md Section 4: Optional Chaining Best Practice - Checked Exception 구조적 분리
- CLAUDE.md Section 11: Exception Handling Strategy
- CLAUDE.md Section 12: Zero Try-Catch Policy & LogicExecutor

---

## GR-ARCH-044: 람다 중첩 깊이 제한

### DON'T (안티패턴)

```java
// 안티패턴: 3단계 이상 중첩
public Result process(Long id) {
    return executor.execute(() -> {
        return repository.findById(id).map(entity -> {
            return transformer.transform(entity).map(dto -> {
                return validator.validate(dto).map(result -> {
                    // 4단계 중첩
                    return notifier.notify(result);
                }).orElse(null);
            }).orElse(null);
        }).orElse(null);
    }, context);
}

// 안티패턴: Executor 중첩
executor.execute(() -> {
    executor.execute(() -> {
        executor.execute(() -> {
            // 3단계 Executor 중첩
            return process();
        }, context);
    }, context);
}, context);
```

**위험성:**
- 가독성 파괴
- 스레드 풀 경합 증가
- 디버깅 불가

### DO (베스트 프랙티스)

```java
// Good: Flat한 호출 구조
public Result process(Long id) {
    return executor.execute(() -> this.processEntity(id), context);
}

private Result processEntity(Long id) {
    Entity entity = repository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException(id));
    return transformAndValidate(entity);
}

private Result transformAndValidate(Entity entity) {
    Dto dto = transformer.transform(entity);
    return validateAndNotify(dto);
}

private Result validateAndNotify(Dto dto) {
    Result result = validator.validate(dto);
    return notifier.notify(result);
}
```

**핵심 규칙:**
- 중첩 깊이 최대 **2단계**
- Executor 중첩 금지
- 각 단계를 Private Method로 분리

### 출처
- CLAUDE.md Section 15: Anti-Pattern: Lambda & Parenthesis Hell

---

## GR-ARCH-045: 명명된 매개변수 vs 람다

### DON'T (안티패턴)

```java
// 안티패턴: 복잡한 람다 대신 명명된 변수 사용
return executor.execute(() -> {
    String ocid = characterId;
    String worldName = getWorldName(ocid);
    String className = getClassName(ocid);
    LocalDateTime now = LocalDateTime.now();
    return repository.save(new GameCharacter(ocid, worldName, className, now));
}, context);
```

**위험성:**
- 람다 내부 로직이 길어짐
- 변수 스코프가 불명확

### DO (베스트 프랙티스)

```java
// Good: 명명된 메서드로 분리
return executor.execute(() -> this.createAndSaveCharacter(characterId), context);

private GameCharacter createAndSaveCharacter(String characterId) {
    String ocid = characterId;
    String worldName = getWorldName(ocid);
    String className = getClassName(ocid);
    LocalDateTime now = LocalDateTime.now();
    return repository.save(new GameCharacter(ocid, worldName, className, now));
}

// 더 좋은 방법: 빌더 패턴 사용
private GameCharacter createAndSaveCharacter(String characterId) {
    return GameCharacter.builder()
        .characterId(characterId)
        .worldName(getWorldName(characterId))
        .className(getClassName(characterId))
        .build();
}
```

**핵심 규칙:**
- 람다 내부에서 **변수 선언 + 복잡한 로직** → 메서드 분리
- 빌더 패턴 활용

### 출처
- CLAUDE.md Section 15: Anti-Pattern: Lambda & Parenthesis Hell

---

## GR-ARCH-046: LogicExecutor와 람다 조합

### DON'T (안티패턴)

```java
// 안티패턴 1: Executor 내에서 또 다른 Executor 호출 (순환 참조 위험)
public Result process(Long id) {
    return executor.execute(() -> {
        // ❌ executor 내에서 executor 호출
        return executor.execute(() -> repository.findById(id), context);
    }, context);
}

// 안티패턴 2: 불필요한 람다로 감싸기
public Result process(Long id) {
    // executor.execute에 이미 함수형 인터페이스를 받는데 람다로 한 번 더 감쌈
    return executor.execute(() -> this.findId(id), context);
}

private Long findId(Long id) {
    return id;
}
```

### DO (베스트 프랙티스)

```java
// Good: Method Reference 직접 사용
public Result process(Long id) {
    return executor.execute(() -> this.findAndProcess(id), context);
}

// Good: 여러 단계의 작업은 하나의 메서드로 통합
private Result findAndProcess(Long id) {
    Entity entity = repository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException(id));
    return processEntity(entity);
}

// Good: 복잡한 작업 흐름은 메서드로 분리
public Result processComplex(Long id) {
    return executor.execute(
        () -> this.executeComplexWorkflow(id),
        TaskContext.of("Workflow", "ProcessComplex", id)
    );
}

private Result executeComplexWorkflow(Long id) {
    // Step 1
    Entity entity = findEntity(id);
    // Step 2
    Dto dto = transformEntity(entity);
    // Step 3
    Result result = validateDto(dto);
    // Step 4
    return notifyResult(result);
}
```

**핵심 규칙:**
- **Executor 중첩 금지**: Executor 내에서 Executor 호출 금지
- **Method Reference 우선**: `this::method` 형태 우선 사용
- **작업 통합**: 여러 단계는 하나의 메서드로 통합

### 출처
- CLAUDE.md Section 12: Zero Try-Catch Policy & LogicExecutor
- CLAUDE.md Section 15: Anti-Pattern: Lambda & Parenthesis Hell

---

## GR-ARCH-047: Stream API 활용 가이드

### DON'T (안티패턴)

```java
// 안티패턴 1: 불필요한 for-each와 side effect
List<Dto> result = new ArrayList<>();
entities.forEach(e -> {
    // side effect로 결과 추가 (함수형 패러다임 위반)
    result.add(transformer.transform(e));
});

// 안티패턴 2: 중간 결과를 변수에 저장
Stream<Entity> stream = entities.stream()
    .filter(e -> e.isValid());
List<Entity> filtered = stream.toList();  // 불필요한 중간 변수
List<Dto> result = filtered.stream()
    .map(this::transform)
    .toList();

// 안티패턴 3: 복잡한 람다 내부 로직
entities.stream()
    .map(e -> {
        // 복잡한 로직이 람다 내부에
        if (e.getType() == Type.A) {
            return processTypeA(e);
        } else if (e.getType() == Type.B) {
            return processTypeB(e);
        } else {
            return processDefault(e);
        }
    })
    .toList();
```

### DO (베스트 프랙티스)

```java
// Good: Stream 체이닝으로 함수형 패러다임 준수
List<Dto> result = entities.stream()
    .filter(Entity::isValid)
    .map(this::transform)
    .toList();

// Good: 복잡한 로직은 메서드로 분리 후 메서드 참조
entities.stream()
    .map(this::processByType)  // 메서드 참조
    .toList();

private Dto processByType(Entity entity) {
    // switch expression으로 깔끔하게 처리
    return switch (entity.getType()) {
        case A -> processTypeA(entity);
        case B -> processTypeB(entity);
        default -> processDefault(entity);
    };
}

// Good: collector 활용
Map<Type, List<Dto>> grouped = entities.stream()
    .collect(Collectors.groupingBy(
        Entity::getType,
        Collectors.mapping(
            this::transform,
            Collectors.toList()
        )
    );
```

**핵심 규칙:**
- **Side effect 금지**: forEach로 외부 상태 변경 금지
- **체이닝**: 중간 결과를 변수에 저장하지 않고 체이닝
- **메서드 참조**: 복잡한 람다는 메서드 분리 후 참조
- **Collector**: groupingBy, partitioningBy 등 활용

### 출처
- CLAUDE.md Section 6: Design Patterns & Structure

---

## Verification Commands

### Lambda Hell 검증

```bash
# 람다 내부 라인 수 확인 (3줄 초과 시 경고)
# 이 검증은 수동 코드리뷰가 필요합니다.
# IDE 플러그인 또는 SonarQube 규칙 활용 권장

# 중첩 람다 패턴 확인
grep -r "execute.*->.*execute" src/main/java/

# Method Reference 미사용 패턴 확인
grep -r "-> service\." src/main/java/ | grep -v "::"
grep -r "-> this\." src/main/java/ | grep -v "::"

# forEach 내부 side effect 확인
grep -r "forEach.*{" src/main/java/ | grep -A 5 "\.add("
```

### Clean Code 검증

```bash
# 메서드 길이 확인 (20라인 초과 시 경고)
find src/main/java -name "*.java" -exec wc -l {} \; | awk '$1 > 20 { print $0 }'

# 중첩 깊이 확인 (2단계 초과 시 경고)
# IDE 플러그인 또는 SonarQube 규칙 활용 권장

# Optional 체이닝 미사용 확인
grep -r "if.*!= null" src/main/java/
```

---

## Evidence Links

- CLAUDE.md Section 4: Optional Chaining Best Practice
- CLAUDE.md Section 11: Exception Handling Strategy
- CLAUDE.md Section 12: Zero Try-Catch Policy & LogicExecutor
- CLAUDE.md Section 15: Anti-Pattern: Lambda & Parenthesis Hell
- CLAUDE.md Section 16: Proactive Refactoring & Quality
