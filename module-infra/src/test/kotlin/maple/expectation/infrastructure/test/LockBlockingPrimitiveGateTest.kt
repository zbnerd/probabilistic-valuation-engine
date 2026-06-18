package maple.expectation.infrastructure.test

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * CI grep gate that scans module-infra/src/main/.../lock/ for blocking primitives
 * (task.get(), runBlocking, Thread.sleep) and fails the build if found.
 *
 * Allowlist:
 *  - Lines containing @Deprecated, @Throws, or comment markers.
 *  - Lines inside a function body whose preceding annotation block contains @Deprecated.
 *  - Thread.sleep inside CompletableFuture.supplyAsync({...}, executor) blocks —
 *    the new VT-friendly async polling pattern.
 */
class LockBlockingPrimitiveGateTest {
    @Test
    fun `no task_get or runBlocking or Thread_sleep in module-infra lock main sources`() {
        val srcRoot = File("src/main/kotlin/maple/expectation/infrastructure/lock")
        if (!srcRoot.exists()) return

        val violations = mutableListOf<String>()
        val patterns = listOf(
            Regex("""task\.get\(\)"""),
            Regex("""runBlocking\s*\{"""),
            Regex("""Thread\.sleep\s*\("""),
        )

        srcRoot.walkTopDown()
            .filter { it.extension in listOf("kt", "java") }
            .forEach { file ->
                val lines = file.readLines()
                val deprecatedBodySpan = markDeprecatedBodies(lines)
                val asyncPollingSpan = markAsyncPollingBodies(lines)
                lines.forEachIndexed { i, line ->
                    val trimmed = line.trim()
                    if (patterns.any { it.containsMatchIn(trimmed) } &&
                        !isAllowlisted(file, trimmed, deprecatedBodySpan[i], asyncPollingSpan[i])
                    ) {
                        violations.add("${file.path}:${i + 1}: $trimmed")
                    }
                }
            }

        assertTrue(violations.isEmpty(), "Blocking primitives found:\n${violations.joinToString("\n")}")
    }

    /**
     * Marks lines that lie inside a function body whose preceding annotation block
     * contains @Deprecated. Handles multi-line function signatures (where `fun` and
     * the opening `{` are on different lines) by looking back up to 10 lines.
     */
    private fun markDeprecatedBodies(lines: List<String>): BooleanArray {
        val n = lines.size
        val result = BooleanArray(n)
        var depth = 0
        var bodyDepth = -1
        var bodyDeprecated = false

        for (i in 0 until n) {
            val raw = lines[i]
            val trimmed = raw.trim()
            val opens = raw.count { it == '{' }
            val closes = raw.count { it == '}' }

            if (bodyDepth < 0) {
                // Look for `fun` declaration that opens a body on this or a near-future line.
                val funIdx = findFunDeclaration(lines, i)
                if (funIdx >= 0 && opens > 0 && trimmed.contains("{")) {
                    bodyDepth = depth + opens
                    bodyDeprecated = hasDeprecatedAbove(lines, funIdx)
                    result[i] = bodyDeprecated
                    if (funIdx != i) result[funIdx] = bodyDeprecated
                }
            } else {
                result[i] = bodyDeprecated
            }

            depth += opens - closes
            if (bodyDepth >= 0 && depth < bodyDepth) {
                bodyDepth = -1
                bodyDeprecated = false
            }
        }
        return result
    }

    /**
     * Marks lines that lie inside a `CompletableFuture.supplyAsync({...}, executor)`
     * or `runAsync` block — VT-friendly async polling pattern.
     */
    private fun markAsyncPollingBodies(lines: List<String>): BooleanArray {
        val n = lines.size
        val result = BooleanArray(n)
        var depth = 0
        var blockDepth = -1

        for (i in 0 until n) {
            val raw = lines[i]
            val trimmed = raw.trim()
            val opens = raw.count { it == '{' }
            val closes = raw.count { it == '}' }

            if (blockDepth < 0) {
                if ((trimmed.contains("CompletableFuture.supplyAsync(") ||
                     trimmed.contains("CompletableFuture.runAsync(")) &&
                    opens > 0
                ) {
                    blockDepth = depth + opens
                    result[i] = true
                }
            } else {
                result[i] = true
            }

            depth += opens - closes
            if (blockDepth >= 0 && depth < blockDepth) {
                blockDepth = -1
            }
        }
        return result
    }

    /** Returns the index of the most recent `fun` declaration at top level, looking back up to 10 lines. */
    private fun findFunDeclaration(lines: List<String>, beforeIdx: Int): Int {
        for (j in beforeIdx - 1 downTo maxOf(0, beforeIdx - 10)) {
            val t = lines[j].trim()
            if (t.startsWith("fun ") || t.startsWith("private fun ") ||
                t.startsWith("internal fun ") || t.startsWith("protected fun ") ||
                t.startsWith("public fun ") || t.startsWith("override fun ")
            ) return j
            // Stop on previous closing brace.
            if (t == "}" || t.startsWith("} ")) return -1
        }
        return -1
    }

    /** Returns true if any line in the 10 lines above funIdx contains `@Deprecated`. */
    private fun hasDeprecatedAbove(lines: List<String>, funIdx: Int): Boolean {
        for (j in funIdx - 1 downTo maxOf(0, funIdx - 10)) {
            val t = lines[j].trim()
            if (t.contains("@Deprecated")) return true
            // Stop at a previous `fun` declaration.
            if (t.startsWith("fun ") || t.startsWith("private fun ") ||
                t.startsWith("internal fun ") || t.startsWith("protected fun ") ||
                t.startsWith("public fun ") || t.startsWith("override fun ")
            ) return false
        }
        return false
    }

    private fun isAllowlisted(
        file: File,
        text: String,
        inDeprecatedSpan: Boolean,
        inAsyncPollingSpan: Boolean,
    ): Boolean {
        val path = file.absolutePath
        val isLegacySync = path.contains("PostgresAdvisoryLockStrategy") ||
                           path.contains("PostgresLockStrategy") ||
                           path.contains("GuavaLockStrategy") ||
                           path.contains("OrderedLockExecutor") ||
                           path.contains("AbstractLockStrategy") ||
                           path.contains("/LockStrategy.kt")
        val isMarkerLine = text.contains("@Deprecated") ||
                           text.startsWith("//") ||
                           text.startsWith("*") ||
                           text.startsWith("/*") ||
                           text.contains("throws")
        // Thread.sleep inside CompletableFuture.supplyAsync/runAsync is the VT-friendly
        // async polling pattern (introduced by Tasks 4-5) — only allowed in legacy files.
        val isInsideAsyncPolling = text.contains("Thread.sleep") && inAsyncPollingSpan
        return isLegacySync && (isMarkerLine || inDeprecatedSpan || isInsideAsyncPolling)
    }
}
