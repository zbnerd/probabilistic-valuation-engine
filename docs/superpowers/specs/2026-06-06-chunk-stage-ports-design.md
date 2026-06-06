# Chunk Stage Port Extraction Design

- Date: 2026-06-06
- Owner: TBD
- Related: #923 (decomposed), #1143 (current stage split), #1144 (DRY), #1145 (error strategy)

---

## 1. Background / Problem

### Background

PR #1143 split `DefaultChunkProcessor` into `ChunkDataReader` / `ChunkDocumentTransformer` / `ChunkDocumentWriter`. The split is in code, but the stage boundaries are not expressed as port interfaces. Each stage is a Spring `@Component` with concrete constructor dependencies. Adding a new stage (validation, metrics, schema migration) requires editing `DefaultChunkProcessor` directly.

### Problem

- Stage replacement requires touching `DefaultChunkProcessor` even when only one stage changes
- Test fakes for a single stage are not first-class — they exist only inside the test class
- `ChunkProcessInput` / `ChunkProcessResult` are local DTOs, not shared domain types. Each stage invents its own intermediate shape.
- The "stage list" is implicit: hard-coded calls in `DefaultChunkProcessor`. No way to add/remove stages by configuration.

### Goal

Introduce port interfaces (`ChunkReader` / `ChunkTransformer` / `ChunkWriter`) in `module-core` with a single `Chunk<T>` domain type. Move existing `ChunkDataReader` / `ChunkDocumentTransformer` / `ChunkDocumentWriter` to `module-synchronizer` as adapters. Add a `ChunkPipelineOrchestrator` that takes a stage list and runs them in sequence.

---

## 2. Decision

> Express chunk processing as a Chain of Responsibility over a shared `Chunk<T>` type. Stage interfaces live in `module-core`. Concrete implementations are Spring `@Component` adapters in `module-synchronizer`. A `ChunkPipelineOrchestrator` composes the stage list and runs them in order.

```text
module-core/domain/chunk/
├── Chunk.kt                       (data class Chunk<T>)
├── ChunkReader.kt                 (interface)
├── ChunkTransformer.kt            (interface)
└── ChunkWriter.kt                 (interface)

module-synchronizer/adapter/chunk/
├── DefaultChunkReader.kt          (moved from processor/ChunkDataReader)
├── DefaultChunkTransformer.kt     (moved from processor/ChunkDocumentTransformer)
├── DefaultChunkWriter.kt          (moved from processor/ChunkDocumentWriter)
└── ChunkPipelineOrchestrator.kt   (new — stage list runner)
```

---

## 3. Interfaces

```kotlin
// module-core
data class Chunk<T>(
    val input: ChunkProcessInput,    // objectKey, sourceRunId, sourceChunkId, resultCount
    val data: T,                      // stage-specific payload
    val metadata: Map<String, String> = emptyMap(),
)

interface ChunkReader<T> {
    suspend fun read(chunk: Chunk<Unit>): Chunk<T>
}

interface ChunkTransformer<T, R> {
    suspend fun transform(chunk: Chunk<T>): Chunk<R>
}

interface ChunkWriter<T> {
    suspend fun write(chunk: Chunk<T>): Chunk<Unit>
}
```

Each interface is a single suspend function. Stages are not aware of the next stage — `ChunkPipelineOrchestrator` chains them by type.

---

## 4. Pipeline Orchestrator

```kotlin
@Component
class ChunkPipelineOrchestrator(
    private val reader: ChunkReader<*>,
    private val transformers: List<ChunkTransformer<*, *>>,
    private val writer: ChunkWriter<*>,
) {
    suspend fun execute(input: ChunkProcessInput) {
        val initial = Chunk<Unit>(input, Unit)
        val afterRead = reader.read(initial)
        val afterTransform = transformers.fold(afterRead) { chunk, transformer ->
            @Suppress("UNCHECKED_CAST")
            (transformer as ChunkTransformer<Any?, Any?>).transform(chunk)
        }
        @Suppress("UNCHECKED_CAST")
        (writer as ChunkWriter<Any?>).write(afterTransform)
    }
}
```

The unchecked cast is the seam: stage types are erased at runtime, but the orchestrator's correctness is enforced by Spring bean wiring (only one `ChunkReader`, one `ChunkWriter`, N `ChunkTransformer`).

---

## 5. Trade-offs

### Sensitivity

* Number of transformers in the chain (currently 1, may grow to 3-4 with validation, metrics, schema migration)
* Stage replacement frequency (currently 0/quarter, expected 1-2/quarter)
* Boot test isolation (each stage mockable independently)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Chain of Responsibility over direct calls | 동적 stage 추가/제거, stage별 단위 테스트, port 재사용 | unchecked cast (type erasure 보완) |
| module-core 위치 | 다른 모듈에서 동기화 패턴 재사용 가능, port 명확 | module-core 의존성 +1 (suspend, Chunk<T>만) |
| 단일 Chunk<T> 도메인 타입 | 모든 stage 일관된 interface, metadata로 stage 사이 context 전달 | 각 stage의 T가 무엇인지 명시적이지 않음 (문서로 보완) |
| Unchecked cast (orchestrator) | Kotlin generics 한계 수용, 런타임 type mismatch 가능 | Spring bean wiring 검증에 의존 (boot 시 type 보장) |

### Risk

* `Chunk<T>`가 너무 generic해 stage type 안 보임 — 메서드 javadoc으로 보완
* transformer list가 비어있을 때 zero-stage write 가능 — `@ConditionalOnBean` 또는 minimum 1 transformer 정책 결정 필요
* `suspend` stage가 아닌 non-suspend stage와 호환 안 됨 — 모든 stage는 `suspend`로 통일

### Non-Risk

* 기존 `DefaultChunkProcessorTest` 회귀 없음 (orchestrator가 동일 시그니처 제공)
* `ChunkProcessInput` / `ChunkProcessResult` 마이그레이션 부담 없음 (이관 대상)
* Performance: 단일 stage 호출 오버헤드 0에 가까움 (suspend 함수 체이닝)

---

## 6. Migration Plan

PR1: `module-core` — `Chunk<T>`, 3 port interfaces, port unit tests
PR2: `module-synchronizer` — adapter 3개 이관, `ChunkPipelineOrchestrator` 추가, `DefaultChunkProcessor` deprecated 후 제거

각 PR은 단독 merge 가능. PR1은 port만 정의하므로 다운스트림 영향 없음. PR2는 `DefaultChunkProcessor` 사용처를 `ChunkPipelineOrchestrator`로 1:1 치환.

---

## 7. Test Strategy

* **module-core** port unit test: 각 interface의 suspend 함수 시그니처 검증 (fake implementation 주입)
* **module-synchronizer** fake stage 주입: `ChunkReader<Foo>` fake + transformer mock + `ChunkWriter` fake로 end-to-end 동작 검증
* **기존 통합 테스트 유지**: `DefaultChunkProcessorTest`는 `ChunkPipelineOrchestrator`의 동작 검증으로 전환 (테스트 명 변경 가능)

Coverage target: port interface 100% (interface이므로 trivial), adapter 80%+.

---

## 8. Success Signal

* 신규 stage 1회 추가 시 `DefaultChunkProcessor` 수정 없이 `ChunkPipelineOrchestrator`의 stage list에 component 추가만으로 가능
* port test로 stage 1개 교체 가능 — `ChunkReader<Foo>` fake로 다른 reader 검증

---

## 9. Open Questions

* Transformer list가 비어있을 때 — 최소 1개 강제? 또는 reader → writer 직행 허용?
* `Chunk<T>`의 `metadata: Map<String, String>` — 이게 너무 generic한지, typed `StageContext`로 강화할지?
