---
id: GR-003
category: backend/spring
severity: warning
keywords: [SOLID, SRP, OCP, DIP, Design Patterns, Clean Architecture]
---

# SOLID Principles & Design Patterns

## DON'T (안티패턴)

### 1. 단일 책임 원칙(SRP) 위반
하나의 클래스/메서드가 여러 책임을 지는 것을 금지합니다.

```java
// Bad (SRP 위반: Service가 HTTP 통신, 파싱, 계산, 캐싱 모두 담당)
@Service
public class EquipmentService {
    public ExpectationResult calculate(String ign) {
        // 1. HTTP 통신
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        // 2. JSON 파싱
        JsonNode root = objectMapper.readTree(response.getBody());
        // 3. 계산
        double expectation = calculator.calculate(root);
        // 4. 캐싱
        cache.put(ign, expectation);
        return new ExpectationResult(expectation);
    }
}
```

### 2. 개방 폐쇄 원칙(OCP) 위반
기능 확장 시 기존 코드를 수정하는 것을 금지합니다.

```java
// Bad (OCP 위반: 새로운 계산식 추가 시 코드 수정)
public class Calculator {
    public double calculate(String type, Equipment equip) {
        if (type.equals("CUBE")) {
            return calculateCube(equip);
        } else if (type.equals("STARFORCE")) {
            return calculateStarforce(equip);
        }
        // 새로운 타입 추가 시 if-else 계속 증가
    }
}
```

### 3. 의존성 역전 원칙(DIP) 위반
구체 클래스에 의존하고 인터페이스를 사용하지 않는 것을 금지합니다.

```java
// Bad (DIP 위반: 구체 클래스에 직접 의존)
@Service
public class CharacterService {
    private final MySqlCharacterRepository repository;  // 구체 클래스
    private final RedisCache cache;  // 구체 클래스
}
```

### 4. God Class/Spaghetti 코드
하나의 메서드가 2단계를 초과하는 인덴트를 가지거나 너무 긴 것을 금지합니다.

```java
// Bad (Spaghetti: 깊은 중첩, 20줄 초과)
public void process(String ign) {
    if (ign != null) {
        if (ign.length() > 0) {
            Optional<Character> opt = repo.findById(ign);
            if (opt.isPresent()) {
                Character c = opt.get();
                if (c.isActive()) {
                    for (Equipment e : c.getEquipment()) {
                        if (e.isValid()) {
                            // ... 복잡한 로직
                        }
                    }
                }
            }
        }
    }
}
```

### 5. 하드코딩 금지
모든 값을 설정 파일, Enum, 상수로 관리합니다.

```java
// Bad (하드코딩)
restTemplate.setConnectTimeout(5000);  // 매직 넘버
restTemplate.setReadTimeout(10000);
String url = "https://api.nexon.com/v1/character";
```

### 6. @Deprecated 사용 금지
@deprecated 기능은 절대 사용하지 않으며 최신 Best Practice API를 사용합니다.

```java
// Bad (Deprecated 사용)
restTemplate.getForObject(url, String.class);  // Spring 5+에서는 RestClient 권장
```

### 7. 왜 위험한가?
- **유지보수 어려움**: 한 클래스 수정 시 다른 클래스 영향 (결합도 높음)
- **테스트 어려움**: 여러 책임을 가진 클래스는 단위 테스트 불가
- **확장성 부족**: 새 기능 추가 시 기존 코드 수정 필요 (버그 유발)
- **코드 가독성 저하**: 중첩 깊이 깊어지면 이해/수정 어려움

## DO (베스트 프랙티스)

### 1. 단일 책임 원칙(SRP) 준수
각 클래스는 하나의 책임만 가지도록 분리합니다.

```java
// Good (SRP 준수: 각 클래스가 하나의 책임)
@Component
public class NexonApiClient {
    public String fetchCharacterData(String ign) { /* HTTP 통신만 */ }
}

@Component
public class EquipmentParser {
    public List<Equipment> parse(String json) { /* 파싱만 */ }
}

@Service
public class ExpectationCalculator {
    public double calculate(List<Equipment> equipment) { /* 계산만 */ }
}

@Component
public class CacheManager {
    public void put(String key, Object value) { /* 캐싱만 */ }
}

// Facade가 조립
@Service
public class GameCharacterFacade {
    private final NexonApiClient apiClient;
    private final EquipmentParser parser;
    private final ExpectationCalculator calculator;
    private final CacheManager cache;

    public ExpectationResult getExpectation(String ign) {
        return cache.getOrCompute(ign, () ->
            calculator.calculate(parser.parse(apiClient.fetchCharacterData(ign)))
        );
    }
}
```

### 2. 개방 폐쇄 원칙(OCP) 준수
Strategy 패턴으로 새로운 기능 확장 시 기존 코드를 수정하지 않습니다.

```java
// Good (OCP 준수: Strategy 패턴)
public interface CalculatorStrategy {
    double calculate(Equipment equipment);
}

@Component
public class CubeCalculator implements CalculatorStrategy {
    public double calculate(Equipment e) { /* 큐브 계산 */ }
}

@Component
public class StarforceCalculator implements CalculatorStrategy {
    public double calculate(Equipment e) { /* 스타포스 계산 */ }
}

// 새로운 계산식 추가 시 구현체만 추가
@Component
public class MiracleCalculator implements CalculatorStrategy {
    public double calculate(Equipment e) { /* 미라클 계산 */ }
}

@Service
public class CalculatorFactory {
    private final Map<String, CalculatorStrategy> strategies;

    public CalculatorStrategy getStrategy(String type) {
        return strategies.get(type + "Calculator");
    }
}
```

### 3. 의존성 역전 원칙(DIP) 준수
인터페이스에 의존하고 구체 클래스는 DI로 주입받습니다.

```java
// Good (DIP 준수: 인터페이스에 의존)
public interface CharacterRepository {
    Optional<Character> findById(String ign);
}

public interface CacheService {
    void put(String key, Object value);
    <T> T get(String key, Class<T> type);
}

@Service
public class CharacterService {
    private final CharacterRepository repository;  // 인터페이스
    private final CacheService cache;  // 인터페이스

    @RequiredArgsConstructor  // 생성자 주입 필수
    public CharacterService(CharacterRepository repository, CacheService cache) {
        this.repository = repository;
        this.cache = cache;
    }
}
```

### 4. Fail Fast & Early Return
중첩을 최소화하기 위해 Early Return을 사용합니다.

```java
// Good (Early Return: 중첩 최소화)
public void process(String ign) {
    if (ign == null || ign.isEmpty()) {
        throw new IllegalArgumentException("IGN cannot be empty");
    }

    Character character = repository.findById(ign)
        .orElseThrow(() -> new CharacterNotFoundException(ign));

    if (!character.isActive()) {
        return;  // 비활성 캐릭터는 처리하지 않음
    }

    List<Equipment> validEquipment = character.getEquipment().stream()
        .filter(Equipment::isValid)
        .toList();

    processEquipment(validEquipment);
}
```

### 5. 설정 분리
모든 매직 넘버와 설정을 외부로 분리합니다.

```java
// Good (설정 분리)
@ConfigurationProperties(prefix = "nexon.api")
public record NexonApiProperties(
    @DefaultValue("https://api.nexon.com") String baseUrl,
    @DefaultValue("v1") String version,
    @DefaultValue("5000") Duration connectTimeout,
    @DefaultValue("10000") Duration readTimeout
) {}
```

### 6. Modern Java API 활용
최신 Best Practice API를 사용합니다.

```java
// Good (RestClient 사용 - Spring 6.2+)
RestClient restClient = RestClient.builder()
    .requestFactory(new ReactorClientHttpConnector())
    .build();

String response = restClient.get()
    .uri(url)
    .retrieve()
    .body(String.class);
```

### 7. Design Patterns 적용
문제 해결을 위한 적절한 패턴을 적용합니다.

```java
// Strategy: 복잡한 분기 처리
public interface CacheStrategy {
    <T> T get(String key, Callable<T> loader);
}

// Facade: 외부 통신 간소화
@Service
public class GameCharacterFacade {
    // 복잡한 내부 로직을 숨기고 단순한 인터페이스 제공
}

// Factory: 객체 생성
@Component
public class CalculatorStrategyFactory {
    public CalculatorStrategy getStrategy(CalculatorType type) { ... }
}

// Template Method: 확장 가능한 템플릿
public abstract class AbstractCacheAspect {
    protected abstract String generateKey(Object[] args);
    protected abstract Duration getTtl();
}
```

### 8. 기대 효과
- **응집도 향상**: 관련 로직이 한 곳에 모여 이해 용이
- **결합도 감소**: 모듈 간 의존성 최소화로 독립적 수정 가능
- **테스트 용이**: 단일 책임 클래스는 단위 테스트 간단
- **확장성**: 새 기능 추가 시 기존 코드 수정 없이 구현체만 추가

## 출처
- CLAUDE.md Section 4: Implementation Logic & SOLID
- CLAUDE.md Section 5: Anti-Pattern & Deprecation Prohibition
- CLAUDE.md Section 6: Design Patterns & Structure
