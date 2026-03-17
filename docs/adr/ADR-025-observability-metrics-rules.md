# ADR-025: Observability Metrics 명명 규칙 및 카디널리티 제한

## 상태

**제안됨 (Proposed)**

## 컨텍스트

현재 MapleExpectation 시스템은 **Micrometer + Prometheus** 조합으로 메트릭 수집을 수행하고 있으며, `GoldenSignalsCollector`를 통해 Golden Signals를 계산하고 있습니다. 그러나 다음과 같은 표준화 문제가 존재합니다:

### 현재 상태

1. **구현 완료**: Micrometer integration, Prometheus export
2. **Golden Signals 수집**: `GoldenSignalsCollector`에서 Latency, Traffic, Errors, Saturation 계산
3. **도메인 메트릭**: `EventOutboxMetrics` 등 커스텀 메트릭 존재

### 문제점

- **명명 표준 부재**: `http.server.requests`, `event_outbox_pending_count`, `nexon.api.performance` 등 혼합된 명명 패턴
- **카디널리티 제한 없음**: 고카디널리티 레이블 조합으로 인한 메모리 폭발 위험
- **단위 접미사 불일치**: `_total`, `_seconds`, `_count` 사용이 임의적
- **강제 메커니즘 부재**: ArchUnit이나 MeterFilter로 규칙 강제화하지 않음

## 결정

### 1. 명명 규칙 (Naming Conventions)

| 타입 | 패턴 | 예시 | 접미사 |
|------|------|------|--------|
| Counter | `{domain}_{entity}_{event}_total` | `nexon_api_outbox_processed_total` | `_total` |
| Gauge | `{domain}_{entity}_{attribute}` | `expectation_pool_active_threads` | (없음) |
| Histogram | `{domain}_{operation}_duration_seconds` | `nexon_api_call_duration_seconds` | `_seconds` |
| Distribution Summary | `{domain}_{entity}_{size}_bytes` | `event_payload_size_bytes` | `_bytes` |

**단위 접미사 표준:**
- `_total`: Counter 타입 (누적 카운트)
- `_seconds`: 시간 기반 Histogram/Timer
- `_bytes`: 바이트 크기 (Distribution Summary)
- `_count`: 이벤트 발생 횟수 (Counter와 구별 필요시만 사용)

**네이밍 규칙:**
- 소문자 + snake_case만 허용
- 도메인 접두사 필수: `expectation_`, `nexon_api_`, `event_outbox_`
- CamelCase 금지
- 약어 사용 최소화 (`api`, `http`, `db` 등 공통 약어만 허용)

### 2. 필수 메트릭 (Required Metrics - Golden Signals)

모든 서비스는 반드시 다음 Golden Signals 메트릭을 노출해야 합니다:

| 시그널 | 메트릭명 | 타입 | 필수 레이블 |
|------|---------|------|-----------|
| Latency | `http_server_requests_seconds` | Histogram | `method`, `uri`, `status` |
| Traffic | `http_server_requests_total` | Counter | `method`, `uri`, `status` |
| Errors | `http_server_errors_total` | Counter | `status`, `exception` |
| Saturation | `hikaricp_connections_active` | Gauge | `pool` |

### 3. 카디널리티 제한 (Cardinality Limits)

**규칙: 메트릭당 레이블 조합 ≤ 10,000**

```kotlin
// Bad: 고카디널리티 (userId, requestId)
Counter.builder("api_requests_total")
    .tag("user_id", userId)      // 수백만 조합
    .tag("request_id", reqId)    // 유니크한 값
    .register(registry)

// Good: 저카디널리티
Counter.builder("api_requests_total")
    .tag("method", "POST")       // 10개 미만
    .tag("endpoint", "/api/v1/cubes")  // 100개 미만
    .tag("status", "200")        // 30개 미만
    .register(registry)
```

**고카디널리티 데이터 처리:**
- 레이블 조합이 10,000을 초과할 가능성이 있으면 로그/트레이스로 이동
- 예: `user_id`, `request_id`, `session_id` → Logging/Tracing
- 예: `uri` → `uri_pattern` (와일드카드 사용)

### 4. 강제 메커니즘 (Enforcement)

| 규칙 | 강제 수단 | 구현 위치 |
|------|----------|-----------|
| 명명 규칙 (snake_case) | ArchUnit test | `module-infra/src/test/kotlin/.../MetricsNamingConventionTest.kt` |
| 카디널리티 제한 | MeterFilter | `MicrometerConfig.cardinalityLimitFilter()` |
| 필수 메트릭 확인 | Integration Test | `GoldenSignalsCollectorTest` |
| @PostConstruct 초기화 | Code Review Checklist | PR Template |

**MeterFilter 예시:**

```kotlin
@Configuration
class MicrometerConfig {

    @Bean
    fun cardinalityLimitFilter(): MeterFilter {
        return MeterFilter.maxAmountInSameTagRegistry(
            MetricsEndpointSanitizer.MAX_TAG_VALUES,
            "uri", "user_id", "request_id"
        )
    }

    @Bean
    fun namingConventionFilter(): MeterFilter {
        return MeterFilter.deny { id ->
            !id.name.matches(Regex("^[a-z_]+(_total|_seconds|_bytes|_count)?"))
        }
    }
}
```

### 5. 레이블 명명 규칙

- **snake_case**: `status_code`, `pool_name` (O)
- **CamelCase 금지**: `statusCode`, `poolName` (X)
- **값 정규화**: `/api/cubes/123` → `/api/cubes/{id}`
- **공통 레이블 추천**:
  - `method`: GET, POST, PUT, DELETE
  - `status`: 200, 400, 500
  - `pool`: hikaricp pool 이름
  - `exception`: exception 클래스 이름

## 결과

### 이점

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| 명명 일관성 | `http.server.requests`, `event_outbox_pending_count` 혼용 | `http_server_requests_seconds`, `event_outbox_pending_count` 표준화 |
| 카디널리티 제어 | 무제한 (OOM 위험) | 10,000 조합 제한 |
| 가독성 | 암묵적 지식 필요 | 문서화된 규칙 + 자동 강제 |
| Prometheus 성능 | 고카디널리티로 인한 쿼리 느려짐 | 저카디널리티로 빠른 쿼리 |

### 마이그레이션 경로

1. **Phase 1**: MicrometerConfig에 MeterFilter 추가 (차단 없이 로그만)
2. **Phase 2**: ArchUnit test 작성 (위반 감지)
3. **Phase 3**: 기존 메트릭 이름 리팩터링 (`http.server.requests` → `http_server_requests_seconds`)
4. **Phase 4**: 카디널리티 제한 강화 (MeterFilterdeny → 거부)

### 위험 평가

- **Risk Level**: MEDIUM
- **Breaking Change**: PARTIAL (기존 메트릭 이름 변경 → Grafana dashboard 업데이트 필요)
- **Rollback**: MeterFilter 제거, ArchUnit test 비활성화

## 이력

| 날짜 | 변경 내용 |
|------|-----------|
| 2026-03-15 | 초안 작성 (Issue #517) |

## 참조

### 관련 ADR
- [ADR-008: Observability Strategy](../01_ADR/ADR-008-observability-strategy.md) (존재하지 않음 - 신규 작성 필요)
- [ADR-044: LogicExecutor Zero Try-Catch](ADR-044-logicexecutor-zero-try-catch.md)

### 구현 파일
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/GoldenSignalsCollector.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/EventOutboxMetrics.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/MicrometerConfig.kt` (신규 작성 필요)
- `module-infra/src/test/kotlin/maple/expectation/infrastructure/metrics/MetricsNamingConventionTest.kt` (신규 작성 필요)

### 관련 이슈
- Issue #517: Observability Metrics 명명 규칙 및 카디널리티 제한

### 참고 자료
- [Prometheus Naming Best Practices](https://prometheus.io/docs/practices/naming/)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Google SRE Book - Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)
