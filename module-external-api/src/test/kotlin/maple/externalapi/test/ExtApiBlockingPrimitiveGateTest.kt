package maple.externalapi.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// CI gate: fails the build if blocking primitives reappear in
// module-external-api runstatus/ or scheduler/ main sources.
//
// Blocking primitives checked:
// - .join() on a CompletableFuture chain (sync coercion of async pipeline)
// - runBlocking { (Kotlin coroutine bridge that pins carrier thread)
// - Task.join() (JavaFX / Task join - out of scope here, but cheap to catch)
// - Thread.sleep( (sleeping inside async pipeline is always wrong)
//
// Allowlist rationale (each entry is a documented exception, not a free pass):
// - ExternalApiScheduler.kt runBlocking: documented acceptable VT-carrier
//   bridge per async-patterns.md (see audit + ADR-blocking-async-contract-cf-chain).
//   Path-level allowlist for runBlocking ONLY - must NOT introduce .join(),
//   Thread.sleep, or Task.join().
// - Coroutine Job.join() (e.g. writerJob.join()) inside the documented
//   runBlocking block: legitimate Kotlin coroutine bridge.
// - Comments and Deprecated shim bodies: not new blocking code.
class ExtApiBlockingPrimitiveGateTest {
    @Test
    fun `no blocking primitives in module-external-api runstatus or scheduler main sources`() {
        val srcRoots = listOf(
            File("src/main/kotlin/maple/externalapi/runstatus"),
            File("src/main/kotlin/maple/externalapi/scheduler"),
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

        // ExternalApiScheduler.kt runBlocking: documented acceptable VT-carrier bridge
        val isLegacyRunBlocking = path.contains("ExternalApiScheduler") &&
            text.contains("runBlocking")

        // Coroutine Job.join() (e.g. writerJob.join()) inside the documented runBlocking block
        val isCoroutineJobJoin = text.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*Job\.join\(\).*"""))

        return isComment || isDeprecatedLine || isLegacyRunBlocking || isCoroutineJobJoin
    }
}
