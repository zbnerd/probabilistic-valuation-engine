package maple.pipeline.artifact.identity

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArtifactIdentitySourceGuardTest {
    @Test
    fun `active services use artifact-owned storage and identity`() {
        val repositoryRoot = repositoryRoot()
        val sourceRoots = activeSourceRoots.map(repositoryRoot::resolve)

        assertThat(sourceRoots.filterNot(Files::isDirectory))
            .withFailMessage("Active service production source roots must exist")
            .isEmpty()

        val violations = sourceRoots
            .flatMap(::productionFiles)
            .flatMap { source -> violations(repositoryRoot, source) }

        assertThat(violations)
            .withFailMessage(
                "Active service production sources must use artifact-owned storage and identity:\n%s",
                violations.joinToString("\n"),
            )
            .isEmpty()
    }

    private fun violations(repositoryRoot: Path, source: Path): List<String> {
        val contents = Files.readString(source)
        val relativePath = repositoryRoot.relativize(source)
        val violations = mutableListOf<String>()

        if (legacyStoragePackage in contents) {
            violations += "$relativePath: legacy storage package $legacyStoragePackage"
        }

        quotedLiterals(contents).forEach { literal ->
            forbiddenIdentityLiterals
                .filter(literal.value::contains)
                .forEach { forbidden ->
                    violations += "$relativePath:${lineNumber(contents, literal.offset)}: quoted raw artifact identity '$forbidden'"
                }
        }

        return violations
    }

    private fun productionFiles(sourceRoot: Path): List<Path> =
        Files.walk(sourceRoot).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { path -> path.fileName.toString().substringAfterLast('.', "") in textSourceExtensions }
                .sorted()
                .toList()
        }

    private fun quotedLiterals(source: String): List<QuotedLiteral> {
        val literals = mutableListOf<QuotedLiteral>()
        var cursor = 0

        while (cursor < source.length) {
            cursor = when {
                source.startsWith("//", cursor) -> skipUntil(source, cursor + 2, "\n")
                source.startsWith("/*", cursor) -> skipBlockComment(source, cursor + 2)
                source.startsWith("<!--", cursor) -> skipUntil(source, cursor + 4, "-->")
                source[cursor] == '#' -> skipUntil(source, cursor + 1, "\n")
                source.startsWith(tripleQuote, cursor) -> {
                    val end = source.indexOf(tripleQuote, cursor + tripleQuote.length)
                    if (end < 0) {
                        source.length
                    } else {
                        literals += QuotedLiteral(cursor, source.substring(cursor + tripleQuote.length, end))
                        end + tripleQuote.length
                    }
                }
                source[cursor] == '"' -> scanEscapedLiteral(source, cursor, literals)
                else -> cursor + 1
            }
        }

        return literals
    }

    private fun scanEscapedLiteral(
        source: String,
        start: Int,
        literals: MutableList<QuotedLiteral>,
    ): Int {
        var cursor = start + 1
        var escaped = false

        while (cursor < source.length) {
            val character = source[cursor]
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> {
                    literals += QuotedLiteral(start, source.substring(start + 1, cursor))
                    return cursor + 1
                }
            }
            cursor++
        }

        return source.length
    }

    private fun skipBlockComment(source: String, start: Int): Int {
        var cursor = start
        var depth = 1

        while (cursor < source.length && depth > 0) {
            when {
                source.startsWith("/*", cursor) -> {
                    depth++
                    cursor += 2
                }
                source.startsWith("*/", cursor) -> {
                    depth--
                    cursor += 2
                }
                else -> cursor++
            }
        }

        return cursor
    }

    private fun skipUntil(source: String, start: Int, terminator: String): Int {
        val end = source.indexOf(terminator, start)
        return if (end < 0) source.length else end + terminator.length
    }

    private fun lineNumber(source: String, offset: Int): Int =
        source.substring(0, offset).count { it == '\n' } + 1

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("module-pipeline-artifact")) &&
                Files.isDirectory(candidate.resolve("module-external-api"))
            ) {
                return candidate
            }
            candidate = candidate.parent
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }

    private data class QuotedLiteral(
        val offset: Int,
        val value: String,
    )

    private companion object {
        const val legacyStoragePackage = "maple.expectation.infrastructure.storage"
        const val tripleQuote = "\"\"\""

        val activeSourceRoots = listOf(
            "module-external-api/src/main",
            "module-calculator/src/main",
            "module-synchronizer/src/main",
            "module-cleanup/src/main",
        )
        val textSourceExtensions = setOf("java", "kt", "properties", "xml", "yaml", "yml")
        val forbiddenIdentityLiterals = listOf(
            "calculator/runs/",
            "ocid-mapping-parquet/",
            "ocid-mapping/",
            "cleanup/inbox/",
            "runs/",
            "/_RUNNING",
            "/_SUCCESS",
        )
    }
}
