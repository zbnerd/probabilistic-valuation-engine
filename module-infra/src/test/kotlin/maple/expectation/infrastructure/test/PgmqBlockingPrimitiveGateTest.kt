package maple.expectation.infrastructure.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * CI gate: fails the build if blocking primitives reappear in pgmq/worker main sources.
 *
 * Blocking primitives checked:
 * - `.join()` on a CompletableFuture chain (sync coercion of async pipeline)
 * - `runBlocking {` (Kotlin coroutine bridge that pins carrier thread)
 * - `Task.join()` (JavaFX/Task join — out of scope here, but cheap to catch)
 * - `task.get()` (lowercase local var named `task` followed by `.get()` — avoids
 *   false-positives on `AtomicInteger.get()` and the legitimate
 *   `.thenApply { allOfFutures.map { it.get() } }` CF idiom after `allOf`)
 * - `Thread.sleep(` (sleeping inside async pipeline is always wrong)
 *
 * Allowlist rationale (each entry is a documented exception, not a free pass):
 * - `PgmqWorker.kt`: legacy `processSequentialBatch` still uses `runBlocking` —
 *   migration tracked separately (CPU-bound `calculateOnly` per Issue #1131).
 *   Path-level allowlist covers all `runBlocking` lines in this file.
 * - Lines containing `@Deprecated`: shim bodies of `process(): Boolean` in
 *   migrated workers (`ExternalApiWorker`, `CalculationWorker`, etc.) that
 *   call `processAsync(message).get()` to bridge async → sync. These are
 *   the migration path; remove only after all callers move to `processAsync`.
 * - `ExternalApiWorker.kt:loadAndWait` and `OcidResolveWorker.kt:resolveOcid`:
 *   CF-→-sync `.join()` sites kept by explicit ADR (see inline Javadoc +
 *   docs/05_Reports/2026-06-18-blocking-audit.md). Path-level allowlist
 *   for `.join()` only — these files must NOT introduce `runBlocking`,
 *   `Thread.sleep`, or `Task.join()`.
 */
class PgmqBlockingPrimitiveGateTest {
    @Test
    fun `no blocking primitives in module-infra pgmq or worker main sources`() {
        val srcRoots = listOf(
            File("src/main/kotlin/maple/expectation/infrastructure/pgmq"),
            File("src/main/kotlin/maple/expectation/infrastructure/worker"),
        )

        val joinPattern = Regex("""\.join\(\)""")
        val runBlockingPattern = Regex("""runBlocking\s*\{""")
        val taskJoinPattern = Regex("""Task\.join\(\)""")
        val taskGetPattern = Regex("""\btask\.get\(\)""")
        val threadSleepPattern = Regex("""Thread\.sleep\s*\(""")

        val violations = mutableListOf<String>()

        srcRoots.forEach { srcRoot ->
            if (!srcRoot.exists()) return@forEach
            srcRoot.walkTopDown()
                .filter { it.extension == "kt" || it.extension == "java" }
                .forEach { file ->
                    file.readLines().forEachIndexed { i, rawLine ->
                        val trimmed = rawLine.trim()
                        if (trimmed.isEmpty()) return@forEachIndexed

                        if (runBlockingPattern.containsMatchIn(trimmed) &&
                            !isRunBlockingAllowlisted(file, trimmed)
                        ) {
                            violations.add("${file.path}:${i + 1}: $trimmed")
                        }
                        if (joinPattern.containsMatchIn(trimmed) &&
                            !isJoinAllowlisted(file, trimmed)
                        ) {
                            violations.add("${file.path}:${i + 1}: $trimmed")
                        }
                        if (taskJoinPattern.containsMatchIn(trimmed)) {
                            violations.add("${file.path}:${i + 1}: $trimmed")
                        }
                        if (taskGetPattern.containsMatchIn(trimmed) &&
                            !isDeprecationLine(trimmed)
                        ) {
                            violations.add("${file.path}:${i + 1}: $trimmed")
                        }
                        if (threadSleepPattern.containsMatchIn(trimmed) &&
                            !isCommentLine(trimmed)
                        ) {
                            violations.add("${file.path}:${i + 1}: $trimmed")
                        }
                    }
                }
        }

        assertThat(violations)
            .withFailMessage(
                "Blocking primitives found in pgmq/worker main sources:\n" +
                    violations.joinToString("\n"),
            )
            .isEmpty()
    }

    /** PgmqWorker.processSequentialBatch keeps runBlocking; legacy migration path. */
    private fun isRunBlockingAllowlisted(file: File, text: String): Boolean {
        val isComment = isCommentLine(text)
        val isDeprecatedLine = isDeprecationLine(text)
        val isLegacyPgmqRunBlocking = file.absolutePath.contains("/pgmq/PgmqWorker.kt")
        return isComment || isDeprecatedLine || isLegacyPgmqRunBlocking
    }

    /** CF-→-sync `.join()` in topic subscriber (OcidResolve) and loadAndWait (ExternalApi). */
    private fun isJoinAllowlisted(file: File, text: String): Boolean {
        val isComment = isCommentLine(text)
        val isDeprecatedLine = isDeprecationLine(text)
        val path = file.absolutePath
        val isDocumentedCfToSync =
            path.contains("/worker/ExternalApiWorker.kt") ||
                path.contains("/worker/OcidResolveWorker.kt")
        return isComment || isDeprecatedLine || isDocumentedCfToSync
    }

    private fun isCommentLine(text: String): Boolean =
        text.startsWith("//") || text.startsWith("*") || text.startsWith("/*")

    private fun isDeprecationLine(text: String): Boolean =
        text.contains("@Deprecated")
}
