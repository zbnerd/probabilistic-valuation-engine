# External-API ArtifactStore/Cleanup null→예외 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `module-external-api`의 `LocalExternalApiArtifactStoreAdapter.read` nullable contract와 `ConsumedChunkCleanupScheduler.deleteFile` boolean swallow를 명시적 예외로 변환하여 EPIC-2 silent data loss 제거.

**Architecture:**
- Port 시그니처 hard break: `read(): ByteArray?` → `read(): ByteArray` + `ArtifactNotFoundException` throw
- 신규 exception `ArtifactNotFoundException`은 `module-common`에 `ServerBaseException` 상속으로 추가
- `deleteFile`은 `Files.deleteIfExists`가 `false` 반환 (이미 없음)만 `false` 유지, `IOException`/`SecurityException`은 throw

**Tech Stack:** Kotlin, JUnit5, `@TempDir`, AssertJ, Mockito Kotlin, Gradle

---

## File Structure

### Create
- `module-common/src/main/kotlin/maple/expectation/error/exception/ArtifactNotFoundException.kt` — 신규 exception
- `module-external-api/src/test/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapterTest.kt` — adapter 단위 테스트
- `module-external-api/src/test/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupSchedulerDeleteFileTest.kt` — deleteFile 단위 테스트

### Modify
- `module-common/src/main/kotlin/maple/expectation/error/CommonErrorCode.kt` — `ARTIFACT_NOT_FOUND` enum 추가
- `module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiArtifactStorePort.kt` — read 반환 타입 변경
- `module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt` — read 구현 + exception 처리
- `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt` — deleteFile catch 분리

---

## Task 1: CommonErrorCode.ARTIFACT_NOT_FOUND 추가

**Files:**
- Modify: `module-common/src/main/kotlin/maple/expectation/error/CommonErrorCode.kt`

- [ ] **Step 1: enum 상수 추가**

`CommonErrorCode` 클래스 내 `S017 SYSTEM_ERROR` 다음 줄에 추가:

```kotlin
ARTIFACT_NOT_FOUND("S018", "아티팩트를 찾을 수 없습니다 (endpoint: %s, key: %s)", 500),
```

- [ ] **Step 2: 컴파일 검증**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-common:compileKotlin --continue
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add module-common/src/main/kotlin/maple/expectation/error/CommonErrorCode.kt
git commit -m "feat(common): add ARTIFACT_NOT_FOUND error code (S018) for #999"
```

---

## Task 2: ArtifactNotFoundException 신규

**Files:**
- Create: `module-common/src/main/kotlin/maple/expectation/error/exception/ArtifactNotFoundException.kt`

- [ ] **Step 1: 테스트 먼저 작성**

`module-common/src/test/kotlin/maple/expectation/error/exception/ArtifactNotFoundExceptionTest.kt` 신규:

```kotlin
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArtifactNotFoundExceptionTest {

    @Test
    fun `constructs with error code, cause, and varargs`() {
        val cause = RuntimeException("disk gone")
        val ex = ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, cause, "CHARACTER_BASIC", "abc123")

        assertThat(ex.errorCode).isEqualTo(CommonErrorCode.ARTIFACT_NOT_FOUND)
        assertThat(ex.cause).isSameAs(cause)
        assertThat(ex.message).contains("CHARACTER_BASIC").contains("abc123")
    }

    @Test
    fun `inherits from ServerBaseException`() {
        val ex = ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ITEM_EQUIPMENT", "xyz")
        assertThat(ex).isInstanceOf(maple.expectation.error.exception.base.ServerBaseException::class.java)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-common:test --tests "maple.expectation.error.exception.ArtifactNotFoundExceptionTest"
```

Expected: FAIL with "Unresolved reference: ArtifactNotFoundException"

- [ ] **Step 3: exception 클래스 작성**

```kotlin
@file:JvmName("ArtifactNotFoundException")

package maple.expectation.error.exception

import maple.expectation.error.ErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class ArtifactNotFoundException : ServerBaseException {

    constructor(errorCode: ErrorCode, vararg args: Any?) : super(errorCode, *args)

    constructor(errorCode: ErrorCode, cause: Throwable, vararg args: Any?) : super(
        errorCode,
        cause,
        *args,
    )
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-common:test --tests "maple.expectation.error.exception.ArtifactNotFoundExceptionTest"
```

Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add module-common/src/main/kotlin/maple/expectation/error/exception/ArtifactNotFoundException.kt
git add module-common/src/test/kotlin/maple/expectation/error/exception/ArtifactNotFoundExceptionTest.kt
git commit -m "feat(common): ArtifactNotFoundException for #999"
```

---

## Task 3: Port 시그니처 hard break + Adapter 동시 변경 (squash)

**Note:** 본 task는 (1) Port hard break, (2) Adapter read 구현, (3) 테스트 4건을 단일 PR에 동시 적용. TDD 학습용 red 단계는 git history에 보존하지 않음.

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiArtifactStorePort.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapterTest.kt`

- [ ] **Step 1: 테스트 4건 작성**

```kotlin
package maple.externalapi.infra.storage

import maple.externalapi.domain.ExternalApiEndpoint
import maple.expectation.error.exception.ArtifactNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LocalExternalApiArtifactStoreAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newAdapter(basePath: String) = LocalExternalApiArtifactStoreAdapter(basePath = basePath)

    @Test
    fun `read returns ByteArray for existing artifact`() {
        val adapter = newAdapter(tempDir.toString())
        val endpoint = ExternalApiEndpoint.CHARACTER_BASIC
        val key = "user-1"
        adapter.store(endpoint, key, "hello".toByteArray())

        val result = adapter.read(endpoint, key)

        assertThat(result).isNotNull()
        assertThat(result.toString(Charsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `read throws ArtifactNotFoundException for missing file`() {
        val adapter = newAdapter(tempDir.toString())

        assertThatThrownBy { adapter.read(ExternalApiEndpoint.CHARACTER_BASIC, "missing") }
            .isInstanceOf(ArtifactNotFoundException::class.java)
    }

    @Test
    fun `ArtifactNotFoundException carries NoSuchFileException as cause`() {
        val adapter = newAdapter(tempDir.toString())

        val ex = kotlin.runCatching {
            adapter.read(ExternalApiEndpoint.CHARACTER_BASIC, "missing")
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ArtifactNotFoundException::class.java)
        assertThat(ex!!.cause).isInstanceOf(java.nio.file.NoSuchFileException::class.java)
    }

    @Test
    fun `store then read round-trip preserves payload bytes`() {
        val adapter = newAdapter(tempDir.toString())
        val endpoint = ExternalApiEndpoint.ITEM_EQUIPMENT
        val key = "user-2"
        val payload = "binary-data".toByteArray()

        adapter.store(endpoint, key, payload)
        val result = adapter.read(endpoint, key)

        assertThat(result).isEqualTo(payload)
    }
}
```

- [ ] **Step 2: Port 시그니처 + Adapter read 본문 교체**

Port (`ExternalApiArtifactStorePort.kt:14-17`):
```kotlin
    fun read(
        endpoint: ExternalApiEndpoint,
        key: String,
    ): ByteArray
```

Adapter import 추가:
```kotlin
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.ArtifactNotFoundException
import java.nio.file.NoSuchFileException
```

Adapter read 본문 (`LocalExternalApiArtifactStoreAdapter.kt:54-61`):
```kotlin
    override fun read(
        endpoint: ExternalApiEndpoint,
        key: String,
    ): ByteArray {
        val filePath = resolvePath(endpoint, key)
        return try {
            GZIPInputStream(Files.readAllBytes(filePath).inputStream()).use { it.readAllBytes() }
        } catch (ex: NoSuchFileException) {
            log.warn("[ArtifactStore] artifact not found: endpoint={}, key={}", endpoint, key)
            throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, ex, endpoint.toString(), key)
        }
        // other IOException (permission, disk) propagates naturally — adapter does not swallow
    }
```

- [ ] **Step 3: 테스트 통과 확인**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-external-api:test --tests "maple.externalapi.infra.storage.LocalExternalApiArtifactStoreAdapterTest"
```

Expected: PASS (4 tests)

- [ ] **Step 4: 커밋 (단일, red 흔적 없음)**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiArtifactStorePort.kt
git add module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt
git add module-external-api/src/test/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapterTest.kt
git commit -m "feat(external-api): ArtifactStorePort.read throws ArtifactNotFoundException for #999"
```

---

## Task 4: ConsumedChunkCleanupScheduler.deleteFile — 실패 테스트

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupSchedulerDeleteFileTest.kt`

- [ ] **Step 1: deleteFile을 internal로 가시성 변경**

`module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt:93`:

```kotlin
    internal fun deleteFile(objectKey: String): Boolean {
```

- [ ] **Step 2: 테스트 작성**

```kotlin
package maple.externalapi.cleanup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConsumedChunkCleanupSchedulerDeleteFileTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newScheduler(): ConsumedChunkCleanupScheduler =
        ConsumedChunkCleanupScheduler(
            objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
            basePath = tempDir.toString(),
            maxPending = 100,
        )

    @Test
    fun `deleteFile returns true when file exists`() {
        val scheduler = newScheduler()
        val file = tempDir.resolve("chunk.json.gz")
        Files.write(file, "payload".toByteArray())

        assertThat(scheduler.deleteFile("chunk.json.gz")).isTrue()
    }

    @Test
    fun `deleteFile returns false when file is already gone (no exception)`() {
        val scheduler = newScheduler()

        assertThat(scheduler.deleteFile("not-there.json.gz")).isFalse()
    }

    @Test
    fun `deleteFile throws IOException when target is a directory`() {
        val scheduler = newScheduler()
        Files.createDirectory(tempDir.resolve("subdir.json.gz"))

        assertThatThrownBy { scheduler.deleteFile("subdir.json.gz") }
            .isInstanceOf(java.io.IOException::class.java)
    }
}
```

- [ ] **Step 3: 테스트 실패 확인 (deleteFile이 아직 모든 예외 swallow)**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-external-api:test --tests "maple.externalapi.cleanup.ConsumedChunkCleanupSchedulerDeleteFileTest"
```

Expected: FAIL on `deleteFile throws IOException` test (현재 `runCatching`이 swallow하여 통과 — FAIL은 `Boolean` 그대로 반환되어 `isInstanceOf IOException` 단언 실패)

---

## Task 5: ConsumedChunkCleanupScheduler.deleteFile — 구현

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt`

- [ ] **Step 1: import 추가 (필요시)**

`ConsumedChunkCleanupScheduler.kt` 상단:

```kotlin
import java.io.IOException
```

(미존재 시에만)

- [ ] **Step 2: deleteFile 본문 교체**

기존 `lines 93-106`:

```kotlin
    private fun deleteFile(objectKey: String): Boolean {
        val path = Paths.get(basePath, objectKey)
        return runCatching {
            val deleted = Files.deleteIfExists(path)
            if (deleted) {
                log.info("[ConsumedChunkCleanup] deleted: {}", objectKey)
            } else {
                log.debug("[ConsumedChunkCleanup] already gone: {}", objectKey)
            }
            deleted
        }.onFailure { ex ->
            log.warn("[ConsumedChunkCleanup] delete failed: {} - {}", objectKey, ex.message)
        }.getOrDefault(false)
    }
```

신규:

```kotlin
    internal fun deleteFile(objectKey: String): Boolean {
        val path = Paths.get(basePath, objectKey)
        return try {
            val deleted = Files.deleteIfExists(path)
            if (deleted) {
                log.info("[ConsumedChunkCleanup] deleted: {}", objectKey)
            } else {
                log.debug("[ConsumedChunkCleanup] already gone: {}", objectKey)
            }
            deleted
        } catch (ex: IOException) {
            log.error("[ConsumedChunkCleanup] delete failed (IO): {} - {}", objectKey, ex.message, ex)
            throw ex
        } catch (ex: SecurityException) {
            log.error("[ConsumedChunkCleanup] delete failed (security): {} - {}", objectKey, ex.message, ex)
            throw ex
        }
    }
```

- [ ] **Step 3: 테스트 통과 확인**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-external-api:test --tests "maple.externalapi.cleanup.ConsumedChunkCleanupSchedulerDeleteFileTest"
```

Expected: PASS (3 tests)

- [ ] **Step 4: 커밋**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt
git add module-external-api/src/test/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupSchedulerDeleteFileTest.kt
git commit -m "feat(external-api): ConsumedChunkCleanupScheduler.deleteFile propagates IO/Security exceptions for #999"
```

---

## Task 6: 전체 검증

- [ ] **Step 1: 외부 API 모듈 전체 컴파일**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-external-api:compileKotlin compileJava :module-common:compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 영향받는 모듈 테스트 실행**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-external-api:test :module-common:test
```

Expected: PASS (모든 테스트 통과, 신규 9건 포함 — exception 2 + adapter 4 + delete 3)

- [ ] **Step 3: 서버 런타임 검증 (workflow-rules.md 준수)**

```bash
cd /home/maple/probabilistic-valuation-engine
set -a && source .env && set +a
./gradlew :module-external-api:bootRun &
BOOT_PID=$!
sleep 30
curl -s -w "\nHTTP %{http_code}" "http://localhost:8081/actuator/health" || echo "health check failed"
kill $BOOT_PID 2>/dev/null
```

Expected: HTTP 200, ERROR 로그 없음

- [ ] **Step 4: 커밋 (검증 결과 기록)**

```bash
git add -A
git diff --cached --quiet || git commit -m "chore(#999): verification log"
```

(변경 없으면 커밋 스킵)

---

## Self-Review

### Spec coverage

| Spec 요구사항 | Task |
|--------------|------|
| `CommonErrorCode.ARTIFACT_NOT_FOUND` (S018) | T1 |
| `ArtifactNotFoundException` (ServerBaseException) | T2 |
| `read` 반환 non-null | T3 |
| Adapter read: 파일 없음 → throw | T3 |
| Adapter read: 다른 IOException 자연 전파 | T3 (catch 분리로 implicit 보장) |
| deleteFile IOException/SecurityException throw | T4, T5 |
| deleteFile "이미 없음" false 유지 | T4 (테스트) |
| 단위 테스트 9건 | T2(2) + T3(4) + T4(3) |
| 컴파일 + 기존 테스트 | T6 |

### Placeholder scan
- "TBD" / "TODO" / "fill in" / "similar to" 없음
- 모든 코드 블록은 실제 코드

### Type consistency
- `ArtifactNotFoundException(errorCode, cause, vararg args)` — T2에서 정의, T3에서 사용. 시그니처 일치.
- `deleteFile(objectKey): Boolean` — T4에서 시그니처 보존, T5에서 내부 로직만 변경. 시그니처 일치.
- `read(endpoint, key): ByteArray` — T3 Port 변경 + T3 테스트에서 호출. 시그니처 일치.
