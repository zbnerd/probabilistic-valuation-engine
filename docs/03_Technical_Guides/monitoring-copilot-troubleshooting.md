# Monitoring Copilot 트러블슈팅 가이드

> **날짜**: 2026-02-06
> **목차**: LangChain4J AI SRE 모니터링 시스템 구축 및 트러블슈팅
> **난이도**: 중급 (Intermediate)

---

## 📋 개요

probabilistic-valuation-engine 프로젝트에 **AI 기반 SRE 모니터링 시스템**을 구축하며 발생한 문제들을 해결한 과정입니다.

### 구성 요소
- **LangChain4J**: Z.ai GLM-4.7 모델 연동
- **Prometheus**: 메트릭 수집 (포트 9090)
- **Grafana**: 대시보드 시각화 (포트 3000)
- **Discord Webhook**: 알림 전송

---

## 🐛 문제 #1: Prometheus JSON 파싱 오류

### 증상
```
Cannot deserialize value of type `PrometheusClient$ValuePoint` from Array value (token `JsonToken.START_ARRAY`)
```

### 원인
Prometheus API는 `values`를 **배열의 배열** 형태로 반환합니다:
```json
{
  "data": {
    "result": [{
      "values": [[1234567890, "100.5"], [1234567905, "101.2"]]
    }]
  }
}
```

하지만 Jackson의 record deserializer는 이를 객체로 변환하지 못합니다.

### 해결책
**커스텀 JsonDeserializer** 구현:

```java
@JsonDeserialize(using = ValuePoint.Deserializer.class)
public record ValuePoint(long timestamp, String value) {

    static class Deserializer extends JsonDeserializer<ValuePoint> {
        @Override
        public ValuePoint deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.START_ARRAY) {
                throw ctxt.instantiationException(ValuePoint.class,
                    "Expected array for ValuePoint, got: " + p.currentToken());
            }

            p.nextToken(); // timestamp
            long ts = p.getLongValue();

            p.nextToken(); // value
            String val = p.getValueAsString();

            p.nextToken(); // consume END_ARRAY

            return new ValuePoint(ts, val);
        }
    }
}
```

**핵심 변경사항**:
1. `@JsonCreator` 제거 (record에서 작동하지 않음)
2. `@JsonDeserialize` 어노테이션 추가
3. `JsonParser`를 사용한 직접 파싱

---

## 🐛 문제 #2: PromQL URL 인코딩 오류

### 증상
```
Prometheus query failed: HTTP 400
parse error: unexpected identifier "rate"
```

### 원인
PromQL 쿼리에 함수 괄호가 포함되어 있는데, 수동 URL 인코딩이 불완전했습니다:

```java
// 기존 코드 (문제 있음)
private String urlEncode(String value) {
    return value.replace(" ", "+")
            .replace("\"", "%22")
            .replace("(", "%28")  // rate() 함수가 인코딩됨
            .replace(")", "%29");
}
```

### 해결책
**Java 표준 `URLEncoder` 사용**:

```java
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

private String urlEncode(String value) {
    try {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    } catch (Exception e) {
        throw new InternalSystemException("Failed to URL encode: " + value, e);
    }
}
```

**이점**:
- 모든 특수문자 자동 인코딩
- UTF-8 지원
- 표준 라이브러리 사용 (유지보수)

---

## 🐛 문제 #3: 중복 알림 (8번 반복)

### 증상
Discord에 동일한 인시던트가 8번 연속 전송됨:
```
⚠️ INCIDENT ALERT INC-29506406-5ae92aa7 [WARN]
⚠️ INCIDENT ALERT INC-29506406-5ae92aa7 [WARN]
... (8번 반복)
```

### 원인
**Race Condition** 발생:

```java
// 기존 코드 (순서 문제)
if (isRecentIncident(context.incidentId())) {
    return; // 체크 통과
}
// AI 분석 (느린 작업)
sendDiscordAlert(context, plan); // 전송
trackIncident(context.incidentId(), now); // 트래킹 ← 너무 늦음!
```

**시나리오**:
1. 8개의 스케줄러가 거의 동시에 실행
2. 모두 `isRecentIncident()` 체크 통과 (아직 트래킹 안됨)
3. 모두 Discord 전송 완료
4. 마지막에 트래킹

### 해결책
**트래킹을 체크 직후로 이동**:

```java
if (isRecentIncident(context.incidentId())) {
    return;
}
// 즉시 트래킹 (다른 스레드 방지)
trackIncident(context.incidentId(), now);

// AI 분석 (이제 안전함)
AiSreService.MitigationPlan plan = aiSreService
        .map(service -> service.analyzeIncident(context))
        .orElseGet(() -> createDefaultMitigationPlan(context));

sendDiscordAlert(context, plan);
```

**효과**:
- 첫 번째 스레드만 Discord 전송
- 나머지 7개는 체크에서 걸러짐

---

## 🐛 문제 #4: Z.ai API 잔액 부족

### 증상
```
dev.ai4j.openai4j.OpenAiHttpException: {"error":{"code":"1113",
"message":"Insufficient balance or no resource package. Please recharge."}}
```

### 해결책
**새 API 키로 교체**:

```bash
export GLM_4_API_KEY="ac16c39a2e9748fcbc0fd23c4741ad05.klcdyn9kS2n8x1va"
```

**Fallback 체인 작동 확인**:
- Z.ai 실패 → 규칙 기반 분석 자동 전환
- Discord 알림은 정상 전송됨

---

## 🐛 문제 #5: OpenAI Fallback 불필요 생성

### 증상
```
openAiApiKey cannot be null or empty
```

### 원인
`ZAiConfiguration`에서 항상 OpenAI Fallback 빈을 생성하려고 시도함.

### 해결책
**ConditionalOnProperty로 Fallback 제어**:

```java
// Z.ai만 있으면 충분
@Bean
@Primary
@ConditionalOnProperty(name = "langchain4j.glm-4.chat-model.api-key")
public ChatLanguageModel zAiChatModel() {
    return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .build();
}

// OpenAI는 별도 설정된 경우에만 생성
@Bean
@ConditionalOnProperty(name = "langchain4j.open-ai.chat-model.api-key")
public ChatLanguageModel openAiFallbackModel(...) {
    // OpenAI fallback
}
```

---

## ✅ 최종 구성

### 1. Docker Compose (Prometheus + Grafana)

```yaml
prometheus:
  image: prom/prometheus:latest
  container_name: maple-prometheus
  ports:
    - "9090:9090"
  volumes:
    - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
    - prometheus_data:/prometheus
  extra_hosts:
    - "host.docker.internal:host-gateway"

grafana:
  image: grafana/grafana:latest
  container_name: maple-grafana
  ports:
    - "3000:3000"
  environment:
    - GF_SECURITY_ADMIN_USER=admin
    - GF_SECURITY_ADMIN_PASSWORD=admin
```

### 2. Prometheus 설정 (prometheus.yml)

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'spring-boot'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

### 3. application.yml 설정

```yaml
app:
  monitoring:
    enabled: true
    prometheus:
      base-url: http://localhost:9090
    discord:
      webhook-url: ${DISCORD_WEBHOOK_URL}
    interval-seconds: 15
    z-score:
      threshold: 3.0

langchain4j:
  glm-4:
    chat-model:
      base-url: https://api.z.ai/api/paas/v4
      api-key: ${GLM_4_API_KEY}
      model-name: glm-4.7
      timeout: 60s

ai:
  sre:
    enabled: ${AI_SRE_ENABLED:true}
```

### 4. 환경 변수

```bash
export DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."
export AI_SRE_ENABLED="true"
export GLM_4_API_KEY="your-api-key"
```

---

## 🎯 성공 결과

### Discord 알림 예시
```
🚨 INC-29506406-5ae92aa7 [WARN]

📊 Top Anomalous Signals
1. **MySQL Lock Pool Connections**: 30.0000

🤖 AI Hypotheses
자동 분석 불가 - 수동 점검 필요

🔧 Proposed Actions
1. 시스템 로그 확인
2. 메트릭 모니터링

📋 Evidence (PromQL)
hikaricp_connections_idle{pool="MySQLLockPool"}
```

---

## 📚 학습 포인트

1. **Jackson Record Deserialization**: `@JsonCreator`만으로는 부족, `@JsonDeserialize` 필요
2. **URL Encoding**: 수동 구현보다 `URLEncoder` 사용 (안정성)
3. **Race Condition**: De-duplication 트래킹은 "체크 후 즉시" 해야 함
4. **Fallback 체인**: `@ConditionalOnProperty`로 빈 생성 방지

---

## 🔗 관련 문서

- [AI SRE Service](../../src/main/java/maple/expectation/monitoring/ai/AiSreService.java)
- [Prometheus Client](../../src/main/java/maple/expectation/monitoring/copilot/client/PrometheusClient.java)
- [Monitoring Pipeline](../../src/main/java/maple/expectation/monitoring/copilot/pipeline/MonitoringPipelineService.java)
- [Discord Notifier](../../src/main/java/maple/expectation/monitoring/copilot/notifier/DiscordNotifier.java)

---

## 🚀 다음 단계

- [ ] 스로틀링 동작 확인 (AlertThrottler)
- [ ] Z.ai API 크레딧 충전
- [ ] Grafana Dashboard 8개 배포
- [ ] Prometheus Alertmanager 설정
