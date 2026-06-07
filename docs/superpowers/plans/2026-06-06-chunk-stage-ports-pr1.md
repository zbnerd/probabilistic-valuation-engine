# Chunk Stage Ports — PR1 (module-core port) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `Chunk<T>`, `ChunkReader`, `ChunkTransformer`, `ChunkWriter` port interfaces in `module-core` with unit tests. No adapter migration in this PR — PR2 will move existing `module-synchronizer` stage components to adapters.

**Architecture:** `module-core/domain/chunk/` package with one data class and three interfaces, each a single suspend function. `metadata: Map<String, String>` for stage-to-stage context. No Spring annotations on the interfaces.

**Tech Stack:** Kotlin, kotlinx-coroutines, JUnit5.

**Spec Reference:** `docs/superpowers/specs/2026-06-06-chunk-stage-ports-design.md`

---

## File Structure

```
module-core/src/main/kotlin/maple/core/domain/chunk/
├── Chunk.kt                 (data class)
├── ChunkReader.kt           (interface)
├── ChunkTransformer.kt      (interface)
└── ChunkWriter.kt           (interface)

module-core/src/test/kotlin/maple/core/domain/chunk/
├── ChunkTest.kt
├── ChunkReaderTest.kt
├── ChunkTransformerTest.kt
└── ChunkWriterTest.kt
```

Each production file has one interface. Each test file covers one port. No shared test fixture — each test defines its own fake stage.

---

## Task 1: Chunk Data Class

**Files:**
- Create: `module-core/src/main/kotlin/maple/core/domain/chunk/Chunk.kt`
- Create: `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.core.domain.chunk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ChunkTest {
    @Test
    fun `holds input data and metadata`() {
        val input = ChunkProcessInput(
            objectKey = "key1",
            sourceRunId = "run1",
            sourceChunkId = "chunk1",
            resultCount = 42,
        )
        val chunk = Chunk(input = input, data = "payload", metadata = mapOf("trace" to "abc"))
        assertEquals(input, chunk.input)
        assertEquals("payload", chunk.data)
        assertEquals("abc", chunk.metadata["trace"])
    }

    @Test
    fun `metadata defaults to empty`() {
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, Unit)
        assertEquals(emptyMap<String, String>(), chunk.metadata)
    }
}
```

This test references `ChunkProcessInput`. If that type does not exist in `module-core` yet, it must be added as a sibling. Use a minimal version:

```kotlin
// module-core/src/main/kotlin/maple/core/domain/chunk/ChunkProcessInput.kt
package maple.core.domain.chunk

data class ChunkProcessInput(
    val objectKey: String,
    val sourceRunId: String,
    val sourceChunkId: String,
    val resultCount: Int,
)
```

(Adjust if `ChunkProcessInput` already exists in `module-synchronizer` — import from there. If it does not exist, create in `module-core` and have the synchronizer module import it later.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkTest" --continue 2>&1 | tail -10`
Expected: COMPILATION FAILURE (`Chunk` not found)

- [ ] **Step 3: Create Chunk data class**

```kotlin
package maple.core.domain.chunk

data class Chunk<T>(
    val input: ChunkProcessInput,
    val data: T,
    val metadata: Map<String, String> = emptyMap(),
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkTest" --continue 2>&1 | tail -10`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/core/domain/chunk/Chunk.kt module-core/src/main/kotlin/maple/core/domain/chunk/ChunkProcessInput.kt module-core/src/test/kotlin/maple/core/domain/chunk/ChunkTest.kt
git commit -m "feat(core): add Chunk<T> domain type and ChunkProcessInput"
```

---

## Task 2: ChunkReader Port

**Files:**
- Create: `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkReader.kt`
- Create: `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkReaderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.core.domain.chunk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ChunkReaderTest {
    @Test
    fun `read returns chunk with payload`() = runTest {
        val reader = object : ChunkReader<String> {
            override suspend fun read(chunk: Chunk<Unit>): Chunk<String> =
                chunk.copy(data = "loaded")
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val initial = Chunk<Unit>(input, Unit)
        val result = reader.read(initial)
        assertEquals("loaded", result.data)
        assertEquals(input, result.input)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkReaderTest" --continue 2>&1 | tail -10`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create ChunkReader interface**

```kotlin
package maple.core.domain.chunk

interface ChunkReader<T> {
    suspend fun read(chunk: Chunk<Unit>): Chunk<T>
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkReaderTest" --continue 2>&1 | tail -10`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/core/domain/chunk/ChunkReader.kt module-core/src/test/kotlin/maple/core/domain/chunk/ChunkReaderTest.kt
git commit -m "feat(core): add ChunkReader port interface"
```

---

## Task 3: ChunkTransformer Port

**Files:**
- Create: `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkTransformer.kt`
- Create: `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkTransformerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.core.domain.chunk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ChunkTransformerTest {
    @Test
    fun `transform maps data and propagates input`() = runTest {
        val transformer = object : ChunkTransformer<String, Int> {
            override suspend fun transform(chunk: Chunk<String>): Chunk<Int> =
                chunk.copy(data = chunk.data.length)
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, "hello")
        val result = transformer.transform(chunk)
        assertEquals(5, result.data)
        assertEquals(input, result.input)
    }

    @Test
    fun `transform passes metadata through`() = runTest {
        val transformer = object : ChunkTransformer<String, String> {
            override suspend fun transform(chunk: Chunk<String>): Chunk<String> =
                chunk.copy(data = chunk.data.uppercase())
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, "abc", metadata = mapOf("step" to "1"))
        val result = transformer.transform(chunk)
        assertEquals("ABC", result.data)
        assertEquals("1", result.metadata["step"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkTransformerTest" --continue 2>&1 | tail -10`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create ChunkTransformer interface**

```kotlin
package maple.core.domain.chunk

interface ChunkTransformer<T, R> {
    suspend fun transform(chunk: Chunk<T>): Chunk<R>
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkTransformerTest" --continue 2>&1 | tail -10`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/core/domain/chunk/ChunkTransformer.kt module-core/src/test/kotlin/maple/core/domain/chunk/ChunkTransformerTest.kt
git commit -m "feat(core): add ChunkTransformer port interface"
```

---

## Task 4: ChunkWriter Port

**Files:**
- Create: `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkWriter.kt`
- Create: `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkWriterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.core.domain.chunk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.atomic.AtomicReference

class ChunkWriterTest {
    @Test
    fun `write receives chunk and returns terminal chunk`() = runTest {
        val captured = AtomicReference<Chunk<String>?>()
        val writer = object : ChunkWriter<String> {
            override suspend fun write(chunk: Chunk<String>): Chunk<Unit> {
                captured.set(chunk)
                return chunk.copy(data = Unit)
            }
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, "payload")
        val result = writer.write(chunk)
        assertEquals("payload", captured.get()?.data)
        assertEquals(Unit, result.data)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkWriterTest" --continue 2>&1 | tail -10`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create ChunkWriter interface**

```kotlin
package maple.core.domain.chunk

interface ChunkWriter<T> {
    suspend fun write(chunk: Chunk<T>): Chunk<Unit>
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-core:test --tests "maple.core.domain.chunk.ChunkWriterTest" --continue 2>&1 | tail -10`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/core/domain/chunk/ChunkWriter.kt module-core/src/test/kotlin/maple/core/domain/chunk/ChunkWriterTest.kt
git commit -m "feat(core): add ChunkWriter port interface"
```

---

## Task 5: Final Verification

- [ ] **Step 1: Compile entire repo**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all module-core tests**

Run: `./gradlew :module-core:test --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Create branch + push + open PR**

```bash
git checkout -b feat/chunk-stage-ports-pr1 develop
git push -u origin feat/chunk-stage-ports-pr1
gh pr create --base develop --title "feat(core): add Chunk stage port interfaces (PR1)" --body '## Summary
Introduces `module-core/domain/chunk/`:
- `Chunk<T>` data class with `input` + `data` + `metadata`
- `ChunkReader<T>`, `ChunkTransformer<T, R>`, `ChunkWriter<T>` port interfaces (single suspend function each)
- 6 unit tests

## Spec
docs/superpowers/specs/2026-06-06-chunk-stage-ports-design.md

## Next
PR2 will move `module-synchronizer` `ChunkDataReader`/`ChunkDocumentTransformer`/`ChunkDocumentWriter` to adapters implementing these ports, and add `ChunkPipelineOrchestrator`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
'
```

---

## Self-Review

**1. Spec coverage:**
- §3 `Chunk<T>`, `ChunkReader`, `ChunkTransformer`, `ChunkWriter` interfaces — Tasks 1-4 ✓
- §4 `ChunkPipelineOrchestrator` — out of scope (PR2) ✓
- §6 PR1 is port-only, no migration — Tasks 1-4 ✓
- §7 Test strategy port unit tests — Tasks 1-4 ✓
- §8 Success signal — out of scope (PR2 verification) ✓

**2. Placeholder scan:** No TBD / TODO. All code blocks complete. `ChunkProcessInput` location addressed with conditional branch in Task 1.

**3. Type consistency:**
- `Chunk<T>` defined Task 1, used in Tasks 2/3/4 ✓
- `ChunkReader<T>` single method `read(chunk: Chunk<Unit>): Chunk<T>` — matches between interface and test ✓
- `ChunkTransformer<T, R>` single method `transform(chunk: Chunk<T>): Chunk<R>` — matches ✓
- `ChunkWriter<T>` single method `write(chunk: Chunk<T>): Chunk<Unit>` — matches ✓
- `ChunkProcessInput` constructor `(objectKey, sourceRunId, sourceChunkId, resultCount)` — consistent across tasks ✓
