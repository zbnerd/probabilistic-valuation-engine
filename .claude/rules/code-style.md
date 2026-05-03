---
paths:
  - "**/*.kt"
  - "**/*.java"
---

# 코드 스타일 컨벤션 (Code Style)

## 로깅

- Kotlin: `private val log = LoggerFactory.getLogger(ClassName::class.java)` (companion object 내)
- `@Slf4j` annotation 사용하지 않음 (이 코드베이스의 Kotlin 컨벤션)
- Top-level `private val log`도 허용
- IGN(캐릭터명)은 반드시 마스킹: `maskIgn(ign)` → `f***l` 형식
- 구조적 context 포함 (job ID, character ID 등)

## TaskContext 필수

- 모든 `LogicExecutor` 호출에 `TaskContext.of(component, operation[, dynamicValue])` 제공
- `component`, `operation`은 metric tag용 고정 taxonomy 값
- `dynamicValue`는 log 전용 (metric tag 아님, cardinality 제어)
- 예: `TaskContext.of("V5Query", "CacheFirstLookup", userIgn)`

## DTO & Entity 컨벤션

- Request/Response DTO: Kotlin `data class`, `module-web/.../dto/v{n}/`
- DTO에 비즈니스 로직 금지
- Domain → DTO 매핑: static mapper function (예: `CharacterViewMapper.toResponseDto()`)
- Entity → Domain: extension function `private fun EntityClass.toDomain() = DomainClass(...)`

## API Versioning

- Package 분리: `controller/v1/`, `controller/v4/`, `controller/v5/`
- URL: `/api/v{n}/...`
- Feature flag: `@ConditionalOnProperty(name = ["v{n}.enabled"])`

## Controller Async Return

- Controller 반환 타입: `CompletableFuture<ResponseEntity<*>>`
- `CompletableFuture.supplyAsync({ ... }, dedicatedExecutor)` 사용
- `.join()` 또는 `.get()`으로 request thread에서 block 금지

## Worker (PGMQ Consumer) 네이밍

- PGMQ queue consumer: `XxxWorker` (예: `ApiResponseWorker`, `CalculationWorker`)
- `topic.subscribe { envelope, _ -> handler(envelope) }` in `init {}`
- 성공 시 `ConsumeResult.Ack` 반환

## ObjectMapper / Jackson

- `ObjectMapper()` 직접 생성 금지 — Spring Boot의 auto-configured module(KotlinModule, JavaTimeModule 등) 상실
- Spring Boot builder 또는 primary ObjectMapper 주입 사용
- Cache `ValueWrapper`와 실제 cached value 구분 후 직렬화

## 중복 Bean/Type 방지

- `@Component`, `@Service`, `@Configuration`, `record/class` 생성 전 기존 동일 type/name 검색
- `lsp_workspace_symbols`로 중복 확인
- Kotlin/Java 공존 시 동일 FQN으로 인한 컴파일 실패 또는 runtime bean 충돌 주의
- **근거**: 중복 bean으로 인한 app 시작 실패 5건 (#398, #445, #569, #570)

## Property Key 일치

- `@Value("${...}")` 또는 `@ConditionalOnProperty` 추가 시 YAML의 정확한 property path 확인
- Property path는 compile time에 검증되지 않음 — 오타/nesting 차이 시 runtime 실패
- **근거**: property key 불일치 3건 (#335, #444, #614)

## ErrorCode Enum

- 모든 exception은 `BaseException` 상속, `ErrorCode` 필수
- ErrorCode는 enum으로 구현 (`code`, `message`, `statusCode`)
- raw `RuntimeException` + hardcoded string 금지
- `ClientBaseException` / `ServerBaseException` 구분
