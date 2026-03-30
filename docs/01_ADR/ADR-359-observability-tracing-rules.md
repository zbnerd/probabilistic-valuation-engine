# ADR-359: Observability Tracing Rules

## 상태 (Status)

**제안됨 (Proposed)**

## 컨텍스트 (Context)

### 현재 상태

현재 OpenTelemetry는 구성되어 있으나 비활성화된 상태입니다:

```kotlin
// module-infra/.../OpenTelemetryConfig.kt
@ConditionalOnProperty(name = ["management.tracing.enabled"], havingValue = "true")
class OpenTelemetryConfig {
    // RuleBasedRoutingSampler: 5% 샘플링, /actuator, /health drop
}
```

- **Sampling Rate**: 5% head sampling (application.yml)
- **Distributed Tracing**: 미구현 (단일 서버 내 span만 존재)
- **Span Coverage**: Spring MVC 자동 span만 생성 (컨트롤러 계층)

### 문제 정의

1. **가시성 부족**: 외부 API 호출(Nexon API), 캐시 조회(Redis), DB 조회(PostgreSQL)에 수동 span이 없음
2. **분산 추적 미지원**: Kafka 메시징, HTTP 클라이언트에 trace context 전파가 없음
3. **민감정보 노출 위험**: OCID, API Key가 span attribute에 포함될 가능성
4. **일관성 없는 네이밍**: 자동 생성 span 이름(`Controller#method`)과 비즈니스 컨텍스트 불일치

## 결정 (Decision)

### 1. Span 생성 규칙 (Span Creation Rules)

| Layer | Package Pattern | Example Class | Requirement | Span Name |
|-------|-----------------|---------------|-------------|-----------|
| Controller | `module-web/**/controller/**` | `CharacterController` | **Required** | `controller.character.{method}` |
| Facade | `module-app/**/facade/**` | `GameCharacterFacade` | Recommended | `facade.character.process` |
| Port | `module-core/**/port/**` | `NexonApiPort` | **Required** | `port.nexon.fetch` |
| Adapter | `module-infra/**/adapter/**` | `NexonApiClient` | **Required** | `adapter.nexon.fetch` |
| Repository | `module-core/**/repository/**` | `CharacterRepository` | Optional | `repository.character.query` |

**구현 예시:**
```kotlin
@WithSpan("controller.character.fetch")
suspend fun fetch(@PathVariable ocid: String): ResponseEntity<CharacterResponse> {
    val masked = StringMaskingUtils.maskOcid(ocid)
    Span.current().setAttribute("character.id", masked)
    // ...
}
```

### 2. Span 속성 표준 (Required Span Attributes)

| Context | Attributes | Example |
|---------|------------|---------|
| HTTP Request | `http.method`, `http.url`, `http.status_code` | `http.method=GET`, `http.status_code=200` |
| Business | `character.id` (masked), `cube.type` | `character.id=abcd***`, `cube.type=additional` |
| Error | `error.type`, `error.message` (sanitized) | `error.type=ApiTimeoutException` |

**금지 속성:**
- OCID 원본 (반드시 `maskOcid()` 적용)
- API Key, JWT Token
- 캐시 키 원본 (반드시 `maskCacheKey()` 적용)

### 3. 샘플링 전략 (Sampling Strategy)

```
IF (error OR latency > P95)
    THEN 100% capture (tail sampling)
    ELSE 5% head sampling
```

**구현 (RuleBasedRoutingSampler):**
```kotlin
RuleBasedRoutingSampler.builder(SpanKind.SERVER, fallback)
    .drop(UrlAttributes.URL_PATH, "^/actuator.*")
    .drop(UrlAttributes.URL_PATH, "^/health.*")
    // TODO: Add latency-based routing (P95 threshold)
    // TODO: Add error-based routing (100% capture on 5xx)
    .build()
```

### 4. 컨텍스트 전파 (Context Propagation)

| Scenario | Mechanism | Example |
|----------|-----------|---------|
| HTTP Client | W3C TraceContext headers | `traceparent: 00-abcdef...` |
| Kafka Producer | Record headers | `headers["trace-context"]` |
| Kafka Consumer | Record headers → Span context | Propagation via `@KafkaListener` |
| Async (Virtual Thread) | `Context.current().makeCurrent()` | Executor wrapper에서 전파 |

**HTTP Client 예시:**
```kotlin
// WebClient에 자동 전파 (Spring Boot 3.x 자동 지원)
// RestTemplate에 수동 전파 필요
val executor = Context.current().makeCurrent().wrap { runnable ->
    virtualThreadExecutor.submit(runnable)
}
```

### 5. 민감 정보 마스킹 (PII Masking)

**필수 유틸리티 사용:**
```kotlin
// module-common/.../StringMaskingUtils.kt
StringMaskingUtils.maskOcid(ocid)          // "abcd1234" → "abcd***"
StringMaskingUtils.maskApiKey(apiKey)       // "key123" → "***"
StringMaskingUtils.maskCacheKey(cacheKey)   // "expectation:v3:ocid:..." → "expectation:v3:***:..."
```

### 6. 강제 메커니즘 (Enforcement)

| Rule | Mechanism | Target |
|------|-----------|--------|
| Required spans | ArchUnit test | `@WithSpan` in Controller/Adapter |
| PII masking | Code review checklist | PR template 필수 체크 |
| Sampling config | CI verification | `application.yml` check |

**ArchUnit 예시:**
```kotlin
@ArchTest
val controllersMustHaveWithSpan = ArchRuleDefinition.classes()
    .that().resideInAPackage("..controller..")
    .and().areAnnotatedWith(Controller::class.java)
    .should().beAnnotatedWith(WithSpan::class.java)
```

## 결과 (Consequences)

### 긍정적 영향

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| 외부 API 가시성 | Nexon API 호출 추적 불가 | Span으로 latency 분석 가능 |
| 분산 추적 | 단일 서버 내 span만 존재 | Kafka → DB → Redis 전체 경로 추적 |
| PII 보호 | OCID가 trace에 포함될 위험 | `maskOcid()`로 강제 마스킹 |
| 샘플링 효율 | 5% 일률 적용 (오류 놓침) | Error/P95 기반 100% capture |

### 부정적 영향

| 항목 | 영향 | 완화 방안 |
|------|------|---------|
| 코드 복잡도 | 모든 Port/Adapter에 `@WithSpan` 추가 | ArchUnit으로 누락 방지 |
| 오버헤드 | Span 생성으로 미세한 latency 증가 | 5% 샘플링으로 영향 최소화 |
| 저장소 비용 | 100% error capture로 데이터 증가 | Retention policy (7일) 적용 |

### 마이그레이션 경로

1. **Phase 1**: OpenTelemetry 활성화 (`management.tracing.enabled=true`)
2. **Phase 2**: Controller/Adapter에 `@WithSpan` 추가
3. **Phase 3**: Kafka trace context 전파 구현
4. **Phase 4**: ArchUnit 테스트 추가 (강제 메커니즘)

## 이력 (History)

| 날짜 | 변경 내용 | 작성자 |
|------|---------|--------|
| 2025-03-15 | 초안 작성 | Claude (Sonnet 4.6) |

## 참조 (References)

### 관련 문서
- [OpenTelemetry Specification: Trace](https://opentelemetry.io/docs/reference/specification/trace/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Spring Boot Micrometer Tracing](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.observability.tracing)

### 구현 파일
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/OpenTelemetryConfig.kt`
- `module-common/src/main/kotlin/maple/expectation/util/StringMaskingUtils.kt`
- `module-web/src/main/kotlin/maple/expectation/web/controller/CharacterController.kt`

### 관련 Issue
- Issue #515: Observability Enhancement

### SemConv 참조
- [HTTP SemConv](https://opentelemetry.io/docs/reference/specification/trace/semantic_conventions/http/)
- [Error SemConv](https://opentelemetry.io/docs/reference/specification/trace/semantic_conventions/exceptions/)
