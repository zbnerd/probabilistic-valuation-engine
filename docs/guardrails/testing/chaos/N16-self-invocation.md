---
id: GR-CHAOS-N16
category: testing/chaos
severity: medium
keywords: [Nightmare, chaos, N16, Self-Invocation, AOP Proxy, @Cacheable, @Transactional]
languages: [java, kotlin]
---

# [N16] Self-Invocation Mirage

## DON'T (장애 원인)

동일 클래스 내에서 `this.method()` 호출 시 **@Cacheable, @Transactional 등 AOP 어노테이션이 동작하지 않습니다**.

### 위험 코드 패턴

```java
// 위험: Self-invocation
@Service
public class UserService {
    public UserDto getUser(Long id) {
        // ... 검증 로직 ...
        return this.getCachedUser(id);  // ❌ Self-invocation!
    }

    @Cacheable("users")
    public UserDto getCachedUser(Long id) {
        return userRepository.findById(id);  // 캐시 무시됨!
    }
}
```

### 장애 시나리오

```
External call:
Client → Spring Proxy → UserService.getUser()
           ↑ AOP 동작 ✅

Internal call (this):
UserService.getUser() → this.getCachedUser()
                         ↑ Proxy 우회! AOP 동작 안 함 ❌
```

### 장애 수치
- **Cache Hit Rate**: 0% (@Cacheable 무시)
- **Transaction Applied**: 거짓 (@Transactional 무시)
- **AOP Effectiveness**: 0% (모든 어노테이션 무효)

---

## DO (재발 방지)

### 1. 별도 Bean 분리 (권장)

```java
@Service
public class UserService {
    private final UserCacheService cacheService;

    public UserDto getUser(Long id) {
        return cacheService.getCachedUser(id);  // ✅ 외부 호출
    }
}

@Service
public class UserCacheService {
    @Cacheable("users")
    public UserDto getCachedUser(Long id) {
        return userRepository.findById(id);
    }
}
```

### 2. AopContext.currentProxy() (대안)

```java
@Service
public class UserService {
    public UserDto getUser(Long id) {
        UserService proxy = (UserService) AopContext.currentProxy();
        return proxy.getCachedUser(id);  // ✅ Proxy 통해 호출
    }

    @Cacheable("users")
    public UserDto getCachedUser(Long id) { ... }
}

// 설정 필요
@EnableAspectJAutoProxy(exposeProxy = true)
```

### 3. @Lazy Self-Injection (대안)

```java
@Service
public class UserService {
    @Lazy
    @Autowired
    private UserService self;  // Proxy 주입

    public UserDto getUser(Long id) {
        return self.getCachedUser(id);  // ✅ Proxy 통해 호출
    }
}
```

### 4. IntelliJ Inspection 활성화

```
Editor → Inspections → Spring → Spring Core → Self-invocation bypasses Spring proxy
```

### 5. ArchUnit 규칙 추가

```java
@ArchTest
static final ArchRule no_self_invocation =
    methods().that().areAnnotatedWith(Cacheable.class)
        .or().areAnnotatedWith(Transactional.class)
        .should(not(beCalledByMethod().thatIsDeclaredInSameClass()));
```

### 6. 코드 리뷰 체크리스트

- [ ] @Cacheable 메서드가 외부에서 호출됨
- [ ] @Transactional 메서드가 외부에서 호출됨
- [ ] Self-invocation 패턴 없음
- [ ] IntelliJ Inspection 경고 없음

### 개선 수치 (테스트 결과 기준)
- **Self-Invocation Count**: 0건 (코드베이스 검증)
- **Cache Hit Rate**: 정상 (@Cacheable 동작)
- **Transaction Boundaries**: 올바르게 적용
- **AOP Effectiveness**: 100%

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N16-self-invocation.md`
- `docs/05_Reports/04_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`
