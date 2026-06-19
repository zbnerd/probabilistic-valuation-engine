package maple.synchronizer.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// CI gate: fails the build if blocking primitives reappear in
// module-synchronizer consumer/, ranking/, or preparer/ main sources.
//
// Blocking primitives checked:
// - .join() on a CompletableFuture chain (sync coercion of async pipeline)
// - runBlocking { (Kotlin coroutine bridge that pins carrier thread)
// - Task.join() (JavaFX / Task join - out of scope here, but cheap to catch)
// - Thread.sleep( (sleeping inside async pipeline is always wrong)
//
// Allowlist rationale (each entry is a documented exception, not a free pass):
// - OcidLookupRunConsumer.kt runBlocking: REMOVED in Sub-PR 5 (now uses
//   CompletableFuture.supplyAsync on executor). No allowlist needed.
// - EquipmentRankingRedisWriter.kt runBlocking: REMOVED in Sub-PR 5 (now uses
//   LogicExecutor.executeOrDefault). No allowlist needed.
// - EquipmentDocumentPreparer.prepare runBlocking: kept temporarily (sync caller
//   path in ChunkDocumentTransformer). Documented acceptable VT-carrier bridge;
//   tracked for follow-up PR. Path-level allowlist for runBlocking ONLY.
// - DefaultChunkFileReader.runBlocking (readBasicChunk/readResultChunk/readOcidMapping):
//   kept temporarily — these are port interface implementations that port consumers
//   call synchronously. Documented acceptable VT-carrier bridge; tracked for
//   follow-up PR. Path-level allowlist for runBlocking ONLY.
// - Comments and Deprecated shim bodies: not new blocking code.
class SynchronizerBlockingPrimitiveGateTest {
    @Test
    fun `no blocking primitives in module-synchronizer consumer ranking or preparer main sources`() {
        val srcRoots = listOf(
            File("src/main/kotlin/maple/synchronizer/consumer"),
            File("src/main/kotlin/maple/synchronizer/ranking"),
            File("src/main/kotlin/maple/synchronizer/preparer"),
            File("src/main/kotlin/maple/synchronizer/storage"),
        )

        val violations = mutableListOf<String>()
        val patterns = listOf(
            Regex("""\.join\(\)"""),
            Regex("""runBlocking\s*\{"""),
            Regex("""Task\.join\(\)"""),
            Regex("""Thread\.sleep\s*\("""),
        )

        srcRoots.forEach { srcRoot ->
            if (!srcRoot.exists()) return@forEach
            srcRoot.walkTopDown()
                .filter { it.extension in listOf("kt", "java") }
                .forEach { file ->
                    file.readLines().forEachIndexed { i, line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEachIndexed
                        if (patterns.any { it.containsMatchIn(trimmed) } && !isAllowlisted(file, i, trimmed)) {
                            violations.add("${file.path}:${i + 1}: $trimmed")
                        }
                    }
                }
        }

        assertThat(violations)
            .withFailMessage("Blocking primitives found:\n${violations.joinToString("\n")}")
            .isEmpty()
    }

    private fun isAllowlisted(file: File, line: Int, text: String): Boolean {
        val path = file.absolutePath
        val isComment = text.startsWith("//") ||
            text.startsWith("*") ||
            text.startsWith("/*")
        val isDeprecatedLine = text.contains("@Deprecated")

        // EquipmentDocumentPreparer.prepare runBlocking: documented acceptable
        // VT-carrier bridge; tracked for follow-up PR. runBlocking ONLY — must NOT
        // introduce .join(), Thread.sleep, or Task.join().
        val isPreparerRunBlocking = path.contains("EquipmentDocumentPreparer") &&
            text.contains("runBlocking")

        // DefaultChunkFileReader.runBlocking (port interface implementations called
        // synchronously by port consumers): documented acceptable VT-carrier
        // bridge; tracked for follow-up PR. runBlocking ONLY.
        val isChunkReaderRunBlocking = path.contains("DefaultChunkFileReader") &&
            text.contains("runBlocking")

        // Coroutine Job.join() (e.g. writerJob.join()) inside the documented runBlocking block
        val isCoroutineJobJoin = text.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*Job\.join\(\).*"""))

        return isComment ||
            isDeprecatedLine ||
            isPreparerRunBlocking ||
            isChunkReaderRunBlocking ||
            isCoroutineJobJoin
    }
}
