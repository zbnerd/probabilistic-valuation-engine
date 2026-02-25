---
id: GR-REFACTOR-008
category: architecture/refactor
severity: critical
keywords: [environment-variable, naming, configuration, silent-failure]
languages: [java, yaml, bash]
---

# Environment Variable Naming Consistency

## DON'T (이름 불일치로 Silent Failure)
- `.env.example`과 `application.yml`의 변수명이 다름
- Spring Boot가 빈 문자열을 기본값으로 사용
- 장애 발생 시 알림이 전송되지 않음

```bash
# .env.example (틀림)
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/your-webhook-url
```

```yaml
# application.yml (틀림)
alert:
  discord:
    webhook-url: ${ALERT_DISCORD_WEBHOOK_URL:}  # ❌ 변수명 불일치!
```

**영향:**
- Spring Boot 시작 시 `ALERT_DISCORD_WEBHOOK_URL`를 찾지만 없음
- `webhook-url`이 **빈 문자열**로 설정됨
- 모든 Discord 알림이 실패하지만 로그만 남고 **조용히 실패**

## DO (일관된 네이밍 규칙)
- `.env.example`과 `application.yml`의 변수명을 **일치**시킴
- `@Value`의 기본값을 제거하여 **명시적 실패** 유도
- `ConfigurationProperties`로 타입 안전성 확보

```bash
# .env.example (올바름)
# Discord webhook URL for alerts (optional)
ALERT_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/your-webhook-url
```

```yaml
# application.yml (올바름)
alert:
  discord:
    webhook-url: ${ALERT_DISCORD_WEBHOOK_URL:}  # ✅ 일치

# 또는 기본값 제거로 빈 문자열 방지
alert:
  discord:
    webhook-url: ${ALERT_DISCORD_WEBHOOK_URL}  # ✅ 설정 없으면 시작 실패
```

```java
// Good: @ConfigurationProperties로 타입 안전성 확보
@ConfigurationProperties(prefix = "alert.discord")
@Validated
public class DiscordAlertProperties {
    private String webhookUrl;

    @NotBlank(message = "alert.discord.webhook-url must be set")
    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
}

// 또는 @PostConstruct로 명시적 검증
@Component
public class DiscordAlertConfig {
    @Value("${alert.discord.webhook-url:}")
    private String webhookUrl;

    @PostConstruct
    public void validate() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException(
                "alert.discord.webhook-url must be set when alerts are enabled"
            );
        }
    }
}
```

```kotlin
// Good: @ConfigurationProperties with validation
@ConfigurationProperties(prefix = "alert.discord")
@Validated
class DiscordAlertProperties(
    @field:NotBlank(message = "alert.discord.webhook-url must be set")
    val webhookUrl: String
)
```

## 네이밍 규칙 가이드

| 카테고리 | 접두사 | 예시 |
|----------|--------|------|
| Alert | `ALERT_` | `ALERT_DISCORD_WEBHOOK_URL` |
| Database | `DB_` | `DB_HOST`, `DB_PORT` |
| Redis | `REDIS_` | `REDIS_HOST`, `REDIS_PORT` |
| API Key | `${SERVICE}_API_KEY` | `NEXON_API_KEY`, `OPENAI_API_KEY` |
| JWT | `JWT_` | `JWT_SECRET`, `JWT_EXPIRATION` |

## 영향받는 컴포넌트

1. **StatelessAlertService** (ADR-0345)
   ```java
   @Value("${alert.discord.webhook-url:}")
   private String webhookUrl;  // 영향 받음
   ```

2. **DiscordAlertService** (v2 package)
   ```java
   @Value("${alert.discord.webhook-url:}")
   private String webhookUrl;  // 영향 받음
   ```

3. **DiscordNotifier** (monitoring copilot)
   ```java
   @Value("${alert.discord.webhook-url:}")
   private String webhookUrl;  // 영향 받음
   ```

## 증거 및 검증

### 실패 증상

```
[DiscordAlertChannel.send()]
  → POST to empty URL
  → WebClientRequestException
  → Logged as warning only
```

### 검증 명령어

```bash
# 환경 변수 확인
grep "DISCORD_WEBHOOK_URL\|ALERT_DISCORD_WEBHOOK_URL" .env.example

# application.yml 설정 확인
grep -A2 "discord:" application.yml | grep webhook-url

# 런타임 설정 확인
curl -s http://localhost:8080/actuator/env | jq '.propertySources[].properties | .["alert.discord.webhook-url"]'
```

## 출처
- [Discord Webhook Root Cause Analysis](../../../../05_Reports/04_05_Incidents/discord-webhook-root-cause-analysis.md)
- [ADR-0345: Stateless Alert System](../../../../adr/ADR-0345-stateless-alert-system.md)
