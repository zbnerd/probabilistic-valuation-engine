# ADR-005: Monitoring-Infra 모듈 이관

## 상태
Proposed (2026-03-01)

## 컨텍스트

**현재 상황:**
1. `monitoring/ai/*` (7개 클래스)와 `monitoring/copilot/*` (26개 클래스)가 module-app에 위치
2. 외부 인프라 의존성 (OpenAI, Prometheus, Discord)이 비즈니스 로직과 혼재
3. ADR-005 완료 후 Web/Infra 계층 분리는 완료되었으나, Monitoring 모듈은 미이관 상태

**목표:**
- Monitoring 관련 인프라 구현체를 module-infra로 이관
- Port 인터페이스를 module-core에 정의하여 의존성 역전 달성
- ADR-005 패턴 준수: `module-core (Port) ← module-infra (Adapter)`

## 결정

### Port 인터페이스 추출 (module-core)

| Port | 메서드 | 목적 |
|------|--------|------|
| `MetricsQueryPort` | `queryRange(promql, start, end, step)` | Prometheus 메트릭 쿼리 |
| `AiAnalysisPort` | `analyzeError(exception)`, `analyzeIncident(context)` | AI 기반 에러 분석 |
| `AlertNotificationPort` | `send(content)`, `formatIncidentMessage(...)` | Discord 알림 발송 |
| `AnomalyDetectionPort` | `detect(signal, timeSeries, now, config)` | 이상 탐지 알고리즘 |

### 패키지 구조

```
module-core/
├── port/out/
│   ├── MetricsQueryPort.kt
│   ├── AiAnalysisPort.kt
│   ├── AlertNotificationPort.kt
│   └── AnomalyDetectionPort.kt
├── monitoring/model/
│   ├── AnomalyEvent.kt
│   ├── SignalDefinition.kt
│   ├── IncidentContext.kt
│   └── ... (12개 모델)

module-infra/
├── monitoring/
│   ├── prometheus/
│   │   └── MetricsQueryPortAdapter.kt  # PrometheusClient 래핑
│   ├── ai/
│   │   ├── AiAnalysisPortAdapter.kt    # AiSreService 래핑
│   │   ├── AiPromptBuilder.kt
│   │   ├── AiResponseParser.kt
│   │   └── AiAnalysisFormatter.kt
│   ├── alert/
│   │   └── AlertNotificationPortAdapter.kt  # DiscordNotifier 래핑
│   ├── anomaly/
│   │   └── AnomalyDetectionPortAdapter.kt   # AnomalyDetector 래핑
│   └── scheduler/
│       └── MonitoringCopilotScheduler.kt
├── config/
│   ├── OpenAIConfiguration.kt
│   └── MonitoringCopilotConfig.kt
```

### 이관 순서

1. **Port 인터페이스 정의** (module-core)
   - 4개 Port 인터페이스 생성
   - 기존 구현체 메서드 시그니처 참조

2. **모델 클래스 이관** (module-core)
   - Java Record → Kotlin data class 변환
   - 12개 모델 클래스 이관

3. **Adapter 구현체 이관** (module-infra)
   - PrometheusClient → MetricsQueryPortAdapter
   - AiSreService → AiAnalysisPortAdapter
   - DiscordNotifier → AlertNotificationPortAdapter
   - AnomalyDetector → AnomalyDetectionPortAdapter

4. **Config 및 Scheduler 이관** (module-infra)
   - OpenAIConfiguration, MonitoringCopilotConfig
   - MonitoringCopilotScheduler

5. **의존성 정리**
   - module-app에서 직접 참조 제거
   - Port 인터페이스 통한 의존성 역전

### 의존성 그래프 (이관 후)

```
module-app (Scheduler/Service)
      │
      ├──→ module-core (Port 인터페이스, Model)
      │          ↑
      │          │
      └──→ module-infra (Port 구현체, Config)
               │
               └──→ 외부 API (OpenAI, Prometheus, Discord)
```

## 근거

1. **단일 책임 원칙 (SRP)**: 비즈니스 로직과 인프라 구현 분리
2. **의존성 역전 원칙 (DIP)**: 상위 모듈이 하위 모듈에 의존하지 않음
3. **테스트 용이성**: Port mocking으로 단위 테스트 가능
4. **확장성**: AlertNotificationPort 구현체를 Slack/Teams로 교체 가능

## 영향

### 긍정적 영향
- Monitoring 인프라 교체 시 비즈니스 로직 영향 없음
- AI 서비스 교체 (OpenAI → Anthropic) 시 Port 구현체만 변경
- 단위 테스트 시 외부 API 의존 제거

### 위험 요소
- 대규모 파일 이동으로 인한 Git history 단절
- 기존 테스트 코드 import 경로 수정 필요

## 관련 문서

- ADR-005: 모듈 의존성 그래프 및 이관 전략
- ADR-003: Hexagonal Architecture 채택
- CLAUDE.md: Section 4 (SOLID), Section 12 (LogicExecutor)

## 이력

| 날짜 | 상태 | 변경 사항 |
|------|------|-----------|
| 2026-03-01 | Proposed | 최초 작성 |
