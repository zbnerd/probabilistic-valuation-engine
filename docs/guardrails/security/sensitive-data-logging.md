---
id: GR-SEC-004
category: security
severity: critical
keywords: [Logging, Masking, GDPR, PII, API Key, Password]
---

# Sensitive Data Logging Rules

## DON'T (안티패턴)

### 1. Record toString() 기본값 사용
```java
// Bad (기본 toString() -> 모든 필드 노출)
public record LoginRequest(String apiKey, String password) {}
// 로그: LoginRequest[apiKey=live_abcd1234efgh5678, password=MyP@ssw0rd!]
```

### 2. TaskContext에 민감 정보 포함
```java
// Bad (민감 정보가 트레이스에 남음)
executor.execute(() -> service.process(apiKey),
    TaskContext.of("Service", "Process", apiKey));  // API Key 노출
```

### 3. 예외 메시지에 민감 정보 포함
```java
// Bad
throw new InvalidApiKeyException("Invalid key: " + apiKey);
```

### 4. 비밀번호 평문 로깅
```java
// Bad
log.info("User login: username={}, password={}", username, password);
```

## DO (베스트 프랙티스)

### 1. Record toString() 오버라이드 (Critical)
```java
// Good (마스킹 적용)
public record LoginRequest(String apiKey, String password) {
    @Override
    public String toString() {
        return "LoginRequest[" +
            "apiKey=" + maskApiKey(apiKey) +
            ", password=***]";
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
// 로그: LoginRequest[apiKey=live****5678, password=***]
```

### 2. 마스킹 패턴 (데이터 타입별)

| 데이터 종류 | 예시 | 마스킹 패턴 |
|-------------|------|------------|
| **API Key** | `live_abcd1234efgh5678` | `live****5678` |
| **JWT Token** | `eyJhbGciOiJIUzI1Ni...` | `eyJhbG...` |
| **비밀번호** | `MyP@ssw0rd!` | `********` |
| **주민번호** | `901231-1234567` | `901231-*******` |
| **전화번호** | `010-1234-5678` | `010-****-5678` |
| **신용카드** | `1234-5678-9012-3456` | `1234-****-****-3456` |

### 3. LogicExecutor 내부 마스킹
```java
// Good (마스킹된 컨텍스트)
executor.execute(() -> service.process(apiKey),
    TaskContext.of("Service", "Process", maskApiKey(apiKey)));
```

### 4. 예외 메시지에서 식별자만 포함
```java
// Good (구체적 ID만 포함)
throw new InvalidApiKeyException("Invalid API key. ID: " + keyId);
```

### 5. Gson/Jackson 순환 참조 방지
```java
// Good (@JsonIgnore)
@Entity
public class User {
    @JsonIgnore
    private List<Order> orders;  // JSON 변환 시 제외
}
```

### 6. Logback Filter로 민감 정보 필터링
```xml
<!-- logback-spring.xml -->
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
        <evaluator>
            <expression>
                throwable != null &amp;&amp;
                throwable.getMessage() != null &amp;&amp;
                (throwable.getMessage().contains("apiKey") ||
                 throwable.getMessage().contains("password") ||
                 throwable.getMessage().contains("token"))
            </expression>
        </evaluator>
        <onMismatch>DENY</onMismatch>
    </filter>
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

## GDPR/개인정보보호법 준수

### 데이터 처리 원칙
1. **최소화**: 필요한 정보만 로깅
2. **마스킹**: 모든 PII(Personally Identifiable Information) 마스킹
3. **접근 제어**: 로그 파일 접근 권한 제한
4. **보관 기간**: 로그 자동 삭제 (30일 이내 권장)
5. **암호화**: 로그 저장 시 암호화

## Monitoring & Alerts

```prometheus
# 민감 정보 로깅 감지 (테스트 환경에서만)
ALERT SensitiveDataInLogs
  IF rate(log_sensitive_data_detected_total[5m]) > 0
  SEVERITY critical

  ANNOTATIONS {
    summary = "Sensitive data detected in logs",
    description = "Check log masking implementation"
  }
```

## Verification Commands

```bash
# 1. 평문 API Key 검색
grep -r "live_" logs/ | grep -v "****"

# 2. 평문 비밀번호 검색
grep -r "password=" logs/ | grep -v "***"

# 3. toString() 마스킹 확인
grep -r "toString" src/main/java/**/dto/ | grep -v "mask"

# 4. TaskContext 민감 정보 확인
grep -r "TaskContext.of.*apiKey" src/main/java/
```

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 31
- [docs/03_Technical_Guides/infrastructure.md](../../../03_Technical_Guides/infrastructure.md) Section 19
- GDPR Article 32 - Security of Processing
