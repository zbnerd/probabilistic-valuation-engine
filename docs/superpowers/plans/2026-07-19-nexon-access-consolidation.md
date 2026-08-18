# Nexon Access Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate Nexon Open API transport, timeout/body limits, credential handling, typed failures, redaction, and metrics in `module-nexon-client`, while keeping system bulk and user BYOK capacity isolated and preserving current endpoint/property contracts.

**Architecture:** A shared transport factory creates two separately owned Reactor Netty providers/clients. `SystemKeyNexonClient` returns raw bytes for external-api adapters; `ByokNexonClient` decodes a neutral character-list model. Endpoint-aware failure classification is shared. External-api maps the neutral types/outcomes; module-infra retains only bounded app/web compatibility adapters.

**Tech Stack:** Kotlin/JDK 21, Gradle Groovy DSL, Spring WebFlux, Reactor Netty, Jackson, Micrometer, Spring Boot configuration properties/validation, JUnit 5, AssertJ, Mockito-Kotlin, JDK local HTTP test server.

**Spec:** `docs/superpowers/specs/2026-07-19-nexon-access-consolidation-design.md`

**Depends on:** `2026-07-19-kafka-delivery-outcome.md` through Task 6 so auth can return typed delivery outcomes and use secret-safe DLT handling.

## Global Constraints

- Preserve Nexon endpoint paths/query names, `VALUES_ONLY` encoding, `Accept: application/json`, and current event/response DTO schemas.
- Preserve system property/environment names under `nexon.http-client.*` / `NEXON_HTTP_*`. Add BYOK settings only under `nexon.byok-http-client.*`. Map legacy `nexon.api.connect-timeout` and `response-timeout` in the infra facade.
- Keep separate providers: system defaults `250/1000/5s`; BYOK defaults `32/128/2s`. Both keep connect `3s`, response `5s`, call ceiling `10s`; body caps are system `2 MiB`, BYOK `256 KiB`.
- Never store a BYOK value in a client field, cache, context, metric, trace, exception, or log. Pass it only into the request-header lexical scope.
- Never log raw response/error bodies, raw query identifiers, character names, OCIDs, or API keys. Failure types may carry only status, sanitized Nexon code, bounded reason, and retry-after.
- `OPENAPI00004` with `Data not found` is `NotFound`, not `InvalidCredential`. Unknown 4xx is never collapsed into invalid credential.
- No `Optional.empty`/default result may hide timeout, 429, 5xx, pool-acquire, body-limit, or decode failure in active ETL.
- Do not add Testcontainers. HTTP tests use an in-process JDK/Reactor stub and controllable latches/clock without `Thread.sleep` or coroutine `delay`.
- Do not add `join`, blocking `get`, or `runBlocking`. The pre-existing synchronous app facade remains bounded and isolated; active ETL paths are completion-stage based.
- Preserve before/after bulk/auth throughput, latency, pool active/idle/pending, acquire timeouts, body bytes, and failures in `docs/05_Reports/2026-07-19-nexon-access-consolidation-evidence.md`.

---

## Task 1: Characterize current HTTP behavior, write ADR-748, and scaffold the module

**Files:**

- Create: `docs/01_ADR/ADR-748-nexon-client-ownership.md`
- Create: `docs/05_Reports/2026-07-19-nexon-access-consolidation-evidence.md`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientCharacterizationTest.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/external/impl/RealNexonAuthClientCharacterizationTest.kt`
- Modify: `settings.gradle`
- Create: `module-nexon-client/build.gradle`

**Interfaces:**

- Consumes: both current HTTP clients/configurations and known Nexon fixtures.
- Produces: frozen encoding/timeout/status/body behavior, an accepted module decision, and a compilable new library.

- [ ] **Step 1: Freeze system and BYOK behavior with local HTTP fixtures**

Characterization must cover:

- Korean character name query encoding and each current endpoint/query;
- exactly one `x-nxopen-api-key` header and one `Accept` header;
- connect/response/call timeouts;
- system 2 MiB body cap;
- current handling of 400 `OPENAPI00004`, unknown 4xx, 401/403, 429, 5xx, malformed success body;
- current system pool defaults and current unpooled BYOK behavior.

Add an evidence-only method to each characterization class, enabled by `NEXON_EVIDENCE_ENABLED=1`, that uses the same in-process stub and no wall-clock sleeps. The system corpus is 500 warmup plus 5,000 measured 2-KiB successes at concurrency 128. The BYOK corpus is 200 warmup plus 1,000 measured 2-KiB successes at concurrency 32 using one fixed synthetic key that is asserted absent from output. Run five measured repetitions, report each repetition plus median throughput and p50/p95/p99 latency, and write machine-readable JSON under each module's `build/reports/nexon-evidence/`. The stub also serves separate fixed 64-KiB, 429, malformed, and over-limit responses for classification counts; those calls are excluded from throughput. Tests coordinate start/completion with latches and futures, not elapsed-time sleeps.

Capture current defects as named expectations rather than approving them: BYOK transient failure collapse, raw error-body logging, and external raw URI metric mapping are migration targets.

Run:

```bash
./gradlew :module-external-api:test --tests '*NexonExternalApiClientCharacterizationTest'
./gradlew :module-infra:test --tests '*RealNexonAuthClientCharacterizationTest'
```

Expected: tests pass against current code and document both desired compatibility and known unsafe behavior.

- [ ] **Step 2: Record runtime/config and fixed-corpus baseline**

Run:

```bash
NEXON_EVIDENCE_ENABLED=1 ./gradlew :module-external-api:test \
  --tests '*NexonExternalApiClientCharacterizationTest' --rerun-tasks
NEXON_EVIDENCE_ENABLED=1 ./gradlew :module-infra:test \
  --tests '*RealNexonAuthClientCharacterizationTest' --rerun-tasks
sha256sum \
  module-external-api/build/reports/nexon-evidence/*.json \
  module-infra/build/reports/nexon-evidence/*.json
```

Record the fixed corpus definition, resolved `nexon.http-client` values without the API key, connection-provider metrics, bulk request count/duration/body bytes, auth latency/failure classification, both module runtime classpaths, commit/JDK/CPU/JVM options, both exit codes, and the JSON hashes. Redact request query and response bodies.

- [ ] **Step 3: Create ADR-748**

Use the five-section ADR format and this decision:

```markdown
Create module-nexon-client with one shared transport policy and endpoint-aware failure taxonomy. Create independent SYSTEM_BULK and USER_BYOK ConnectionProviders/clients. Active ETL uses asynchronous typed clients; module-infra keeps only app/web compatibility mapping. API keys and raw error bodies are never observable data.
```

- [ ] **Step 4: Add the module**

Add `include 'module-nexon-client'` to `settings.gradle` and create:

```groovy
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation project(':module-common')
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.spring.boot)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
}

tasks.named('jar') {
    enabled = true
    archiveClassifier = 'plain'
}
```

- [ ] **Step 5: Verify and commit baseline/scaffold**

Run: `./gradlew :module-nexon-client:compileKotlin :module-nexon-client:compileJava --continue`

Expected: `BUILD SUCCESSFUL`; no dependency on module-infra or module-external-api.

```bash
git add settings.gradle module-nexon-client docs/01_ADR/ADR-748-nexon-client-ownership.md docs/05_Reports/2026-07-19-nexon-access-consolidation-evidence.md module-external-api/src/test/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientCharacterizationTest.kt module-infra/src/test/kotlin/maple/expectation/infrastructure/external/impl/RealNexonAuthClientCharacterizationTest.kt
git commit -m "build: add Nexon client module"
```

---

## Task 2: Define endpoint-aware requests, neutral models, and typed failures

**Files:**

- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/model/NexonEndpointPurpose.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/model/NexonRequest.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/byok/NexonCharacterList.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/failure/NexonFailure.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/failure/NexonFailureClassifier.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/failure/NexonErrorEnvelope.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/failure/NexonFailureClassifierTest.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/byok/NexonCharacterListTest.kt`

**Interfaces:**

- Consumes: endpoint purpose, HTTP status, bounded error JSON, transport cause, and retry-after.
- Produces: a neutral request/model and one sealed sanitized failure.

- [ ] **Step 1: Write the failing classifier matrix**

Use literal fixtures for:

```text
CHARACTER_LIST + 401/403                 => InvalidCredential
OCID_LOOKUP + 400 + OPENAPI00004         => NotFound
CHARACTER_LIST + OPENAPI00004 Data not found => NotFound
unknown 4xx                              => InvalidRequest
429 + Retry-After                        => RateLimited(retryAfter)
5xx/network unavailable                  => UpstreamUnavailable
connect/response/call timeout            => Timeout(kind)
body over profile cap                    => ResponseTooLarge
2xx malformed expected DTO               => DecodeFailure
```

Assert no failure `message`, `toString`, or property includes raw body, query value, or a supplied API key.

Run: `./gradlew :module-nexon-client:test --tests '*NexonFailureClassifierTest'`

Expected: compilation fails because failures do not exist.

- [ ] **Step 2: Implement neutral request/model types**

```kotlin
enum class NexonEndpointPurpose {
    OCID_LOOKUP,
    CHARACTER_BASIC,
    ITEM_EQUIPMENT,
    RANKING_OVERALL,
    CUBE_HISTORY,
    CHARACTER_LIST,
}

data class NexonRequest(
    val purpose: NexonEndpointPurpose,
    val path: String,
    val query: Map<String, String>,
    val endpointTemplate: String,
) {
    init {
        require(path.startsWith('/') && endpointTemplate.startsWith('/'))
    }
}
```

```kotlin
data class NexonCharacterList(val accounts: List<NexonAccount>) {
    val characters: List<NexonCharacter> = accounts.flatMap(NexonAccount::characters)
}

data class NexonAccount(val accountId: String?, val characters: List<NexonCharacter>)

data class NexonCharacter(
    val ocid: String?,
    val characterName: String?,
    val worldName: String?,
    val characterClass: String?,
    val characterLevel: Int,
)
```

Keep Jackson wire classes private to the BYOK decoder and map nullable lists to empty immutable lists. The neutral model imports no infra DTO or external-api domain.

- [ ] **Step 3: Implement sanitized sealed failures**

`NexonFailure` extends `RuntimeException` with a constant/bounded message. It stores only status, sanitized Nexon code, timeout kind, retry-after, endpoint purpose, and endpoint template. It does not attach a raw `WebClientResponseException`, Reactor Netty exception, decoder exception, URI, response body, or user key as its cause because exception-chain rendering can expose those values. The classifier converts the raw failure at the transport boundary and records only the typed category plus static endpoint/provider tags. Subtypes are `InvalidCredential`, `NotFound`, `InvalidRequest`, `RateLimited`, `Timeout`, `UpstreamUnavailable`, `ResponseTooLarge`, and `DecodeFailure`.

Classifier input includes endpoint purpose; code `OPENAPI00004` maps to `NotFound` for current supported purposes. Unknown 4xx maps to `InvalidRequest`. Only sanitized Nexon code and status are retained.

- [ ] **Step 4: Verify and commit contracts**

Run:

```bash
./gradlew :module-nexon-client:test --tests '*NexonFailureClassifierTest' --tests '*NexonCharacterListTest'
```

Expected: all tests pass and secret-scan assertions find no supplied key/body.

```bash
git add module-nexon-client
git commit -m "feat: define typed Nexon failures"
```

---

## Task 3: Build two validated, isolated, deterministically disposed transport profiles

**Files:**

- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/config/SystemNexonClientProperties.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/config/ByokNexonClientProperties.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/config/LegacyNexonApiProperties.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/config/NexonClientProfile.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/transport/NexonTransport.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/transport/NexonTransportFactory.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/transport/NexonTransportResources.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/config/NexonClientAutoConfiguration.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/metrics/NexonClientMetrics.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/config/NexonClientPropertiesTest.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/transport/NexonTransportTest.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/transport/NexonTransportResourcesTest.kt`

**Interfaces:**

- Consumes: one profile, validated properties, request, and API key argument scoped to one exchange.
- Produces: raw response bytes or a typed failure on a profile-isolated completion stage.

- [ ] **Step 1: Write failing property/pool/transport tests**

Test exact defaults, positive/bounded validation, distinct pool-name validation, `VALUES_ONLY` Korean query encoding, one key header, connect/response/call timeouts, both body caps, URI metric normalization, system-pool saturation not blocking BYOK, and async lifecycle callback after both providers dispose.

Run:

```bash
./gradlew :module-nexon-client:test --tests '*NexonClientPropertiesTest' --tests '*NexonTransportTest' --tests '*NexonTransportResourcesTest'
```

Expected: compilation fails because configuration/transport types do not exist.

- [ ] **Step 2: Implement exact property defaults**

`SystemNexonClientProperties` binds `nexon.http-client` and keeps the existing suffixes so current YAML and `NEXON_HTTP_*` variables bind unchanged:

```kotlin
poolName = "nexon-pool"
maxConnections = 250
pendingAcquireMaxCount = 1000
pendingAcquireTimeoutMs = 5000
connectTimeoutMs = 3000
responseTimeoutSeconds = 5
callTimeoutSeconds = 10
maxInMemorySizeBytes = 2 * 1024 * 1024
metricsEnabled = true
```

`ByokNexonClientProperties` binds `nexon.byok-http-client` with the same field suffix convention and defaults to `poolName=nexon-byok-pool`, `maxConnections=32`, `pendingAcquireMaxCount=128`, `pendingAcquireTimeoutMs=2000`, `connectTimeoutMs=3000`, `responseTimeoutSeconds=5`, `callTimeoutSeconds=10`, and `maxInMemorySizeBytes=256 * 1024`. Validate every count/duration/cap and fail boot when pool names match.

`LegacyNexonApiProperties` binds only nullable `connectTimeout` and `responseTimeout` under `nexon.api`; it has no defaults and never binds `nexon.api.key`. When either legacy value is explicitly present, `NexonClientAutoConfiguration` maps it to both effective system and BYOK timeout values before creating transports. If both legacy and new timeout keys are explicitly present, the new `nexon.http-client.*` / `nexon.byok-http-client.*` value wins; test precedence with `ApplicationContextRunner` and `Environment.containsProperty`.

- [ ] **Step 3: Implement one transport-construction path**

`NexonTransportFactory.create(profile, properties)` is the only place containing the Nexon base URL, `DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY`, `ConnectionProvider.builder`, `HttpClient`, `ReactorClientHttpConnector`, WebClient codecs, compression, timeout, Accept header, and URI mapper.

`NexonClientAutoConfiguration` exposes the factory's system `WebClient` as the qualified bean `nexonSystemWebClient` solely for the infra compatibility alias; active external-api code injects `SystemKeyNexonClient`, not the raw client. It exposes no BYOK raw `WebClient` bean, so user-key calls remain constrained to `ByokNexonClient`/`NexonTransport.exchange`.

The request method must have this shape so the BYOK value is not retained:

```kotlin
fun exchange(request: NexonRequest, apiKey: String): CompletableFuture<ByteArray>
```

Build `.header("x-nxopen-api-key", apiKey)` inside that method. Do not assign the key to a transport/client property or include it in Reactor context. Apply call timeout after response/body decoding and map failures through `NexonFailureClassifier`.

- [ ] **Step 4: Own provider lifecycle asynchronously**

`NexonTransportResources` implements `SmartLifecycle`. `stop(callback)` composes both providers' `disposeLater()`, applies a bounded shutdown timeout, records failure, clears owned references, and invokes the Spring callback exactly once. It must not block using `get`/`join`.

- [ ] **Step 5: Verify profile isolation and commit**

Run:

```bash
./gradlew :module-nexon-client:test --tests '*NexonClientPropertiesTest' --tests '*NexonTransportTest' --tests '*NexonTransportResourcesTest'
```

Expected: all tests pass; system and BYOK provider instances/names differ, and saturation in one test leaves the other able to acquire.

```bash
git add module-nexon-client
git commit -m "feat: isolate Nexon transport profiles"
```

---

## Task 4: Migrate external-api system-key bulk/urgent access

**Files:**

- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/system/SystemKeyNexonClient.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/system/SystemKeyNexonClientTest.kt`
- Modify: `module-external-api/build.gradle`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/domain/ExternalApiProvider.kt`
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/config/NexonHttpClientProperties.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientCharacterizationTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumerTest.kt`

**Interfaces:**

- Consumes: current `ExternalApiProvider`, `ExternalApiEndpoint`, request key, and service credential.
- Produces: the same raw byte completion stage with typed failure and unchanged endpoint/query behavior.

- [ ] **Step 1: Write failing adapter parity tests against the new client**

Assert exact request mapping for OCID, basic, equipment, ranking date/page, Korean name encoding, raw byte equality, existing `SnapshotFetchMetrics` observation, and typed timeout/429/5xx propagation.

Run:

```bash
./gradlew :module-nexon-client:test --tests '*SystemKeyNexonClientTest'
./gradlew :module-external-api:test --tests '*NexonExternalApiClientCharacterizationTest'
```

Expected: tests fail because `SystemKeyNexonClient`/adapter delegation do not exist.

- [ ] **Step 2: Implement the raw system client**

```kotlin
class SystemKeyNexonClient(private val transport: NexonTransport) {
    fun fetch(request: NexonRequest, systemApiKey: String): CompletableFuture<ByteArray> =
        transport.exchange(request, systemApiKey)
}
```

The module does not know `ExternalApiEndpoint`; external-api owns a total mapping from its enum/key types to `NexonRequest`.

- [ ] **Step 3: Make the external adapter thin**

Delete its local `ConnectionProvider`, `HttpClient`, `WebClient`, base URL, timeout, body-limit, status, and raw-error logging code. Inject `SystemKeyNexonClient`; retain only endpoint mapping, existing workload metrics, and the service key field. Return the client's future unchanged after metric instrumentation. Remove the unused `baseUrl` constructor property/literal from `ExternalApiProvider`; it becomes the single enum value `NEXON` with no transport configuration.

Import `NexonClientAutoConfiguration` from `ExternalApiApplication` and let it enable the two new property types. Delete `NexonHttpClientProperties` after `rg` proves only the new `SystemNexonClientProperties` remains. Existing YAML/env names do not change because Step 2 retained the old system field suffixes.

- [ ] **Step 4: Verify system/urgent behavior**

Run:

```bash
./gradlew :module-nexon-client:test --tests '*SystemKeyNexonClientTest'
./gradlew :module-external-api:test --tests '*NexonExternalApiClientCharacterizationTest' --tests '*UrgentCharacterRequestConsumerTest' --tests '*DataflowContractTest'
```

Expected: all tests pass; raw response bytes/query fixtures are unchanged and no raw body is logged on failure.

- [ ] **Step 5: Commit system-client migration**

```bash
git add module-nexon-client module-external-api
git commit -m "refactor: use shared Nexon system client"
```

---

## Task 5: Implement BYOK character-list decoding and key-lifetime/redaction guarantees

**Files:**

- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/byok/ByokNexonClient.kt`
- Create: `module-nexon-client/src/main/kotlin/maple/nexon/client/byok/CharacterListDecoder.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/byok/ByokNexonClientTest.kt`
- Create: `module-nexon-client/src/test/kotlin/maple/nexon/client/security/NexonCredentialRedactionTest.kt`

**Interfaces:**

- Consumes: a method-local BYOK string.
- Produces: `CompletableFuture<NexonCharacterList>` or typed failure; no retained credential reference.

- [ ] **Step 1: Write failing BYOK behavior/security tests**

Cover populated list, valid empty list, explicit null lists normalized to empty, invalid credential, `OPENAPI00004` not found, unknown 4xx, 429, timeouts, 5xx, over-256-KiB, and malformed 2xx. Inspect client fields/owned object graph after completion and captured logs/exceptions/metrics for the exact test API key.

Run:

```bash
./gradlew :module-nexon-client:test --tests '*ByokNexonClientTest' --tests '*NexonCredentialRedactionTest'
```

Expected: compilation fails because BYOK client/decoder do not exist.

- [ ] **Step 2: Implement async typed BYOK access**

```kotlin
class ByokNexonClient(
    private val transport: NexonTransport,
    private val decoder: CharacterListDecoder,
) {
    fun getCharacterList(apiKey: String): CompletableFuture<NexonCharacterList> =
        transport.exchange(CHARACTER_LIST_REQUEST, apiKey)
            .thenApply(decoder::decode)
}
```

`CHARACTER_LIST_REQUEST` contains only endpoint metadata. `CharacterListDecoder` maps a private Jackson wire DTO to the neutral model and throws sanitized `DecodeFailure`; it never includes response text in the exception.

- [ ] **Step 3: Prove key lifetime and redaction**

The client, transport, requests, resources, decoder, and returned model must have no field containing the BYOK string. Test success and every failure subtype. Captured logs and exception chains must not contain the key or raw body.

- [ ] **Step 4: Verify and commit BYOK client**

Run:

```bash
./gradlew :module-nexon-client:test --tests '*ByokNexonClientTest' --tests '*NexonCredentialRedactionTest'
```

Expected: all tests pass; a successful empty list is distinct from `InvalidCredential` and `NotFound`.

```bash
git add module-nexon-client
git commit -m "feat: add typed Nexon BYOK client"
```

---

## Task 6: Switch auth delivery to typed Nexon outcomes and remove unused OCID injection

**Files:**

- Delete: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchHandler.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/messaging/ExternalApiSubscriptions.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/auth/AuthCharacterFetchHandlerTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/dataflow/DataflowContractTest.kt`

**Interfaces:**

- Consumes: `CharacterFetchRequest`, `ByokNexonClient`, and an awaitable response publisher.
- Produces: delivery outcome only after the correct success/error response send completes.

- [ ] **Step 1: Write failing typed-outcome/send-order tests**

Use controllable response-send futures. Assert:

```text
character list success     => success response send completes, then Success
InvalidCredential          => existing invalid-key response completes, then Success
NotFound                   => no-accessible-character response completes, then Success
RateLimited/Timeout/5xx    => Retryable, no terminal response/ACK
ResponseTooLarge/Decode    => Retryable for bounded delivery/DLT policy
unknown invalid request    => InvalidMessage
serialization/send failure => Retryable
```

Run: `./gradlew :module-external-api:test --tests '*AuthCharacterFetchHandlerTest'`

Expected: current consumer fails ordering/taxonomy expectations because it collapses errors and publishes fire-and-forget.

- [ ] **Step 2: Implement neutral-model mapping**

Map `NexonCharacterList.accounts.firstOrNull()?.accountId` and all nonblank name/OCID pairs to the existing `CharacterFetchResponse`. Keep the event schema and Kafka key unchanged. A valid HTTP character-list response with empty accounts/characters is a successful response with `accountId=null` and an empty map. A typed Nexon `NotFound` produces the existing no-accessible-character failure response and is distinct from invalid credential.

- [ ] **Step 3: Return delivery outcomes after send completion**

The handler composes `ByokNexonClient.getCharacterList` and `publishResponse`. It pattern-matches the unwrapped typed failure exactly as the matrix states. It never incorporates `ex.message` into a user response or log when the cause may hold sensitive transport data.

- [ ] **Step 4: Remove unused `NexonAuthClient` from OCID phase**

Delete the constructor field/import/future-use comment and update every test construction. OCID lookup continues through the system raw client path.

- [ ] **Step 5: Verify auth/dataflow and commit**

Run:

```bash
./gradlew :module-external-api:test --tests '*AuthCharacterFetchHandlerTest' --tests '*OcidLookupPhaseTest' --tests '*DataflowContractTest' --tests '*ExternalApiSubscriptionsTest'
```

Expected: all tests pass; timeout/429/5xx reach messaging retry, and response send completion precedes `Success`.

```bash
git add module-external-api
git commit -m "fix: preserve typed Nexon auth failures"
```

---

## Task 7: Reduce infra to compatibility adapters, remove duplication, and verify runtime

**Files:**

- Modify: `module-infra/build.gradle`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/MaplestoryApiConfig.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/RealNexonApiClient.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/RealNexonAuthClient.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/NexonAuthClient.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/NexonCharacterListMapper.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/external/impl/NexonCompatibilityAdapterTest.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`
- Modify: `docs/05_Reports/2026-07-19-nexon-access-consolidation-evidence.md`

**Interfaces:**

- Consumes: old app/web `NexonApiClient`/`NexonAuthClient` APIs and shared new transport/client types.
- Produces: stable legacy DTOs/bean names with no duplicated transport construction; active external-api has no infra Nexon imports.

- [ ] **Step 1: Write failing compatibility/property tests**

Assert old bean names/types, every legacy endpoint DTO mapping, character-list nullable-field behavior, `nexon.api` timeout mapping, known app exceptions, and absence of `validateApiKey` callers. Assert transient failures are not turned into `Optional.empty` by `LogicExecutor`.

Run: `./gradlew :module-infra:test --tests '*NexonCompatibilityAdapterTest'`

Expected: tests fail until old implementations delegate/classify through the new module.

- [ ] **Step 2: Add the new dependency and shared construction**

Add `implementation project(':module-nexon-client')` to `module-infra`. `MaplestoryApiConfig` becomes a compatibility import/factory over `NexonClientAutoConfiguration`; it contains no base URL, `HttpClient`, `ConnectionProvider`, encoding mode, timeout, body cap, or status classifier. The new module's nullable `LegacyNexonApiProperties` performs the existing `nexon.api.connect-timeout`/`response-timeout` mapping with the tested new-key precedence. Preserve the `mapleWebClient` bean required by excluded app/web-only `NexonDataCollector` and `DiscordAlertService` as a direct alias of the new module's qualified `nexonSystemWebClient`; do not build a second client.

- [ ] **Step 3: Map legacy clients without changing app/web DTOs**

`RealNexonApiClient` delegates each request to `SystemKeyNexonClient`, then maps raw bytes to current DTOs. `RealNexonAuthClient` injects `ByokNexonClient` (never a raw transport/WebClient) and uses `NexonCharacterListMapper` to return the old `Optional<CharacterListResponse>` contract: `InvalidCredential`, `NotFound`, `InvalidRequest`, and a valid empty account list map to `Optional.empty`; populated success maps to `Optional.of`; `RateLimited`, `Timeout`, `UpstreamUnavailable`, `ResponseTooLarge`, and `DecodeFailure` propagate as typed runtime failures.

Preserve the existing synchronous app boundary with `Mono.fromFuture(byokNexonClient.getCharacterList(apiKey)).block(effectiveCallTimeout.plusMillis(250))`; do not use `Future.get`/`join`, and do not add a second HTTP transport. The extra 250 ms is only the facade completion margin after the transport-owned call ceiling. A facade timeout becomes sanitized `Timeout(CALL)`, a null completion becomes sanitized `DecodeFailure`, and transient failures never become empty.

Remove `validateApiKey` from `NexonAuthClient` and its implementation after `rg -n 'validateApiKey\('` proves there are no callers outside the declaration/implementation. Do not refactor app/web ownership services in this program.

- [ ] **Step 4: Remove duplicate and unsafe code**

Delete local Nexon base URL/builders from external/infra, raw response-body logs, raw URI metric mapper, and `LogicExecutor` defaulting in auth. A repository scan must find one base URL and one transport builder, both under `module-nexon-client`.

- [ ] **Step 5: Run compatibility, active-service, and architecture tests**

Run:

```bash
./gradlew :module-nexon-client:test
./gradlew :module-external-api:test
./gradlew :module-infra:test --tests '*NexonCompatibilityAdapterTest' --tests '*RealNexonAuthClientCharacterizationTest'
./gradlew :module-app:test --tests '*ApiKeyValidatorTest'
./gradlew compileKotlin compileJava --continue
rg -n 'https://open\.api\.nexon\.com|ConnectionProvider\.builder|DefaultUriBuilderFactory' module-nexon-client/src/main module-external-api/src/main module-infra/src/main
```

Expected: tests/compile pass; every production construction/base URL match printed by this source-only scan is under the new module.

- [ ] **Step 6: Run non-destructive external-api runtime verification**

First run the deterministic local-stub suites; do not send a live Nexon request in this plan:

```bash
./gradlew :module-nexon-client:test --tests '*NexonTransportTest' --tests '*ByokNexonClientTest' --tests '*NexonCredentialRedactionTest'
./gradlew :module-external-api:test --tests '*AuthCharacterFetchHandlerTest' --tests '*ExternalApiSubscriptionsTest'
```

Then start external-api with the current environment and existing Docker dependencies, verify port `8081` health, provider names/capacities, no duplicate client beans, and deterministic provider shutdown. Verify auth invalid/not-found/transient outcomes and DLT redaction through the deterministic component suites above; the runtime smoke is construction/health/shutdown only and must not log key/query/body.

Expected: `UP`; system and BYOK metrics show separate providers and transient auth failures remain retryable.

- [ ] **Step 7: Capture after performance/security evidence**

Run the same `NEXON_EVIDENCE_ENABLED=1` characterization commands from Task 1 with the unchanged stub corpus, concurrency, five-repetition protocol, JVM options, and output schema. Record throughput/latency/pool metrics, timeout/body-limit classifications, provider disposal, JSON hashes, and full secret-scan results. Compare medians and every p95/p99 rather than a single fastest repetition. A material regression or any key/body exposure blocks completion.

- [ ] **Step 8: Commit compatibility/evidence**

```bash
git add module-infra module-external-api docs/05_Reports/2026-07-19-nexon-access-consolidation-evidence.md
git commit -m "refactor: consolidate Nexon access policy"
```

## Plan Completion Gate

- [ ] `git diff --check` is clean.
- [ ] `rg -n 'maple\.expectation\.infrastructure\.(config\.MaplestoryApiConfig|external\.)' module-external-api/src/main` returns no matches.
- [ ] Base URL, transport construction, timeouts/body limits, and failure classification each have one production implementation under `module-nexon-client`.
- [ ] Separate-pool, failure taxonomy, key lifetime, redaction, send-before-success, and legacy compatibility tests pass.
- [ ] `OPENAPI00004` is never treated as invalid credential in the approved fixtures.
- [ ] Before/after throughput/pool/security evidence is recorded without secrets.
