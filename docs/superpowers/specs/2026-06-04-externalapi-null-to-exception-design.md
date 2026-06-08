# External-API ArtifactStore/Cleanup null→예외 설계

## Goal

`module-external-api`의 두 silent failure 경로를 명시적 예외로 변환하여 EPIC-2의 "유실 0건 / 실패 관측성 확보" 목표 달성.

## Problem

### Path 1: `LocalExternalApiArtifactStoreAdapter.read` nullable contract

```kotlin
override fun read(endpoint, key): ByteArray? {
    val filePath = resolvePath(endpoint, key)
    if (!Files.exists(filePath)) return null  // ← 호출부가 null check 잊으면 NPE
    return GZIPInputStream(Files.readAllBytes(filePath).inputStream()).use { ... }
}
```

`ByteArray?` 시그니처는 호출부마다 null check를 강제. 호출부 fan-out이 늘어나면 NPE 발생 후 silent pipeline 중단. 현재 호출부 grep 결과 **0건** (port 주입만 존재, 미사용)이지만 향후 사용 시 NPE 위험 잔존.

### Path 2: `ConsumedChunkCleanupScheduler.deleteFile` 모든 예외를 `false`로 환원

```kotlin
return runCatching {
    val deleted = Files.deleteIfExists(path)
    if (deleted) log.info("deleted: ...") else log.debug("already gone: ...")
    deleted
}.onFailure { ex ->
    log.warn("delete failed: {} - {}", objectKey, ex.message)  // ← 경고만, false 반환
}.getOrDefault(false)
```

`Files.deleteIfExists`는 파일 없음(`false`)과 I/O 실패(`IOException`/`SecurityException`)를 구분. 현재 코드는 둘 다 `false`로 환원 → 권한 거부 / 디스크 오류가 "정상 스킵"으로 가려짐. EPIC-2 실패 관측성 위배.

## Solution

### 1. Port 시그니처 hard break

`maple.externalapi.port.out.ExternalApiArtifactStorePort.read`:
- 반환 타입 `ByteArray?` → `ByteArray`
- 파일 미존재 시 `ArtifactNotFoundException` throw

```kotlin
fun read(endpoint: ExternalApiEndpoint, key: String): ByteArray
```

### 2. `ArtifactNotFoundException` 신규

- 위치: `module-common/src/main/kotlin/maple/expectation/error/exception/ArtifactNotFoundException.kt`
- 상속: `ServerBaseException` (I/O 실패 = 서버 측 오류)
- `ErrorCode`: `CommonErrorCode.ARTIFACT_NOT_FOUND` 신규 추가 (`"S018"`, 500, "아티팩트를 찾을 수 없습니다 (endpoint: %s, key: %s)")
- 생성자: `(errorCode: ErrorCode, vararg args: Any?)`, `(errorCode: ErrorCode, cause: Throwable, vararg args: Any?)` (기존 `ExternalApiException` 패턴 동일)

`module-common` 위치 선택 근거:
- `LocalExternalApiArtifactStoreAdapter`는 `module-external-api/infra/storage/`
- port 인터페이스 호출부가 미래에 `module-calculator`/`module-synchronizer` 등으로 확장될 가능성
- `BaseException`/`ServerBaseException` 계층이 `module-common`에 이미 위치 (cross-cutting 정합성)
- `CacheDataNotFoundException`도 같은 위치/패턴 (선례)

### 3. Adapter 구현

```kotlin
override fun read(endpoint: ExternalApiEndpoint, key: String): ByteArray {
    val filePath = resolvePath(endpoint, key)
    return try {
        GZIPInputStream(Files.readAllBytes(filePath).inputStream()).use { it.readAllBytes() }
    } catch (ex: NoSuchFileException) {
        log.warn("[ArtifactStore] artifact not found: endpoint={}, key={}", endpoint, key)
        throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, ex, endpoint.toString(), key)
    }
    // 다른 IOException (권한, 디스크)은 자연 전파 — adapter가 swallow하지 않음
}
```

`Files.exists` 사전 체크 제거. race condition 회피 + 단일 코드 경로.

### 4. `deleteFile` 예외 분리

```kotlin
private fun deleteFile(objectKey: String): Boolean {
    val path = Paths.get(basePath, objectKey)
    return try {
        val deleted = Files.deleteIfExists(path)
        if (deleted) log.info("[ConsumedChunkCleanup] deleted: {}", objectKey)
        else log.debug("[ConsumedChunkCleanup] already gone: {}", objectKey)
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

규칙:
- `Files.deleteIfExists`가 `false` 반환 = "이미 없음" → `false` 유지 (기존 호출부 의미 보존)
- `IOException`/`SecurityException` = I/O 실패 → throw
- 호출부 `cleanup()`의 `if (deleteFile(...) deletedCount++ else failedCount++` 의미가 명확해짐. `false`는 정상 스킵, 예외는 미카운트 + 호출부 `cleanup`이 미처리 메시지 재시도 책임을 짐

## Components

### 변경 파일

| 파일 | 변경 |
|------|------|
| `module-common/.../error/CommonErrorCode.kt` | `ARTIFACT_NOT_FOUND` enum 추가 |
| `module-common/.../error/exception/ArtifactNotFoundException.kt` | 신규 |
| `module-external-api/.../port/out/ExternalApiArtifactStorePort.kt` | `read` 시그니처 `ByteArray?` → `ByteArray` |
| `module-external-api/.../infra/storage/LocalExternalApiArtifactStoreAdapter.kt` | `read` 구현, exception 처리 |
| `module-external-api/.../cleanup/ConsumedChunkCleanupScheduler.kt` | `deleteFile` catch 분리 |
| `module-external-api/src/test/.../LocalExternalApiArtifactStoreAdapterTest.kt` | 신규 (4 케이스) |
| `module-external-api/src/test/.../ConsumedChunkCleanupSchedulerDeleteFileTest.kt` | 신규 (3 케이스) |

## Error Handling

| 경로 | 처리 |
|------|------|
| Artifact read, 파일 없음 | `ArtifactNotFoundException` throw |
| Artifact read, I/O 실패 (권한/디스크) | `IOException` 자연 전파 |
| delete, 이미 없음 | `false` 반환 + DEBUG 로그 |
| delete, `IOException` | throw + ERROR 로그 |
| delete, `SecurityException` | throw + ERROR 로그 |

## Testing

### Unit test: `LocalExternalApiArtifactStoreAdapterTest`

1. `read` 성공 — 정상 gzip 파일 → `ByteArray` 반환
2. `read` 파일 없음 → `ArtifactNotFoundException` throw
3. `read`에서 `ArtifactNotFoundException`의 cause에 `NoSuchFileException` 포함 확인
4. `store` → `read` 왕복 — round-trip 동일성

### Unit test: `ConsumedChunkCleanupSchedulerDeleteFileTest`

`deleteFile`을 `internal` 가시성으로 노출 후:
1. 정상 삭제 → `true`
2. 파일 없음 → `false` (예외 없음)
3. `IOException` 시뮬레이션 (잠긴 파일/permission) → throw

`temp` 디렉토리 사용 (Testcontainers/H2 금지 룰 준수).

## Acceptance criteria

- [ ] `CommonErrorCode.ARTIFACT_NOT_FOUND` 추가 (`S018`)
- [ ] `ArtifactNotFoundException` `ServerBaseException` 상속
- [ ] `ExternalApiArtifactStorePort.read` 반환 타입 non-null
- [ ] `LocalExternalApiArtifactStoreAdapter.read` 파일 없음 시 `ArtifactNotFoundException` throw
- [ ] `LocalExternalApiArtifactStoreAdapter.read` 다른 `IOException`은 자연 전파
- [ ] `ConsumedChunkCleanupScheduler.deleteFile` `IOException`/`SecurityException` throw
- [ ] `deleteFile` "이미 없음"은 `false` 유지
- [ ] 신규 단위 테스트 9건 통과 (`ArtifactNotFoundExceptionTest` 2 + `LocalExternalApiArtifactStoreAdapterTest` 4 + `ConsumedChunkCleanupSchedulerDeleteFileTest` 3)
- [ ] `./gradlew compileKotlin compileJava --continue` 통과
- [ ] 기존 테스트 회귀 없음

## Out of Scope

- `listStoredKeys`/`listRuns`/`deleteRun`/`deleteAll`/`fileExists`/`calculateDirectorySize` — 본 이슈는 `read`와 `deleteFile` 한정
- `fileExists`의 boolean 반환 — 호출부 의미 명확, 변경 불필요
- `deleteFile`의 `Boolean` → `sealed class` (Deleted/NotFound) 리팩토링 — 이슈 범위 초과, 별도 PR

## Trade-offs

### Sensitivity
- `module-common`에 `ArtifactNotFoundException` 추가 = `module-common` 빌드 의존성 그래프에 영향. 하지만 Spring 의존성 없는 순수 exception type이므로 안전.
- `ByteArray?` → `ByteArray` hard break = 현재 미사용 호출부 0건이라 안전. 향후 사용 시 컴파일러가 강제.

### Trade-off
| 선택 | 얻는 것 | 포기한 것 |
|------|--------|-----------|
| hard break (non-null) | NPE 원천 차단, 호출부 단순화 | 기존 nullable contract 의존 코드 (현 0건) |
| `module-common` 예외 위치 | cross-cutting 정합성, 다른 모듈 import 가능 | `module-external-api` 내부 격리 |
| `deleteFile` `Boolean` 유지 | 호출부 의미 보존 | "I/O 실패 vs 없음" 구분을 호출부가 직접 못함 (로그로만) |

### Risk
- `Bytes.exists` 제거로 race condition 가능 (파일이 read 도중 삭제됨). catch에서 `NoSuchFileException`을 `ArtifactNotFoundException`으로 변환하므로 안전.
- `deleteFile`에서 `IOException` throw 시 `cleanup()` batch가 중단됨. 의도된 동작 (실패 관측성 확보)이지만, `pendingDeletions`이 in-memory `ConcurrentLinkedQueue`이며 `cleanup()` 시작 시점에 batch가 `poll`되어 제거된 상태. **process kill/restart 시 batch의 남은 events 영구 손실** → `mq-messaging.md`의 "복구 불가능한 in-memory 상태 금지" 룰의 기존 위반과 연관. **본 PR scope 밖.** EPIC-2 umbrella #1096 또는 후속 issue에서 `pendingDeletions`을 PGMQ/DB-backed queue로 이관 필요.

### Non-Risk
- `deleteFile`에서 `IOException` throw가 cleanup batch를 중단시킬 수 있음 → 의도된 동작. 호출부 `cleanup`이 재시도 책임을 짐 (다음 `@Scheduled` 또는 `@PreDestroy` cycle).
