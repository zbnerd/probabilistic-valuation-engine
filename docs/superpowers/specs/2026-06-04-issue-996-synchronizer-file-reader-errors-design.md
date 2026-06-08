# Issue #996: Synchronizer file readers silent error loss

- Issue: https://github.com/.../issues/996 (module-synchronizer)
- Date: 2026-06-04
- Status: Proposed

---

## 1. Background / Problem

### Background

`OcidMappingFileReader.parseMapping` 과 `BasicChunkFileReader.parseRecord` 는 모든 예외를 `runCatching { ... }.getOrNull()` 로 무시한다. 또한 `OcidMappingFileReader.read` 는 파일 미존재 시 `emptyList()` + caller 의 ack 로 영구 유실된다.

같은 모듈의 `ResultFileReader` 와 `BasicChunkFileReader.read` 자체 (file open) 는 `require` / `IllegalStateException` 을 사용 — 불일치.

### Problem

세 가지 silent failure:

1. **손상된 gzip / 잘못된 JSON line** → `null` 반환 → 정상 record 인 양 처리. chunk 전체가 silent 하게 누락될 수 있다.
2. **필드 누락 (e.g. `userIgn` 없음)** → `null` 반환. 의도적 필터링인지 손상인지 구분 불가.
3. **파일 미존재** (`OcidMappingFileReader.read` 만) → `emptyList()` + consumer 의 `if (mappings.isEmpty()) ack` → 영구 유실. `OcidLookupRunConsumer` 가 manifest 가 사라진 run 을 무시.

### Goal

* JSON 파싱 오류 (corrupted gzip, malformed JSON): **즉시 throw** → caller 가 retry/reject 결정.
* 의도적 필터링 (status/endpoint 불일치): `null` 유지 (기존 동작).
* 필드 누락: 카운트 + 로그 + 임계치 초과 시 throw.
* 파일 미존재: `IllegalStateException` (다른 reader 와 일치).

---

## 2. Decision

### 2.1 `OcidMappingFileReader.parseMapping`

```kotlin
private fun parseMapping(line: String, parseErrorCount: AtomicLong, missingFieldCount: AtomicLong): OcidMapping? {
    val node: JsonNode = try {
        objectMapper.readTree(line)
    } catch (ex: JsonProcessingException) {
        parseErrorCount.incrementAndGet()
        throw ex  // 즉시 전파 — caller 가 decide
    }
    val ign = node.get("userIgn")?.asText()
    val ocid = node.get("ocid")?.asText()
    if (ign.isNullOrBlank() || ocid.isNullOrBlank()) {
        missingFieldCount.incrementAndGet()
        return null  // 의도적 skip — 카운트만
    }
    return OcidMapping(ign, ocid)
}
```

### 2.2 `OcidMappingFileReader.read`

```kotlin
fun read(manifestPath: String): List<OcidMapping> {
    val path = Paths.get(storeBasePath, manifestPath)
    if (!Files.exists(path)) {
        throw IllegalStateException("Ocid mapping file not found: $path")
    }
    val parseErrors = AtomicLong(0)
    val missingFields = AtomicLong(0)
    val mappings = mutableListOf<OcidMapping>()
    GZIPInputStream(BufferedInputStream(Files.newInputStream(path))).bufferedReader().use { reader ->
        reader.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line -> parseMapping(line, parseErrors, missingFields)?.let { mappings.add(it) } }
    }
    if (parseErrors.get() > 0) {
        log.error("[OcidMappingFileReader] {} parse errors, {} missing fields, parsed {} from {}",
            parseErrors.get(), missingFields.get(), mappings.size, manifestPath)
    } else if (missingFields.get() > 0) {
        log.warn("[OcidMappingFileReader] {} missing fields, parsed {} from {}",
            missingFields.get(), mappings.size, manifestPath)
    } else {
        log.info("[OcidMappingFileReader] parsed {} mappings from {}", mappings.size, manifestPath)
    }
    return mappings
}
```

### 2.3 `BasicChunkFileReader.parseRecord`

```kotlin
private fun parseRecord(
    line: String,
    parseErrorCount: AtomicLong,
    missingFieldCount: AtomicLong,
    filteredCount: AtomicLong,
    errorThreshold: Int,
): BasicRecord? {
    val node: JsonNode = try {
        objectMapper.readTree(line)
    } catch (ex: JsonProcessingException) {
        parseErrorCount.incrementAndGet()
        throw ex
    }
    if (node.get("status")?.asText() != "SUCCESS") { filteredCount.incrementAndGet(); return null }
    if (node.get("endpoint")?.asText() != "character-basic") { filteredCount.incrementAndGet(); return null }
    val ocid = node.get("key")?.asText()
    val body = node.get("body")
    if (ocid.isNullOrBlank() || body == null) {
        missingFieldCount.incrementAndGet()
        if (missingFieldCount.get() > errorThreshold) {
            throw IllegalStateException("BasicChunk missing-field threshold exceeded: $missingFieldCount")
        }
        return null
    }
    // ... existing field extraction
}
```

임계치 `errorThreshold` 는 default 100 (전체의 ~0.01% 미만은 정상 손실로 간주, 그 이상이면 파일 손상 추정). YAML 외부화 가능 (`@Value`).

### 2.4 Caller impact (`OcidLookupRunConsumer`)

`fileReader.read` 가 throw 하면 Spring Kafka listener container 의 `ErrorHandler` 가 redelivery 를 결정한다. 현재 container 의 default 가 `SeekToCurrentErrorHandler` / `DefaultErrorHandler` 라면 consumer 재시작 시 같은 record 재처리. 별도 try/catch 추가 불필요 — 예외가 자연 전파되어 ack 안 됨.

---

## 3. Trade-offs

### Sensitivity

* chunk 사이즈 (records per file) — 손상 line 비율 threshold 결정에 영향
* Kafka redelivery backoff — listener container error handler 설정에 의존
* 운영 alert — `parse error` log 가 ERROR level 이므로 즉시 가시

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| --- | --- | --- |
| parse error 즉시 throw | silent loss 제거, 운영 alert | 첫 1개 손상 line 으로 전체 chunk fail 가능 → threshold 로 완화 |
| 필드 누락은 카운트만 (default) | 정상 데이터의 부분 손실은 OK | 의도적 corruption 도 통과 — threshold 초과 시에만 throw |
| 임계치 hardcode 100 | 단순, 추가 config 0 | 환경별 튜닝 불가 → `@Value` 외부화로 완화 |
| `JsonProcessingException` (파싱) + `IllegalStateException` (필드 임계치) 분리 | 의미 명확, 호출부에서 두 케이스 구분 가능 | caller 가 두 예외 타입을 인지해야 함 |

### Risk

* 기존 `OcidMappingFileReaderTest` 의 `skips blank and malformed lines` 테스트가 **변경 필요** — malformed line 이 더 이상 skip 되지 않고 throw. acceptance criteria 와 일치하므로 test 자체를 갱신.
* `OcidLookupRunConsumer` 가 이제 redelivery loop 에 빠질 수 있다 (파일이 계속 손상된 경우). **완화**: error log + metric 으로 운영자가 인지, `DefaultErrorHandler` 의 max-retries (default 10) 가 적용됨.
* `JsonProcessingException` throw 시 line 번호 정보 손실. **완화**: log 에서 line prefix (e.g. first 80 chars) 함께 출력.

### Non-Risk

* `BasicChunkFileReader.read` 의 file open 자체 (`require(Files.exists(path))`) 는 이미 throw — 변경 없음.
* 다른 reader (`ResultFileReader`) 는 이미 `IllegalStateException` 사용 — 변경 없음.
* gzip decompression error 도 `IOException` 으로 throw — line-by-line parse 전에 발생하면 caller 에서 catch.

---

## 4. Result / Evidence

### Metrics (additions)

| Metric | Type | Tags | Notes |
| --- | --- | --- | --- |
| `synchronizer_reader_parse_error_total` | Counter | `reader=ocid_mapping\|basic_chunk` | JSON parse exception 횟수 |
| `synchronizer_reader_missing_field_total` | Counter | `reader=ocid_mapping\|basic_chunk` | 필드 누락 skip 횟수 |
| `synchronizer_reader_filtered_total` | Counter | `reader=basic_chunk,reason=status\|endpoint` | 의도적 필터링 (basic 만) |

### Observed Result (expected)

* 손상 파일 발생 시 ERROR log + 위 metric 증가 → 운영 alert 가능.
* `OcidMappingFileReader` 의 "no mappings found" warn 로그가 사라짐 (이제 throw).
* `runItemEquipmentCycle` 와 독립 — 이 이슈는 reader 의 신뢰성만 다룸.

---

## 5. Files Changed

| File | Change |
| --- | --- |
| `module-synchronizer/.../storage/OcidMappingFileReader.kt` | `read` 가 missing file 시 throw. `parseMapping` 가 parse error 시 throw + 필드 누락 카운트. log 메시지 분리. |
| `module-synchronizer/.../storage/BasicChunkFileReader.kt` | `parseRecord` 시그니처 확장 (counters + threshold). `read` / `readInBatches` 가 counters 전달. |
| `module-synchronizer/.../config/SynchronizerStorageConfig.kt` (or YAML) | `synchronizer.reader.missing-field-threshold` property (default 100). |
| `module-synchronizer/.../metrics/SynchronizerMetrics.kt` | 위 3개 counter 추가. |
| `module-synchronizer/src/test/.../OcidMappingFileReaderTest.kt` | `skips malformed lines` → `throws on malformed line` 으로 변경. `returns empty list when not found` → `throws when not found` 로 변경. 정상 케이스 유지. |
| `module-synchronizer/src/test/.../BasicChunkFileReaderTest.kt` (new) | 정상 파싱, 필터링 (status/endpoint), parse error throw, missing field threshold 검증. |

---

## 6. Testing Strategy

* Unit: 각 reader 별로 (a) 정상 JSONL, (b) malformed JSON line → throw, (c) missing field → skip + counter, (d) file not found → throw.
* Threshold test: 101개 record 중 101번째가 missing field 일 때 throw.
* 기존 `skips blank and malformed lines` 테스트 삭제 + 대체.
* `OcidLookupRunConsumer` 통합 테스트: 손상 manifest → listener 가 throw → redelivery 카운트 증가 (있다면).

---

## 7. Summary

> Reader 두 개의 `runCatching{}.getOrNull()` silent loss 패턴을 제거. JSON parse error 는 즉시 throw, 필드 누락은 카운트+threshold, 파일 미존재는 IllegalStateException. 운영자가 손상 chunk 를 즉시 인지 가능.
