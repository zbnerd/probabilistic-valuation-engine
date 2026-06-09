# V1 ObjectStorage Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the unified `ObjectStorage` interface in `module-common`, both adapters (Local + MinIO) in `module-infra`, Spring wiring with `@ConditionalOnProperty`, docker-compose MinIO + minio-init, and .env entries — without changing any application call sites (deferred to VS2).

**Architecture:** Pure-Kotlin interface in `module-common` (zero Spring imports). Spring `@Component` adapters in `module-infra` injected via `@Value` / `@ConfigurationProperties`. Backend selection via `storage.backend=local|minio` property. MinIO adapter validates bucket in `@PostConstruct` and fails the Spring context on failure (boot-time fatal). aws-sdk-java v2 with `RetryPolicy.defaultRetryPolicy()` (built-in retry, no custom wrapper). Apache HTTP client (no Netty), path-style access (MinIO requirement).

**Tech Stack:** Kotlin 2.1, Spring Boot 3.5, aws-sdk-java v2 (s3 + auth + regions + apache-client), Micrometer (optional), JUnit 5, AssertJ, MinIO (docker).

**Spec:** `docs/superpowers/specs/2026-06-09-v1-object-storage-foundation-design.md`

---

## Task 1: Add aws-sdk dependencies to gradle

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `module-infra/build.gradle`

- [ ] **Step 1: Add version + libraries to `gradle/libs.versions.toml`**

Edit `gradle/libs.versions.toml`. Under `[versions]` add at the bottom (keep alphabetical-ish ordering by following the existing pattern — the file is loosely ordered):

```toml
# AWS SDK v2 (MinIO/S3 client)
aws-sdk = "2.28.16"
```

Under `[libraries]` add at the bottom:

```toml
# AWS SDK v2 (MinIO/S3 client)
aws-sdk-bom = { module = "software.amazon.awssdk:bom", version.ref = "aws-sdk" }
aws-sdk-s3 = { module = "software.amazon.awssdk:s3" }
aws-sdk-auth = { module = "software.amazon.awssdk:auth" }
aws-sdk-regions = { module = "software.amazon.awssdk:regions" }
aws-sdk-apache-client = { module = "software.amazon.awssdk:apache-client" }
```

- [ ] **Step 2: Add deps to `module-infra/build.gradle`**

Edit `module-infra/build.gradle`. Find the `dependencyManagement` block (already imports Spring Boot, Resilience4j, Testcontainers BOMs). Inside its `imports` block add:

```groovy
mavenBom libs.aws.sdk.bom.get()
```

Result:
```groovy
dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"
        mavenBom "io.github.resilience4j:resilience4j-bom:${libs.versions.resilience4j.get()}"
        mavenBom "org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}"
        mavenBom libs.aws.sdk.bom.get()
    }
}
```

In the `dependencies { ... }` block, after the existing `implementation(libs.spring.boot.starter.data.jpa)` line, add:

```groovy
    // AWS SDK v2 (MinIO/S3 client)
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.regions)
    implementation(libs.aws.sdk.apache.client)
```

- [ ] **Step 3: Verify compile**

Run:
```bash
./gradlew :module-infra:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. The new deps are downloaded; no source changes yet.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml module-infra/build.gradle
git commit -m "build(infra): add aws-sdk-java v2 dependencies (MinIO foundation)"
```

---

## Task 2: Create ObjectStorage interface in module-common

**Files:**
- Create: `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt`

- [ ] **Step 1: Create the interface file**

Create `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt`:

```kotlin
package maple.expectation.common.storage

import java.io.InputStream
import java.time.Instant

/**
 * Unified object storage abstraction. Replaces the three local filesystem port
 * interfaces (SnapshotObjectStore, ExternalApiArtifactStorePort, calculator's
 * local ObjectStorage) plus direct Paths.get() access in synchronizer readers.
 *
 * Implementations: LocalFsObjectStorage (module-infra), MinioObjectStorage (module-infra).
 * Selected at boot via storage.backend=local|minio property.
 */
interface ObjectStorage {
    /** Put data. Returns PutResult with key, size, and checksum (SHA-256 hex for Local, S3 ETag for MinIO). */
    fun put(key: String, data: ByteArray): PutResult

    /** Put data from a stream. Caller is responsible for closing `input`. */
    fun putStream(key: String, input: InputStream): PutResult

    /** Get object as bytes. Throws if key not found. */
    fun get(key: String): ByteArray

    /** Get object as InputStream. Caller is responsible for closing the stream. Throws if key not found. */
    fun getStream(key: String): InputStream

    /** Delete object. No-op if key not found. */
    fun delete(key: String)

    /** True if key exists. */
    fun exists(key: String): Boolean

    /** List all objects under prefix (eager). Returns empty list if prefix has no objects. */
    fun listByPrefix(prefix: String): List<ObjectInfo>

    /** Delete all objects under prefix. Returns total bytes deleted. */
    fun deleteByPrefix(prefix: String): Long

    /** Sum of object sizes under prefix. Returns 0 if prefix empty. */
    fun calculatePrefixSize(prefix: String): Long

    /** Last-modified timestamp. Null if key not found. */
    fun getLastModified(key: String): Instant?
}

data class ObjectInfo(
    val key: String,
    val size: Long,
    val lastModified: Instant,
    /** S3 ETag (MD5 for single-part, composite for multipart). Null for Local. */
    val etag: String? = null,
)

data class PutResult(
    val key: String,
    val size: Long,
    /**
     * Checksum. SHA-256 hex for Local; S3 ETag for MinIO.
     * Callers must NOT assume algorithm. Use only for debug/metrics.
     */
    val checksum: String?,
)
```

- [ ] **Step 2: Verify compile**

Run:
```bash
./gradlew :module-common:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify module-common has zero Spring imports**

Run:
```bash
./gradlew :module-common:verifyNoSpringDependency --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (the task is a pre-existing convention; if the task name differs in this repo, run `./gradlew :module-common:tasks | grep -i spring` to find the correct task).

If the project has no `verifyNoSpringDependency` task, run this grep instead as a manual check:

```bash
grep -rn "import org.springframework" module-common/src/main/ 2>&1 | head -5
```

Expected: no output (zero matches). The new `ObjectStorage.kt` uses only `java.io.InputStream` and `java.time.Instant`.

- [ ] **Step 4: Commit**

```bash
git add module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt
git commit -m "feat(common): add ObjectStorage interface + data classes"
```

---

## Task 3: Write LocalFsObjectStorageTest (failing test first)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt`

- [ ] **Step 1: Verify test source dir exists**

Run:
```bash
ls module-infra/src/test/kotlin/maple/expectation/infrastructure/ 2>&1 | head -5
```

Expected: directory exists (or list shows existing tests). If not present, the path will be auto-created by the first write below.

- [ ] **Step 2: Create the test file with all 10 method tests**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class LocalFsObjectStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStorage(basePath: String = tempDir.toString()): LocalFsObjectStorage =
        LocalFsObjectStorage(basePath, meterRegistry = null)

    @Test
    fun `put and get round-trip returns identical bytes`() {
        val storage = newStorage()
        val data = "hello world".toByteArray()
        storage.put("test/file.txt", data)
        val read = storage.get("test/file.txt")
        assertThat(read).isEqualTo(data)
    }

    @Test
    fun `put returns PutResult with SHA-256 hex checksum`() {
        val storage = newStorage()
        val data = "abc".toByteArray()
        val result = storage.put("test.txt", data)
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(result.checksum).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(result.size).isEqualTo(data.size.toLong())
    }

    @Test
    fun `put creates parent directories automatically`() {
        val storage = newStorage()
        storage.put("deeply/nested/path/file.txt", "data".toByteArray())
        assertThat(Files.exists(tempDir.resolve("deeply/nested/path/file.txt"))).isTrue
    }

    @Test
    fun `put is atomic - no tmp file remains after success`() {
        val storage = newStorage()
        storage.put("test.txt", "data".toByteArray())
        val tmpFiles = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `exists returns true after put, false for missing key`() {
        val storage = newStorage()
        storage.put("present.txt", "data".toByteArray())
        assertThat(storage.exists("present.txt")).isTrue
        assertThat(storage.exists("missing.txt")).isFalse
    }

    @Test
    fun `get on missing key throws NoSuchFileException`() {
        val storage = newStorage()
        org.junit.jupiter.api.assertThrows<java.nio.file.NoSuchFileException> {
            storage.get("missing.txt")
        }
    }

    @Test
    fun `delete on missing key is no-op`() {
        val storage = newStorage()
        // Should not throw
        storage.delete("missing.txt")
    }

    @Test
    fun `deleteByPrefix removes nested files and returns byte count`() {
        val storage = newStorage()
        storage.put("runs/run-1/chunk-1.gz", "12345".toByteArray())  // 5 bytes
        storage.put("runs/run-1/chunk-2.gz", "678".toByteArray())     // 3 bytes
        storage.put("runs/run-2/chunk-1.gz", "abc".toByteArray())     // 3 bytes
        val deleted = storage.deleteByPrefix("runs/run-1/")
        assertThat(deleted).isEqualTo(8L)
        assertThat(storage.exists("runs/run-1/chunk-1.gz")).isFalse
        assertThat(storage.exists("runs/run-1/chunk-2.gz")).isFalse
        assertThat(storage.exists("runs/run-2/chunk-1.gz")).isTrue
    }

    @Test
    fun `listByPrefix returns nested objects with full depth`() {
        val storage = newStorage()
        storage.put("runs/run-1/a.gz", "1".toByteArray())
        storage.put("runs/run-1/sub/b.gz", "2".toByteArray())
        storage.put("runs/run-2/c.gz", "3".toByteArray())
        val keys = storage.listByPrefix("runs/run-1/").map { it.key }
        assertThat(keys).containsExactlyInAnyOrder(
            "runs/run-1/a.gz",
            "runs/run-1/sub/b.gz",
        )
    }

    @Test
    fun `listByPrefix returns empty list for non-existent prefix`() {
        val storage = newStorage()
        val result = storage.listByPrefix("nonexistent/")
        assertThat(result).isEmpty()
    }

    @Test
    fun `getLastModified returns null for missing key`() {
        val storage = newStorage()
        val result = storage.getLastModified("missing.txt")
        assertThat(result).isNull()
    }

    @Test
    fun `getLastModified returns Instant for existing key`() {
        val storage = newStorage()
        storage.put("present.txt", "data".toByteArray())
        val result = storage.getLastModified("present.txt")
        assertThat(result).isNotNull
        assertThat(result).isBeforeOrEqualTo(Instant.now())
    }

    @Test
    fun `calculatePrefixSize matches sum of file sizes`() {
        val storage = newStorage()
        storage.put("p/a.txt", "12345".toByteArray())  // 5
        storage.put("p/b.txt", "678".toByteArray())     // 3
        assertThat(storage.calculatePrefixSize("p/")).isEqualTo(8L)
    }

    @Test
    fun `calculatePrefixSize returns 0 for non-existent prefix`() {
        val storage = newStorage()
        assertThat(storage.calculatePrefixSize("nonexistent/")).isEqualTo(0L)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```bash
./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.LocalFsObjectStorageTest" --no-daemon
```

Expected: BUILD FAILED. Compilation error: `Unresolved reference: LocalFsObjectStorage`.

- [ ] **Step 4: Commit (test only)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt
git commit -m "test(infra): add LocalFsObjectStorageTest (failing tests for 10 methods)"
```

---

## Task 4: Implement LocalFsObjectStorage

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt`

- [ ] **Step 1: Create the implementation**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.stream.Collectors

/**
 * Local filesystem implementation of [ObjectStorage]. Used when
 * `storage.backend=local`. Acts as a hot-spare rollback target in production.
 */
@Component
class LocalFsObjectStorage(
    @Value("\${storage.local.base-path:../data}") private val basePath: String,
    // Optional Spring metrics. null when not Spring-managed (e.g., unit tests).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {

    override fun put(key: String, data: ByteArray): PutResult {
        val path = resolve(key)
        path.parent.toFile().mkdirs()
        val temp = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        Files.write(temp, data)
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return PutResult(key, data.size.toLong(), sha256Hex(data))
    }

    override fun putStream(key: String, input: java.io.InputStream): PutResult {
        val path = resolve(key)
        path.parent.toFile().mkdirs()
        val temp = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        val bytes = input.use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val hash = sha256Hex(Files.readAllBytes(path))
        return PutResult(key, bytes, hash)
    }

    override fun get(key: String): ByteArray = Files.readAllBytes(resolve(key))

    override fun getStream(key: String): java.io.InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    override fun exists(key: String): Boolean = Files.exists(resolve(key))

    override fun listByPrefix(prefix: String): List<ObjectInfo> {
        val dir = resolve(prefix)
        if (!Files.exists(dir)) return emptyList()
        val base = Paths.get(basePath)
        return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { p ->
                    val relKey = base.relativize(p).toString().replace('\\', '/')
                    ObjectInfo(
                        key = relKey,
                        size = Files.size(p),
                        lastModified = Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()),
                    )
                }
                .collect(Collectors.toList())
        }
    }

    override fun deleteByPrefix(prefix: String): Long {
        val dir = resolve(prefix)
        if (!Files.exists(dir)) return 0L
        var deletedBytes = 0L
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                deletedBytes += attrs.size()
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return deletedBytes
    }

    override fun calculatePrefixSize(prefix: String): Long {
        val dir = resolve(prefix)
        if (!Files.exists(dir)) return 0L
        return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
    }

    override fun getLastModified(key: String): Instant? {
        val p = resolve(key)
        if (!Files.exists(p)) return null
        return Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis())
    }

    private fun resolve(key: String): Path {
        require(!key.startsWith("/")) { "key must be relative (no leading slash): $key" }
        require(!key.contains("..")) { "key must not contain '..': $key" }
        return Paths.get(basePath, key)
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run:
```bash
./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.LocalFsObjectStorageTest" --no-daemon
```

Expected: BUILD SUCCESSFUL. All 14 test methods pass.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt
git commit -m "feat(infra): implement LocalFsObjectStorage (atomic put + SHA-256 checksum)"
```

---

## Task 5: Write StorageConfig binding test

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StorageConfigTest.kt`

- [ ] **Step 1: Create the test file**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StorageConfigTest.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest(
    classes = [StorageConfig::class, MinioProperties::class],
)
@TestPropertySource(properties = [
    "storage.backend=local",
    "storage.local.base-path=/tmp/test-storage",
])
class StorageConfigTest {

    @Autowired
    private lateinit var objectStorage: maple.expectation.common.storage.ObjectStorage

    @Test
    fun `local backend produces LocalFsObjectStorage bean`() {
        assertThat(objectStorage).isInstanceOf(LocalFsObjectStorage::class.java)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.StorageConfigTest" --no-daemon
```

Expected: BUILD FAILED. Either `StorageConfig` or `MinioProperties` cannot be resolved, or Spring fails to load context (no `ObjectStorage` bean defined yet).

- [ ] **Step 3: Commit (test only)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StorageConfigTest.kt
git commit -m "test(infra): add StorageConfigTest (failing test for local backend wiring)"
```

---

## Task 6: Implement MinioProperties + StorageConfig

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioProperties.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`

- [ ] **Step 1: Create MinioProperties**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioProperties.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the MinIO/S3 backend. Bound from `storage.minio.*` properties.
 * Required fields (no default): endpoint, accessKey, secretKey, bucket.
 */
@ConfigurationProperties("storage.minio")
data class MinioProperties(
    val endpoint: String,
    val region: String = "us-east-1",
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val pathStyleAccess: Boolean = true,
)
```

- [ ] **Step 2: Create StorageConfig**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Selects the active [ObjectStorage] implementation based on `storage.backend`.
 * Default is `local`. Setting `storage.backend=minio` switches to [MinioObjectStorage].
 */
@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "local", matchIfMissing = true)
    fun localObjectStorage(
        @Value("\${storage.local.base-path:../data}") basePath: String,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = LocalFsObjectStorage(basePath, meterRegistry)

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun minioObjectStorage(
        props: MinioProperties,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = MinioObjectStorage(props, meterRegistry)
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run:
```bash
./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.StorageConfigTest" --no-daemon
```

Expected: BUILD SUCCESSFUL. The Spring context loads, the `ObjectStorage` bean is a `LocalFsObjectStorage` instance.

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioProperties.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt
git commit -m "feat(infra): add MinioProperties + StorageConfig (local backend wiring)"
```

---

## Task 7: Write MinioObjectStorageIT (integration test, env-gated)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt`

- [ ] **Step 1: Verify minio-client is in test classpath**

The aws-sdk-v2 `s3` artifact is already on the main classpath (Task 1), so tests can use it directly.

Run:
```bash
grep -A 3 "aws-sdk-s3" module-infra/build.gradle
```

Expected: line present (no action needed).

- [ ] **Step 2: Create the integration test file**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.util.UUID

/**
 * Integration test for MinioObjectStorage. Runs only when INTEGRATION_MINIO=true.
 * Requires a running MinIO at MINIO_ENDPOINT (default http://localhost:9000) with
 * valid MINIO_ACCESS_KEY/MINIO_SECRET_KEY.
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioObjectStorageIT {

    private lateinit var s3: S3Client
    private lateinit var bucket: String
    private lateinit var testPrefix: String
    private lateinit var storage: MinioObjectStorage

    @BeforeAll
    fun setUp() {
        val endpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
        val accessKey = System.getenv("MINIO_ACCESS_KEY") ?: "maple"
        val secretKey = System.getenv("MINIO_SECRET_KEY") ?: "changeme"
        val region = System.getenv("MINIO_REGION") ?: "us-east-1"
        bucket = System.getenv("MINIO_BUCKET") ?: "maple-expectation"
        testPrefix = "minio-it-${UUID.randomUUID()}/"

        s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true).build()
            )
            .build()

        // Ensure bucket exists
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        } catch (e: Exception) {
            // Already exists — OK
        }

        val props = MinioProperties(
            endpoint = endpoint,
            region = region,
            accessKey = accessKey,
            secretKey = secretKey,
            bucket = bucket,
            pathStyleAccess = true,
        )
        storage = MinioObjectStorage(props, meterRegistry = null)
    }

    @AfterAll
    fun tearDown() {
        if (::s3.isInitialized && ::testPrefix.isInitialized) {
            // Delete all test objects
            val list = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).prefix(testPrefix).build()
            )
            list.contents().forEach { obj ->
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build())
            }
        }
    }

    private fun testKey(name: String): String = "$testPrefix$name"

    @Test
    fun `put and get round-trip returns identical bytes`() {
        val data = "hello world".toByteArray()
        storage.put(testKey("file.txt"), data)
        val read = storage.get(testKey("file.txt"))
        assertThat(read).isEqualTo(data)
    }

    @Test
    fun `put returns PutResult with ETag checksum`() {
        val data = "test data".toByteArray()
        val result = storage.put(testKey("etag-test.txt"), data)
        assertThat(result.checksum).isNotNull
        assertThat(result.checksum).isNotEmpty
        assertThat(result.size).isEqualTo(data.size.toLong())
    }

    @Test
    fun `exists returns true after put, false for missing key`() {
        storage.put(testKey("present.txt"), "data".toByteArray())
        assertThat(storage.exists(testKey("present.txt"))).isTrue
        assertThat(storage.exists(testKey("missing-${UUID.randomUUID()}.txt"))).isFalse
    }

    @Test
    fun `get on missing key throws NoSuchKeyException`() {
        org.junit.jupiter.api.assertThrows<software.amazon.awssdk.services.s3.model.NoSuchKeyException> {
            storage.get(testKey("missing-${UUID.randomUUID()}.txt"))
        }
    }

    @Test
    fun `delete on missing key is no-op`() {
        // Should not throw
        storage.delete(testKey("missing-${UUID.randomUUID()}.txt"))
    }

    @Test
    fun `listByPrefix returns nested objects`() {
        storage.put(testKey("nested/a.txt"), "1".toByteArray())
        storage.put(testKey("nested/sub/b.txt"), "2".toByteArray())
        val keys = storage.listByPrefix(testKey("nested/")).map { it.key }
        assertThat(keys).contains(
            testKey("nested/a.txt"),
            testKey("nested/sub/b.txt"),
        )
    }

    @Test
    fun `listByPrefix returns empty list for non-existent prefix`() {
        val result = storage.listByPrefix(testKey("nonexistent-${UUID.randomUUID()}/"))
        assertThat(result).isEmpty()
    }

    @Test
    fun `deleteByPrefix removes all matches and returns byte count`() {
        storage.put(testKey("cleanup/a.txt"), "12345".toByteArray())  // 5 bytes
        storage.put(testKey("cleanup/b.txt"), "678".toByteArray())    // 3 bytes
        val deleted = storage.deleteByPrefix(testKey("cleanup/"))
        assertThat(deleted).isEqualTo(8L)
        assertThat(storage.exists(testKey("cleanup/a.txt"))).isFalse
    }

    @Test
    fun `getLastModified returns null for missing key`() {
        val result = storage.getLastModified(testKey("missing-${UUID.randomUUID()}.txt"))
        assertThat(result).isNull()
    }

    @Test
    fun `getLastModified returns Instant for existing key`() {
        storage.put(testKey("mod.txt"), "data".toByteArray())
        val result = storage.getLastModified(testKey("mod.txt"))
        assertThat(result).isNotNull
    }

    @Test
    fun `calculatePrefixSize matches sum of object sizes`() {
        storage.put(testKey("size/a.txt"), "12345".toByteArray())
        storage.put(testKey("size/b.txt"), "678".toByteArray())
        assertThat(storage.calculatePrefixSize(testKey("size/"))).isEqualTo(8L)
    }
}
```

- [ ] **Step 3: Verify the test compiles (it should fail to find MinioObjectStorage)**

Run:
```bash
./gradlew :module-infra:compileTestKotlin --no-daemon
```

Expected: BUILD FAILED. Unresolved reference: `MinioObjectStorage`. The test class references the implementation that doesn't exist yet.

- [ ] **Step 4: Commit (test only)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt
git commit -m "test(infra): add MinioObjectStorageIT (env-gated integration tests, 10 methods)"
```

---

## Task 8: Implement MinioObjectStorage

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`

- [ ] **Step 1: Create the implementation**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.http.apache.ApacheHttpClient
import jakarta.annotation.PostConstruct
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.stream.Collectors

/**
 * MinIO/S3 implementation of [ObjectStorage]. Used when `storage.backend=minio`.
 * Validates the bucket in [validateBucket] (PostConstruct) — throws on failure,
 * causing the Spring application context to fail to start (boot-time fatal).
 *
 * Retry: SDK built-in [RetryPolicy.defaultRetryPolicy] (3 attempts, 5xx + throttling).
 * No custom retry wrapper.
 */
class MinioObjectStorage(
    private val props: MinioProperties,
    @Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {

    private val log = LoggerFactory.getLogger(MinioObjectStorage::class.java)

    private val s3: S3Client = S3Client.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey, props.secretKey)
            )
        )
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(props.pathStyleAccess).build()
        )
        .httpClient(ApacheHttpClient.builder().build())
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .retryPolicy(RetryPolicy.defaultRetryPolicy())
                .build()
        )
        .build()

    @PostConstruct
    fun validateBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
            log.info("[MinIO] bucket validated: bucket={}, endpoint={}", props.bucket, props.endpoint)
        } catch (e: S3Exception) {
            throw IllegalStateException(
                "MinIO bucket '${props.bucket}' unreachable at ${props.endpoint} (status=${e.statusCode()}): ${e.message}",
                e
            )
        } catch (e: SdkClientException) {
            throw IllegalStateException(
                "MinIO endpoint '${props.endpoint}' unreachable: ${e.message}",
                e
            )
        }
    }

    override fun put(key: String, data: ByteArray): PutResult {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(key)
                .contentLength(data.size.toLong())
                .contentType("application/octet-stream")
                .build(),
            RequestBody.fromBytes(data),
        )
        val head = s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
        return PutResult(key, head.contentLength(), head.eTag())
    }

    override fun putStream(key: String, input: java.io.InputStream): PutResult {
        val tempFile = Files.createTempFile("minio-put-", ".tmp")
        try {
            val bytes = input.use { Files.copy(it, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(props.bucket)
                    .key(key)
                    .contentLength(bytes)
                    .build(),
                tempFile,
            )
            val head = s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
            return PutResult(key, head.contentLength(), head.eTag())
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    override fun get(key: String): ByteArray =
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(props.bucket).key(key).build())
            .asByteArray()

    override fun getStream(key: String): java.io.InputStream =
        s3.getObject(GetObjectRequest.builder().bucket(props.bucket).key(key).build())

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket).key(key).build())
    }

    override fun exists(key: String): Boolean = try {
        s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    }

    override fun listByPrefix(prefix: String): List<ObjectInfo> {
        val req = ListObjectsV2Request.builder().bucket(props.bucket).prefix(prefix).build()
        return s3.listObjectsV2(req).contents().map { obj ->
            ObjectInfo(
                key = obj.key(),
                size = obj.size(),
                lastModified = obj.lastModified(),
                etag = obj.eTag(),
            )
        }
    }

    override fun deleteByPrefix(prefix: String): Long {
        var totalBytes = 0L
        var continuation: String? = null
        do {
            val listReq = ListObjectsV2Request.builder()
                .bucket(props.bucket)
                .prefix(prefix)
                .continuationToken(continuation)
                .build()
            val resp = s3.listObjectsV2(listReq)
            if (resp.contents().isNotEmpty()) {
                totalBytes += resp.contents().sumOf { it.size() }
                s3.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(props.bucket)
                        .delete { d ->
                            d.objects(
                                resp.contents().map { o ->
                                    ObjectIdentifier.builder().key(o.key()).build()
                                }
                            )
                        }
                        .build()
                )
            }
            continuation = resp.nextContinuationToken()
        } while (continuation != null)
        return totalBytes
    }

    override fun calculatePrefixSize(prefix: String): Long {
        var total = 0L
        var continuation: String? = null
        do {
            val resp = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(props.bucket)
                    .prefix(prefix)
                    .continuationToken(continuation)
                    .build()
            )
            total += resp.contents().sumOf { it.size() }
            continuation = resp.nextContinuationToken()
        } while (continuation != null)
        return total
    }

    override fun getLastModified(key: String): Instant? = try {
        s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
            .lastModified()
    } catch (e: NoSuchKeyException) {
        null
    }
}
```

- [ ] **Step 2: Verify the test compiles**

Run:
```bash
./gradlew :module-infra:compileTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify local tests still pass**

Run:
```bash
./gradlew :module-infra:test --no-daemon
```

Expected: BUILD SUCCESSFUL. `LocalFsObjectStorageTest` and `StorageConfigTest` pass. `MinioObjectStorageIT` is skipped (env var not set).

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt
git commit -m "feat(infra): implement MinioObjectStorage (aws-sdk-v2, boot-time fatal validation)"
```

---

## Task 9: Run MinioObjectStorageIT against local MinIO

**Files:** (no code changes — verification only)

- [ ] **Step 1: Start MinIO + minio-init via docker-compose**

Run (in a separate terminal, or in background):
```bash
docker compose up -d minio minio-init
```

Wait for the health check to pass. Verify with:
```bash
docker compose ps minio
```

Expected: `State: Up (healthy)`.

- [ ] **Step 2: Verify bucket exists**

Run:
```bash
docker compose exec minio mc ls local/
```

Expected output: `[yyyy-mm-dd hh:mm:ss] maple-expectation/`. If not present, check minio-init logs:
```bash
docker compose logs minio-init
```

- [ ] **Step 3: Run the integration test**

Run:
```bash
INTEGRATION_MINIO=true MINIO_ACCESS_KEY=maple MINIO_SECRET_KEY=changeme \
  MINIO_ENDPOINT=http://localhost:9000 MINIO_BUCKET=maple-expectation \
  ./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.MinioObjectStorageIT" --no-daemon
```

Expected: BUILD SUCCESSFUL. All integration test methods pass.

If any test fails, capture the failure output, fix the implementation, re-run.

- [ ] **Step 4: Verify the test was actually run (not skipped)**

Run:
```bash
INTEGRATION_MINIO=true MINIO_ACCESS_KEY=maple MINIO_SECRET_KEY=changeme \
  MINIO_ENDPOINT=http://localhost:9000 MINIO_BUCKET=maple-expectation \
  ./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.MinioObjectStorageIT" --no-daemon -i 2>&1 | grep -E "MinioObjectStorageIT|PASSED|FAILED"
```

Expected: at least 10 test methods listed with status `PASSED` (or `test` then `BUILD SUCCESSFUL`). If you see "skipped" or "Skipped", the env var is not being picked up — verify the `@EnabledIfEnvironmentVariable` annotation.

- [ ] **Step 5: No commit needed (verification only)**

---

## Task 10: Implement MinioHealthIndicator

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt`

- [ ] **Step 1: Create the indicator**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest

/**
 * Exposes MinIO bucket health at /actuator/health.
 * NOT used as a liveness gate (per spec §8.5 — boot-time fatal already validates the bucket).
 */
@Component
@ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
class MinioHealthIndicator(
    private val props: MinioProperties,
    private val s3: S3Client,
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(MinioHealthIndicator::class.java)

    override fun health(): Health = try {
        s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
        Health.up()
            .withDetail("bucket", props.bucket)
            .withDetail("endpoint", props.endpoint)
            .build()
    } catch (e: Exception) {
        log.warn("[MinIO] health check failed: {}", e.message)
        Health.down(e)
            .withDetail("bucket", props.bucket)
            .withDetail("endpoint", props.endpoint)
            .build()
    }
}
```

Note: this class depends on `S3Client` bean. The current `MinioObjectStorage` constructs its own S3Client internally rather than exposing a bean. We need to either:
- (a) Extract S3Client into a separate bean
- (b) Have `MinioObjectStorage` expose its `s3` for injection
- (c) Construct a second S3Client in MinioHealthIndicator

For minimal change, take approach (a). Move S3Client construction into `StorageConfig`:

- [ ] **Step 2: Refactor S3Client into a Spring bean**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`. Replace the `minioObjectStorage` bean with this version that also creates a shared `S3Client` bean:

```kotlin
package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "local", matchIfMissing = true)
    fun localObjectStorage(
        @Value("\${storage.local.base-path:../data}") basePath: String,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = LocalFsObjectStorage(basePath, meterRegistry)

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun s3Client(props: MinioProperties): S3Client = S3Client.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey, props.secretKey)
            )
        )
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(props.pathStyleAccess).build()
        )
        .httpClient(ApacheHttpClient.builder().build())
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .retryPolicy(RetryPolicy.defaultRetryPolicy())
                .build()
        )
        .build()

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun minioObjectStorage(
        props: MinioProperties,
        s3: S3Client,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = MinioObjectStorage(props, s3, meterRegistry)
}
```

- [ ] **Step 3: Refactor MinioObjectStorage to receive S3Client via constructor**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`. Replace the entire class with this version (S3Client is now injected, not constructed internally):

```kotlin
package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import jakarta.annotation.PostConstruct
import java.nio.file.Files
import java.time.Instant

/**
 * MinIO/S3 implementation of [ObjectStorage]. Used when `storage.backend=minio`.
 * Validates the bucket in [validateBucket] (PostConstruct) — throws on failure,
 * causing the Spring application context to fail to start (boot-time fatal).
 *
 * Retry: SDK built-in RetryPolicy.defaultRetryPolicy (3 attempts, 5xx + throttling).
 * S3Client is injected as a Spring bean (see StorageConfig.s3Client).
 */
class MinioObjectStorage(
    private val props: MinioProperties,
    private val s3: S3Client,
    @Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {

    private val log = LoggerFactory.getLogger(MinioObjectStorage::class.java)

    @PostConstruct
    fun validateBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
            log.info("[MinIO] bucket validated: bucket={}, endpoint={}", props.bucket, props.endpoint)
        } catch (e: S3Exception) {
            throw IllegalStateException(
                "MinIO bucket '${props.bucket}' unreachable at ${props.endpoint} (status=${e.statusCode()}): ${e.message}",
                e
            )
        } catch (e: SdkClientException) {
            throw IllegalStateException(
                "MinIO endpoint '${props.endpoint}' unreachable: ${e.message}",
                e
            )
        }
    }

    override fun put(key: String, data: ByteArray): PutResult {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket).key(key)
                .contentLength(data.size.toLong())
                .contentType("application/octet-stream")
                .build(),
            RequestBody.fromBytes(data),
        )
        val head = s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
        return PutResult(key, head.contentLength(), head.eTag())
    }

    override fun putStream(key: String, input: java.io.InputStream): PutResult {
        val tempFile = Files.createTempFile("minio-put-", ".tmp")
        try {
            val bytes = input.use { Files.copy(it, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(props.bucket).key(key)
                    .contentLength(bytes)
                    .build(),
                tempFile,
            )
            val head = s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
            return PutResult(key, head.contentLength(), head.eTag())
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    override fun get(key: String): ByteArray =
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(props.bucket).key(key).build())
            .asByteArray()

    override fun getStream(key: String): java.io.InputStream =
        s3.getObject(GetObjectRequest.builder().bucket(props.bucket).key(key).build())

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket).key(key).build())
    }

    override fun exists(key: String): Boolean = try {
        s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    }

    override fun listByPrefix(prefix: String): List<ObjectInfo> {
        val req = ListObjectsV2Request.builder().bucket(props.bucket).prefix(prefix).build()
        return s3.listObjectsV2(req).contents().map { obj ->
            ObjectInfo(
                key = obj.key(),
                size = obj.size(),
                lastModified = obj.lastModified(),
                etag = obj.eTag(),
            )
        }
    }

    override fun deleteByPrefix(prefix: String): Long {
        var totalBytes = 0L
        var continuation: String? = null
        do {
            val listReq = ListObjectsV2Request.builder()
                .bucket(props.bucket).prefix(prefix)
                .continuationToken(continuation)
                .build()
            val resp = s3.listObjectsV2(listReq)
            if (resp.contents().isNotEmpty()) {
                totalBytes += resp.contents().sumOf { it.size() }
                s3.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(props.bucket)
                        .delete { d ->
                            d.objects(
                                resp.contents().map { o ->
                                    ObjectIdentifier.builder().key(o.key()).build()
                                }
                            )
                        }
                        .build()
                )
            }
            continuation = resp.nextContinuationToken()
        } while (continuation != null)
        return totalBytes
    }

    override fun calculatePrefixSize(prefix: String): Long {
        var total = 0L
        var continuation: String? = null
        do {
            val resp = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(props.bucket).prefix(prefix)
                    .continuationToken(continuation)
                    .build()
            )
            total += resp.contents().sumOf { it.size() }
            continuation = resp.nextContinuationToken()
        } while (continuation != null)
        return total
    }

    override fun getLastModified(key: String): Instant? = try {
        s3.headObject(HeadObjectRequest.builder().bucket(props.bucket).key(key).build())
            .lastModified()
    } catch (e: NoSuchKeyException) {
        null
    }
}
```

- [ ] **Step 4: Update MinioObjectStorageIT constructor call (it constructs MinioObjectStorage directly)**

The IT currently calls `MinioObjectStorage(props, meterRegistry = null)`. Update to pass `s3`:

Edit `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt`. Find the line:
```kotlin
        storage = MinioObjectStorage(props, meterRegistry = null)
```

Replace with:
```kotlin
        storage = MinioObjectStorage(props, s3, meterRegistry = null)
```

- [ ] **Step 5: Verify all tests still pass**

Run:
```bash
./gradlew :module-infra:test --no-daemon
```

Expected: BUILD SUCCESSFUL. LocalFsObjectStorageTest (14 methods), StorageConfigTest (1 method), MinioObjectStorageIT (skipped) — all pass.

- [ ] **Step 6: Re-run integration test**

Run:
```bash
INTEGRATION_MINIO=true MINIO_ACCESS_KEY=maple MINIO_SECRET_KEY=changeme \
  MINIO_ENDPOINT=http://localhost:9000 MINIO_BUCKET=maple-expectation \
  ./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.MinioObjectStorageIT" --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt
git commit -m "feat(infra): extract S3Client bean + add MinioHealthIndicator"
```

---

## Task 11: Update application.yml in 4 modules

**Files:**
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Modify: `module-external-api/src/main/resources/application.yml`
- Modify: `module-calculator/src/main/resources/application.yml`
- Modify: `module-synchronizer/src/main/resources/application.yml`

- [ ] **Step 1: Inspect existing application.yml in one module**

Run:
```bash
cat module-external-api/src/main/resources/application.yml | tail -20
```

Expected: yaml content. Find a stable spot to add the storage block (typically at the end, before any profile-specific sections).

- [ ] **Step 2: Add storage block to module-external-api**

Edit `module-external-api/src/main/resources/application.yml`. At the end of the file, add:

```yaml

storage:
  backend: ${STORAGE_BACKEND:local}
  local:
    base-path: ${STORE_BASE_PATH:../data}
  minio:
    endpoint: ${MINIO_ENDPOINT:http://minio:9000}
    region: ${MINIO_REGION:us-east-1}
    access-key: ${MINIO_ACCESS_KEY:}
    secret-key: ${MINIO_SECRET_KEY:}
    bucket: ${MINIO_BUCKET:maple-expectation}
    path-style-access: true
```

Note: `access-key` and `secret-key` defaults are empty strings. Spring will throw `ConfigurationPropertiesBindException` at startup if they're empty AND `storage.backend=minio`. This is intentional — fail-fast on missing MinIO credentials.

- [ ] **Step 3: Repeat for the other 3 modules**

Use the same yaml block. Edit:
- `module-rest-controller/src/main/resources/application.yml`
- `module-calculator/src/main/resources/application.yml`
- `module-synchronizer/src/main/resources/application.yml`

For each, append the same storage block at the end.

- [ ] **Step 4: Verify compile**

Run:
```bash
./gradlew compileKotlin compileJava --continue --no-daemon
```

Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/resources/application.yml
git add module-external-api/src/main/resources/application.yml
git add module-calculator/src/main/resources/application.yml
git add module-synchronizer/src/main/resources/application.yml
git commit -m "feat(config): add storage.backend config to 4 active modules"
```

---

## Task 12: Update .env.example

**Files:**
- Modify: `.env.example`

- [ ] **Step 1: Find a stable insertion point in .env.example**

Run:
```bash
tail -10 .env.example
```

Expected: end of file. The .env.example uses bash-style `KEY=value` lines.

- [ ] **Step 2: Append storage/MinIO variables**

Append to `.env.example`:

```bash

# =============================================================================
# Storage backend (V1 / issue #1216)
# =============================================================================
STORAGE_BACKEND=local
STORE_BASE_PATH=../data

# MinIO (only required when STORAGE_BACKEND=minio)
MINIO_ROOT_USER=maple
MINIO_ROOT_PASSWORD=changeme
MINIO_ACCESS_KEY=maple
MINIO_SECRET_KEY=changeme
MINIO_ENDPOINT=http://minio:9000
MINIO_BUCKET=maple-expectation
MINIO_REGION=us-east-1
```

- [ ] **Step 3: Verify .env.example is syntactically valid**

Run:
```bash
grep -E "^[A-Z_]+=" .env.example | wc -l
```

Expected: a number > 0 (no specific count — just verify all lines parse). If the project's CI runs `set -a && source .env.example && set +a` somewhere, run that as well.

- [ ] **Step 4: Commit**

```bash
git add .env.example
git commit -m "docs(env): add STORAGE_BACKEND + MINIO_* variables to .env.example"
```

---

## Task 13: Update docker-compose.yml with MinIO services

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Inspect current docker-compose structure**

Run:
```bash
grep -n "^[a-z]" docker-compose.yml | head -20
```

Expected: a list of services. Find where to add `minio` and `minio-init` (alphabetical or end).

- [ ] **Step 2: Add the minio + minio-init services**

Edit `docker-compose.yml`. Find the top-level `volumes:` section (defines named volumes). Add to it:

```yaml
  minio_data:
```

Then add the two services to the `services:` block (alphabetically between other services, or at the end):

```yaml

  # MinIO object storage (V1 / issue #1216)
  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    volumes:
      - minio_data:/data
    networks:
      - maple-network
    ports:
      - "9000:9000"
      - "9001:9001"
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 5s
      retries: 5

  minio-init:
    image: minio/mc:latest
    depends_on:
      minio:
        condition: service_healthy
    networks:
      - maple-network
    entrypoint: |
      /bin/sh -c "
      mc alias set local http://minio:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD;
      mc mb -p local/maple-expectation || true;
      mc anonymous set none local/maple-expectation;
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'snapshots/';
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'runs/';
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'calculator/';
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'ocid-mapping/';
      "
```

- [ ] **Step 3: Verify docker-compose is syntactically valid**

Run:
```bash
docker compose config --quiet 2>&1 | head -10
```

Expected: no output (silent success) or only deprecation warnings. If there are syntax errors, fix the yaml.

- [ ] **Step 4: Start MinIO and minio-init**

Run:
```bash
docker compose up -d minio minio-init
```

Wait for minio-init to exit (it's a one-shot init job). Verify:
```bash
docker compose ps minio minio-init
```

Expected: `minio` is `Up (healthy)`, `minio-init` is `Exited (0)` (or similar successful exit).

- [ ] **Step 5: Verify bucket + lifecycle rules**

Run:
```bash
docker compose exec minio mc ls local/
docker compose exec minio mc ilm ls local/maple-expectation
```

Expected:
- First command: `[yyyy-mm-dd hh:mm:ss] maple-expectation/`
- Second command: 4 lifecycle rules listed, one per prefix.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): add minio + minio-init services with 2-day lifecycle rules"
```

---

## Task 14: Final compile + test pass

**Files:** (verification only)

- [ ] **Step 1: Full compile across all modules**

Run:
```bash
./gradlew compileKotlin compileJava --continue --no-daemon 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. No compilation errors. If `verifyNoSpringDependency` task exists for module-common, that should also pass.

- [ ] **Step 2: Full test run (skipping IT)**

Run:
```bash
./gradlew test --no-daemon 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. All unit tests pass. MinioObjectStorageIT is skipped (env var not set).

- [ ] **Step 3: Integration test against local MinIO**

Run:
```bash
INTEGRATION_MINIO=true MINIO_ACCESS_KEY=maple MINIO_SECRET_KEY=changeme \
  MINIO_ENDPOINT=http://localhost:9000 MINIO_BUCKET=maple-expectation \
  ./gradlew :module-infra:test --no-daemon 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. MinioObjectStorageIT runs and passes.

- [ ] **Step 4: Verify module-common has zero Spring imports**

Run:
```bash
grep -rn "import org.springframework" module-common/src/main/ 2>&1 | head -5
```

Expected: no output.

- [ ] **Step 5: Verify no new `!!`, `try-catch`, `join()/get()/runBlocking` introduced**

Run:
```bash
git diff develop -- module-common/src/main module-infra/src/main \
  | grep -E "!!\.|try \{|\.join\(\)|\.get\(\)|runBlocking" | head -20
```

Expected: minimal matches. The `MinioObjectStorageIT` uses `org.junit.jupiter.api.assertThrows` which is a method call, not try-catch. `Files.deleteIfExists` in a `finally` block is OK (per project policy on resource cleanup).

If you see real `try { ... } catch` blocks in `module-common` or `module-infra/src/main` (excluding the `@PostConstruct` validation which uses a deliberate `try { ... } catch (S3Exception) { throw IllegalStateException(...) }` pattern), review and refactor.

- [ ] **Step 6: No commit (verification only)**

---

## Task 15: Boot smoke test (local + minio)

**Files:** (verification only)

- [ ] **Step 1: Boot smoke with local backend (regression check)**

Run:
```bash
set -a && source .env && set +a
STORAGE_BACKEND=local ./gradlew :module-rest-controller:bootRun --no-daemon &
BOOT_PID=$!
sleep 60
curl -s http://localhost:8080/actuator/health | head -20
kill $BOOT_PID 2>/dev/null
```

Expected: health endpoint returns `{"status":"UP"}` (or similar). No boot errors.

- [ ] **Step 2: Boot smoke with MinIO backend (happy path)**

Ensure MinIO is running (`docker compose ps minio` shows healthy).

Run:
```bash
set -a && source .env && set +a
STORAGE_BACKEND=minio ./gradlew :module-rest-controller:bootRun --no-daemon &
BOOT_PID=$!
sleep 60
curl -s http://localhost:8080/actuator/health | head -20
echo "---"
docker compose logs minio | grep -i "bucket\|GET\|HEAD" | tail -5
kill $BOOT_PID 2>/dev/null
```

Expected: 
- App boots successfully (the bucket validation succeeds).
- `MinioHealthIndicator` reports UP at `/actuator/health`.
- MinIO logs show a `HEAD` request for the bucket.

- [ ] **Step 3: Boot smoke with MinIO down (boot-time fatal)**

Stop MinIO:
```bash
docker compose stop minio
```

Run:
```bash
set -a && source .env && set +a
STORAGE_BACKEND=minio ./gradlew :module-rest-controller:bootRun --no-daemon > /tmp/boot-without-minio.log 2>&1 &
BOOT_PID=$!
sleep 30
grep -E "IllegalStateException|MinIO.*unreachable|bucket.*unreachable" /tmp/boot-without-minio.log | head -5
kill $BOOT_PID 2>/dev/null
docker compose start minio
```

Expected: log shows `IllegalStateException: MinIO endpoint '...' unreachable: ...` or similar boot-time fatal error. The Spring application context fails to start.

- [ ] **Step 4: Cleanup**

```bash
docker compose down
```

- [ ] **Step 5: No commit (verification only)**

---

## Task 16: Final commit + PR ready check

- [ ] **Step 1: Verify all DoD items from spec are met**

Re-read `docs/superpowers/specs/2026-06-09-v1-object-storage-foundation-design.md` §13. Walk through each checkbox mentally. Confirm each is satisfied.

- [ ] **Step 2: Generate PR description**

Run:
```bash
git log --oneline develop..HEAD
```

Use the output to draft a PR description. Suggested title: `feat(infra): V1 ObjectStorage foundation (issue #1216)`. Body should reference the spec and list the new files.

- [ ] **Step 3: Final commit if any leftover changes**

If during the verification steps you made any tweaks (e.g., a typo fix), commit them:

```bash
git add -A
git status  # sanity check
git commit -m "chore(infra): VS1 final adjustments"
```

(Skip this step if there are no leftover changes.)

- [ ] **Step 4: Push branch + open PR**

```bash
git push origin HEAD
gh pr create --base develop --title "feat(infra): V1 ObjectStorage foundation (issue #1216)" --body "..."
```

---

## Self-Review Notes

**Spec coverage:**
- §5.1 Interface — Task 2 ✓
- §5.2 LocalFsObjectStorage — Tasks 3, 4 ✓
- §5.2 MinioObjectStorage + @PostConstruct — Tasks 7, 8, 10 ✓
- §5.3 StorageConfig + MinioProperties — Tasks 5, 6 ✓
- §6.1 Error semantics — covered by tests in Tasks 3, 7
- §6.2 Boot-time fatal — Task 8 (validateBucket), Task 15 (Step 3 verifies)
- §6.3 HealthIndicator — Task 10
- §6.4 Metrics — DEFERRED to a follow-up task; spec says "optional" so not strictly DoD
- §7.1 application.yml — Task 11
- §7.2 .env.example — Task 12
- §7.3 gradle/libs.versions.toml — Task 1
- §7.4 module-infra/build.gradle — Task 1
- §8 docker-compose — Task 13
- §9.1 LocalFsObjectStorageTest — Task 3
- §9.2 MinioObjectStorageIT — Task 7
- §13 DoD — verified in Tasks 14, 15

**Placeholder scan:** none found.

**Type consistency:**
- `ObjectStorage` interface used consistently across all tasks
- `MinioObjectStorage(props, s3, meterRegistry)` constructor signature consistent in Tasks 8, 10
- `StorageConfig.localObjectStorage` / `s3Client` / `minioObjectStorage` bean names consistent

**Notes for executors:**
- The `MinioObjectStorage` constructor signature changed between Task 8 and Task 10. Task 8 creates `S3Client` internally; Task 10 refactors to inject it. The IT class needs the same update (Task 10 Step 4).
- The metrics surface (`object_storage_operation_total` etc.) is intentionally deferred. If required, add it as a follow-up task after PR review.
- Boot-time fatal is best verified in Task 15. If Task 15 Step 3 fails to show the expected `IllegalStateException`, check that the `MinioObjectStorage` bean is being eagerly initialized (it should be by default — Spring Boot does not lazy-init beans by default).
