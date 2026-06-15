package maple.expectation.infrastructure.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

/**
 * Unit test: asserts each policy JSON under docker/minio/policies/
 * - parses as valid JSON
 * - has Version 2012-10-17
 * - has at least one Allow statement
 * - never grants s3:DeleteObject in a wildcard-resource statement
 * - ext-api writes ocid-mapping; synchronizer reads it but does not write
 *
 * Runs on every build. No env var gate.
 *
 * The policies live at <project-root>/docker/minio/policies, but Gradle runs
 * tests with CWD = the module directory (module-infra/). Resolve the project
 * root by walking up from user.dir until we find a directory that contains
 * "docker/minio/policies".
 */
class MinioPolicyJsonTest {

    private val policiesDir: java.nio.file.Path = run {
        val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        // Walk up to find the project root that contains docker/minio/policies.
        // Cap the walk at 5 levels to avoid pathological loops.
        var cursor: java.nio.file.Path? = cwd
        var found: java.nio.file.Path? = null
        repeat(5) {
            val candidate = cursor?.resolve("docker/minio/policies")
            if (candidate != null && java.nio.file.Files.isDirectory(candidate)) {
                found = candidate
                return@repeat
            }
            cursor = cursor?.parent
        }
        requireNotNull(found) {
            "Could not locate docker/minio/policies starting from CWD=$cwd"
        }
    }

    private val mapper = ObjectMapper()

    @ParameterizedTest
    @ValueSource(strings = ["ext-api", "calculator", "synchronizer", "cleanup"])
    fun `policy file exists and parses`(sa: String) {
        val file = policiesDir.resolve("$sa.json").toFile()
        assertThat(file).exists()
        val tree: JsonNode = mapper.readTree(file)
        assertThat(tree.get("Version").asText()).isEqualTo("2012-10-17")
        val statements = tree.get("Statement")
        assertThat(statements.isArray).isTrue
        assertThat(statements.size()).isGreaterThan(0)
        statements.forEach { st ->
            assertThat(st.get("Effect").asText()).isEqualTo("Allow")
            val actions = st.get("Action")
            val resources = st.get("Resource")
            assertThat(actions.isArray || actions.isTextual).isTrue
            assertThat(resources.isArray || resources.isTextual).isTrue
        }
    }

    @Test
    fun `cleanup policy has no wildcard DeleteObject`() {
        val file = policiesDir.resolve("cleanup.json").toFile()
        val tree: JsonNode = mapper.readTree(file)
        val statements = tree.get("Statement")
        statements.forEach { st ->
            val actions = st.get("Action")
            val actionList: List<String> = when {
                actions.isArray -> (0 until actions.size()).map { actions.get(it).asText() }
                else -> listOf(actions.asText())
            }
            val resources = st.get("Resource")
            val resourceList: List<String> = when {
                resources.isArray -> (0 until resources.size()).map { resources.get(it).asText() }
                else -> listOf(resources.asText())
            }
            if (actionList.contains("s3:DeleteObject")) {
                resourceList.forEach { res ->
                    assertThat(res)
                        .describedAs("cleanup DeleteObject resource must NOT be a wildcard")
                        .doesNotContain(":*")
                }
            }
        }
    }

    @Test
    fun `ext-api writes ocid-mapping and synchronizer reads it but does not write`() {
        val ext = mapper.readTree(policiesDir.resolve("ext-api.json").toFile())
        val sync = mapper.readTree(policiesDir.resolve("synchronizer.json").toFile())

        fun resources(tree: JsonNode): List<String> =
            (0 until tree.get("Statement").size()).flatMap { i ->
                val res = tree.get("Statement").get(i).get("Resource")
                if (res.isArray) (0 until res.size()).map { res.get(it).asText() }
                else listOf(res.asText())
            }

        fun actions(tree: JsonNode): List<String> =
            (0 until tree.get("Statement").size()).flatMap { i ->
                val act = tree.get("Statement").get(i).get("Action")
                if (act.isArray) (0 until act.size()).map { act.get(it).asText() }
                else listOf(act.asText())
            }

        // ext-api must have ocid-mapping in its resources
        assertThat(resources(ext))
            .describedAs("ext-api must have ocid-mapping/* in resources")
            .anyMatch { it.contains("ocid-mapping") }

        // synchronizer has ocid-mapping in resources (READ) but NOT s3:PutObject
        assertThat(resources(sync))
            .describedAs("synchronizer must have ocid-mapping/* in resources (read)")
            .anyMatch { it.contains("ocid-mapping") }

        assertThat(actions(sync))
            .describedAs("synchronizer must NOT have s3:PutObject (ext-api is the sole writer)")
            .doesNotContain("s3:PutObject")
    }

    @Test
    fun `cleanup policy grants DeleteObject on prefix resources`() {
        val file = policiesDir.resolve("cleanup.json").toFile()
        val tree: JsonNode = mapper.readTree(file)
        val allActions = (0 until tree.get("Statement").size()).flatMap { i ->
            val act = tree.get("Statement").get(i).get("Action")
            if (act.isArray) (0 until act.size()).map { act.get(it).asText() }
            else listOf(act.asText())
        }
        assertThat(allActions)
            .describedAs("cleanup policy must grant s3:DeleteObject to actually clean up")
            .contains("s3:DeleteObject")
    }
}
