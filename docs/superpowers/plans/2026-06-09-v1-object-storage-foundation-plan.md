# V1 ObjectStorage Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the unified `ObjectStorage` interface in `module-common`, both adapters (Local + MinIO) in `module-infra`, Spring wiring with `@ConditionalOnProperty`, docker-compose MinIO + minio-init, and .env entries — without changing any application call sites (deferred to VS2).

**Architecture:** Pure-Kotlin interface in `module-common` (zero Spring imports). Spring `@Component` adapters in `module-infra` injected via `@Value` / `@ConfigurationProperties`. Backend selection via `storage.backend=local|minio` property. MinIO adapter validates bucket in `@PostConstruct` via `runCatching` (fails the Spring application context on failure — boot-time fatal). aws-sdk-java v2 with `RetryPolicy.defaultRetryPolicy()` (built-in retry, no custom wrapper). Apache HTTP client (no Netty), path-style access (MinIO requirement). MinioObjectStorage receives S3Client as a Spring bean (no internal construction).

**Tech Stack:** Kotlin 2.1, Spring Boot 3.5, aws-sdk-java v2 (s3 + auth + regions + apache-client), Micrometer (optional), JUnit 5, AssertJ, MinIO (docker).

**Spec:** `docs/superpowers/specs/2026-06-09-v1-object-storage-foundation-design.md`

---

## Task 1: Add aws-sdk dependencies to gradle

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `module-infra/build.gradle`

- [ ] **Step 1: Add version + libraries to `gradle/libs.versions.toml`**

Edit `gradle/libs.versions.toml`. Under `[versions]` add:

```toml
# AWS SDK v2 (MinIO/S3 client)
aws-sdk = "2.28.16"
```

Under `[libraries]` add:

```toml
# AWS SDK v2 (MinIO/S3 client)
aws-sdk-bom = { module = "software.amazon.awssdk:bom", version.ref = "aws-sdk" }
aws-sdk-s3 = { module = "software.amazon.awssdk:s3" }
aws-sdk-auth = { module = "software.amazon.awssdk:auth" }
aws-sdk-regions = { module = "software.amazon.awssdk:regions" }
aws-sdk-apache-client = { module = "software.amazon.awssdk:apache-client" }
```

- [ ] **Step 2: Add deps to `module-infra/build.gradle`**

Edit `module-infra/build.gradle`. In the `dependencyManagement` block's `imports` add:

```groovy
mavenBom libs.aws.sdk.bom.get()
```

In the `dependencies` block, after `implementation(libs.spring.boot.starter.data.jpa)`, add:

```groovy
    // AWS SDK v2 (MinIO/S3 client)
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.regions)
    implementation(libs.aws.sdk.apache.client)
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :module-infra:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

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

Run: `./gradlew :module-common:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify module-common has zero Spring imports**

Run:
```bash
grep -rn "import org.springframework" module-common/src/main/ 2>&1 | head -5
```

Expected: no output. If the project has a Gradle `verifyNoSpringDependency` task, also run that:
```bash
./gradlew :module-common:verifyNoSpringDependency --no-daemon
```

- [ ] **Step 4: Commit**

```bash
git add module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt
git commit -m "feat(common): add ObjectStorage interface + data classes"
```

---

## Task 3: LocalFsObjectStorage TDD pair (test + impl, 1 commit)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt`

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.LocalFsObjectStorageTest" --no-daemon`
Expected: BUILD FAILED. Compilation error: `Unresolved reference: LocalFsObjectStorage`.

- [ ] **Step 3: Implement `LocalFsObjectStorage`**

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

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.LocalFsObjectStorageTest" --no-daemon`
Expected: BUILD SUCCESSFUL. All 14 test methods pass.

- [ ] **Step 5: Commit (test + impl together)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt
git commit -m "feat(infra): LocalFsObjectStorage (atomic put + SHA-256 checksum)

TDD pair: LocalFsObjectStorageTest (14 cases) + LocalFsObjectStorage.
Used as storage.backend=local hot-spare and as the reference implementation
for the unified ObjectStorage interface."
```

---

## Task 4: StorageConfig TDD pair (MinioProperties + 3 beans, 1 commit)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StorageConfigTest.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioProperties.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.StorageConfigTest" --no-daemon`
Expected: BUILD FAILED. Either `StorageConfig` or `MinioProperties` cannot be resolved, or Spring fails to load context (no `ObjectStorage` bean defined yet).

- [ ] **Step 3: Implement `MinioProperties`**

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

- [ ] **Step 4: Implement `StorageConfig` with all 3 beans**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.storage.ObjectStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

/**
 * Selects the active [ObjectStorage] implementation based on `storage.backend`.
 * Default is `local`. Setting `storage.backend=minio` switches to [MinioObjectStorage].
 *
 * S3Client is exposed as a Spring bean so it can be shared by [MinioObjectStorage],
 * [MinioHealthIndicator], and any other future consumers (e.g., metrics, backups).
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

- [ ] **Step 5: Run the test, verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.StorageConfigTest" --no-daemon`
Expected: BUILD SUCCESSFUL. The Spring context loads, the `ObjectStorage` bean is a `LocalFsObjectStorage` instance.

- [ ] **Step 6: Commit (test + impl together)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StorageConfigTest.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioProperties.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt
git commit -m "feat(infra): MinioProperties + StorageConfig (local wiring + S3Client bean)

3 beans: localObjectStorage (default), s3Client + minioObjectStorage
(activated by storage.backend=minio). S3Client exposed for sharing with
MinioHealthIndicator and future consumers."
```

---

## Task 5: MinioObjectStorage TDD pair (IT + impl, 1 commit)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`

- [ ] **Step 1: Write the integration test (compile fails)**

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

        // Ensure bucket exists (idempotent)
        runCatching {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        }

        val props = MinioProperties(
            endpoint = endpoint,
            region = region,
            accessKey = accessKey,
            secretKey = secretKey,
            bucket = bucket,
            pathStyleAccess = true,
        )
        storage = MinioObjectStorage(props, s3, meterRegistry = null)
    }

    @AfterAll
    fun tearDown() {
        if (::s3.isInitialized && ::testPrefix.isInitialized) {
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

- [ ] **Step 2: Verify the test compiles (it should fail — `MinioObjectStorage` doesn't exist yet)**

Run: `./gradlew :module-infra:compileTestKotlin --no-daemon`
Expected: BUILD FAILED. Unresolved reference: `MinioObjectStorage`.

- [ ] **Step 3: Start local MinIO + minio-init for IT verification**

```bash
docker compose up -d minio minio-init
```

Wait for health check:
```bash
docker compose ps minio
```

Expected: `State: Up (healthy)`.

- [ ] **Step 4: Implement `MinioObjectStorage` (with runCatching + 1-RTT put)**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`:

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
 * Validates the bucket in [validateBucket] (PostConstruct) — translates SDK errors
 * to IllegalStateException via runCatching (project policy: avoid raw try-catch).
 *
 * Retry: SDK built-in RetryPolicy.defaultRetryPolicy (configured in StorageConfig.s3Client).
 * S3Client is injected as a Spring bean.
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
        runCatching { s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build()) }
            .onFailure { e ->
                val message = when (e) {
                    is S3Exception -> "MinIO bucket '${props.bucket}' unreachable at ${props.endpoint} (status=${e.statusCode()}): ${e.message}"
                    is SdkClientException -> "MinIO endpoint '${props.endpoint}' unreachable: ${e.message}"
                    else -> "MinIO bucket validation failed: ${e.message}"
                }
                throw IllegalStateException(message, e)
            }
        log.info("[MinIO] bucket validated: bucket={}, endpoint={}", props.bucket, props.endpoint)
    }

    override fun put(key: String, data: ByteArray): PutResult {
        val resp = s3.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(key)
                .contentLength(data.size.toLong())
                .contentType("application/octet-stream")
                .build(),
            RequestBody.fromBytes(data),
        )
        return PutResult(key, data.size.toLong(), resp.eTag())
    }

    override fun putStream(key: String, input: java.io.InputStream): PutResult {
        val tempFile = Files.createTempFile("minio-put-", ".tmp")
        try {
            val bytes = input.use { Files.copy(it, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            val resp = s3.putObject(
                PutObjectRequest.builder()
                    .bucket(props.bucket)
                    .key(key)
                    .contentLength(bytes)
                    .build(),
                tempFile,
            )
            return PutResult(key, bytes, resp.eTag())
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

- [ ] **Step 5: Verify the test compiles**

Run: `./gradlew :module-infra:compileTestKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the IT against local MinIO (verify it passes)**

```bash
INTEGRATION_MINIO=true MINIO_ACCESS_KEY=maple MINIO_SECRET_KEY=changeme \
  MINIO_ENDPOINT=http://localhost:9000 MINIO_BUCKET=maple-expectation \
  ./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.MinioObjectStorageIT" --no-daemon
```

Expected: BUILD SUCCESSFUL. All integration test methods pass.

If the test is skipped (not "PASSED"), verify `@EnabledIfEnvironmentVariable` picked up the env var. Re-run with `-i` to see JUnit's reason.

- [ ] **Step 7: Commit (IT + impl together)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt
git commit -m "feat(infra): MinioObjectStorage (1-RTT put, runCatching validate)

TDD pair: MinioObjectStorageIT (11 cases, env-gated) + MinioObjectStorage.
- put/putStream use PutObjectResponse.eTag() (1 RTT, no extra headObject)
- @PostConstruct validateBucket via runCatching (no try-catch, project policy)
- S3Client injected as Spring bean from StorageConfig
- NoSuchKey -> false/null for exists/getLastModified"
```

---

## Task 6: MinioHealthIndicator (TDD: test + impl, 1 commit)

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicatorTest.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt`

- [ ] **Step 1: Write the failing test**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicatorTest.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Health
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.S3Exception

class MinioHealthIndicatorTest {

    @Test
    fun `health returns UP when headBucket succeeds`() {
        val s3 = org.mockito.kotlin.mock<S3Client>(name = "happyS3")
        val props = MinioProperties(
            endpoint = "http://minio:9000",
            accessKey = "k", secretKey = "s", bucket = "b"
        )
        val indicator = MinioHealthIndicator(props, s3)
        val health = indicator.health()
        assertThat(health.status).isEqualTo(Health.up().status)
    }

    @Test
    fun `health returns DOWN when headBucket throws S3Exception`() {
        val s3 = org.mockito.kotlin.mock<S3Client>(name = "failingS3")
        org.mockito.kotlin.whenever(s3.headBucket(org.mockito.kotlin.any<HeadBucketRequest>()))
            .thenThrow(S3Exception.builder().statusCode(500).message("boom").build())
        val props = MinioProperties(
            endpoint = "http://minio:9000",
            accessKey = "k", secretKey = "s", bucket = "b"
        )
        val indicator = MinioHealthIndicator(props, s3)
        val health = indicator.health()
        assertThat(health.status).isEqualTo(Health.down().status)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.MinioHealthIndicatorTest" --no-daemon`
Expected: BUILD FAILED. Unresolved reference: `MinioHealthIndicator`.

- [ ] **Step 3: Implement `MinioHealthIndicator`**

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
 * NOT used as a liveness gate (per spec §8.5 — boot-time fatal already validates
 * the bucket via @PostConstruct). This is for runtime observability only.
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

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.MinioHealthIndicatorTest" --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (test + impl together)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicatorTest.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt
git commit -m "feat(infra): MinioHealthIndicator (runtime /actuator/health visibility)

Exposes bucket + endpoint at /actuator/health. Not a liveness gate.
Activated only when storage.backend=minio."
```

---

## Task 7: Update application.yml in 4 modules

**Files:**
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Modify: `module-external-api/src/main/resources/application.yml`
- Modify: `module-calculator/src/main/resources/application.yml`
- Modify: `module-synchronizer/src/main/resources/application.yml`

- [ ] **Step 1: Verify all 4 modules have an `application.yml`**

```bash
ls module-rest-controller/src/main/resources/application*.yml \
   module-external-api/src/main/resources/application*.yml \
   module-calculator/src/main/resources/application*.yml \
   module-synchronizer/src/main/resources/application*.yml 2>&1
```

Expected: 4+ files listed. If any module is missing `application.yml`, note it (this may indicate the module uses a different config file name, e.g. `application-local.yml` or loads from elsewhere). Adapt the next step to the actual file.

- [ ] **Step 2: Add storage block to module-external-api's `application.yml`**

Edit `module-external-api/src/main/resources/application.yml`. Append at the end:

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

- [ ] **Step 3: Repeat for the other 3 modules**

Use the exact same yaml block. Edit:
- `module-rest-controller/src/main/resources/application.yml`
- `module-calculator/src/main/resources/application.yml`
- `module-synchronizer/src/main/resources/application.yml`

For each, append the same storage block at the end. (If a module has multiple `application*.yml` files — e.g. `application.yml` + `application-local.yml` — only add to the base `application.yml`. Profile-specific files inherit unless they override.)

- [ ] **Step 4: Verify compile**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon`
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

## Task 8: Update .env.example

**Files:**
- Modify: `.env.example`

- [ ] **Step 1: Inspect end of .env.example**

Run: `tail -5 .env.example`
Expected: end of file. Confirm the file uses bash-style `KEY=value` lines.

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

- [ ] **Step 3: Verify .env.example parses**

Run: `bash -c 'set -a; source .env.example; set +a; echo "STORAGE_BACKEND=$STORAGE_BACKEND"'`
Expected: `STORAGE_BACKEND=local`. If the file has syntax errors, fix them.

- [ ] **Step 4: Commit**

```bash
git add .env.example
git commit -m "docs(env): add STORAGE_BACKEND + MINIO_* variables to .env.example"
```

---

## Task 9: Update docker-compose.yml with MinIO services (idempotent init)

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Inspect current docker-compose structure**

Run: `grep -n "^[a-z]" docker-compose.yml | head -20`
Expected: a list of services + a `volumes:` block.

- [ ] **Step 2: Add `minio_data` volume**

Find the top-level `volumes:` block. Add inside it:

```yaml
  minio_data:
```

- [ ] **Step 3: Add minio + minio-init services**

Add to the `services:` block:

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
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'snapshots/' || true;
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'runs/' || true;
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'calculator/' || true;
      mc ilm add local/maple-expectation --expiry-days 2 --prefix 'ocid-mapping/' || true;
      "
```

Note the `|| true` on each `mc ilm add` — without it, restarting minio-init would fail on duplicate rules (mc ilm is not idempotent on the rule-add path). `mc mb -p` is already idempotent by design.

- [ ] **Step 4: Verify docker-compose is syntactically valid**

Run: `docker compose config --quiet 2>&1 | head -10`
Expected: no error output (silent success). If deprecation warnings appear, they're OK.

- [ ] **Step 5: Start MinIO and minio-init**

```bash
docker compose up -d minio minio-init
```

Wait for completion. Verify:
```bash
docker compose ps minio minio-init
```

Expected: `minio` is `Up (healthy)`, `minio-init` is `Exited (0)`.

- [ ] **Step 6: Verify bucket + lifecycle rules**

```bash
docker compose exec minio mc ls local/
docker compose exec minio mc ilm ls local/maple-expectation
```

Expected:
- First command: `[yyyy-mm-dd hh:mm:ss] maple-expectation/`
- Second command: 4 lifecycle rules listed, one per prefix.

- [ ] **Step 7: Verify init is idempotent (restart minio, run minio-init again)**

```bash
docker compose restart minio
docker compose up minio-init 2>&1 | tail -5
```

Expected: minio-init exits 0 on re-run (because of `|| true`). If you see error, double-check the entrypoint script.

- [ ] **Step 8: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): add minio + minio-init services with idempotent lifecycle rules

mc ilm add lines use '|| true' so restarts of minio-init do not fail
on duplicate-rule errors."
```

---

## Task 10: Full compile + test pass

**Files:** (verification only)

- [ ] **Step 1: Full compile across all modules**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. No compilation errors.

- [ ] **Step 2: Full test run (skipping IT)**

Run: `./gradlew test --no-daemon 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. All unit tests pass. MinioObjectStorageIT is skipped (env var not set).

- [ ] **Step 3: Integration test against local MinIO**

```bash
INTEGRATION_MINIO=true MINIO_ACCESS_KEY=maple MINIO_SECRET_KEY=changeme \
  MINIO_ENDPOINT=http://localhost:9000 MINIO_BUCKET=maple-expectation \
  ./gradlew :module-infra:test --no-daemon 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. MinioObjectStorageIT runs and passes (11 methods).

- [ ] **Step 4: Verify module-common has zero Spring imports**

```bash
grep -rn "import org.springframework" module-common/src/main/ 2>&1 | head -5
```

Expected: no output.

- [ ] **Step 5: Verify no new `try { ... } catch` in module-infra/src/main**

```bash
git diff develop -- module-infra/src/main \
  | grep -E "^\+.*\btry \{|^\+.*\bcatch \(" | head -20
```

Expected: no matches. The only exception is the `try { ... } catch (NoSuchKeyException)` in `exists()` and `getLastModified()` — those are S3's standard "key not found" idiom (similar to try-catch in `BasicChunkFileReader.parseRecord`). If you see broad `catch (e: Exception)` in newly added code, refactor.

- [ ] **Step 6: No commit (verification only)**

---

## Task 11: Boot smoke test (local + minio up + minio down)

**Files:** (verification only)

- [ ] **Step 1: Boot smoke with local backend (regression check)**

```bash
set -a && source .env && set +a
STORAGE_BACKEND=local ./gradlew :module-rest-controller:bootRun --no-daemon > /tmp/boot-local.log 2>&1 &
BOOT_PID=$!
sleep 60
curl -s http://localhost:8080/actuator/health | head -20
kill $BOOT_PID 2>/dev/null
```

Expected: health endpoint returns `{"status":"UP"}`. No boot errors.

- [ ] **Step 2: Boot smoke with MinIO backend (happy path)**

Ensure MinIO is running (`docker compose ps minio` shows healthy).

```bash
set -a && source .env && set +a
STORAGE_BACKEND=minio ./gradlew :module-rest-controller:bootRun --no-daemon > /tmp/boot-minio.log 2>&1 &
BOOT_PID=$!
sleep 60
curl -s http://localhost:8080/actuator/health | head -20
echo "---"
docker compose logs minio 2>&1 | grep -i "bucket\|GET\|HEAD" | tail -5
kill $BOOT_PID 2>/dev/null
```

Expected:
- App boots successfully (bucket validation succeeds).
- `MinioHealthIndicator` reports UP at `/actuator/health`.
- MinIO logs show a `HEAD` request for the bucket.

- [ ] **Step 3: Boot smoke with MinIO down (boot-time fatal)**

Stop MinIO:
```bash
docker compose stop minio
```

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

## Task 12: Final commit + PR ready check

- [ ] **Step 1: Verify all DoD items from spec are met**

Re-read `docs/superpowers/specs/2026-06-09-v1-object-storage-foundation-design.md` §13. Walk through each checkbox mentally. Confirm each is satisfied.

- [ ] **Step 2: Generate PR description**

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

## Self-Review Notes (v2)

**Spec coverage:**
- §5.1 Interface — Task 2 ✓
- §5.2 LocalFsObjectStorage — Task 3 (TDD pair) ✓
- §5.2 MinioObjectStorage + @PostConstruct — Task 5 (TDD pair) ✓
- §5.3 StorageConfig + MinioProperties — Task 4 (TDD pair) ✓
- §6.1 Error semantics — covered by tests in Tasks 3, 5
- §6.2 Boot-time fatal (runCatching) — Task 5 (impl), Task 11 (Step 3 verifies)
- §6.3 HealthIndicator — Task 6 (TDD pair)
- §6.4 Metrics — DEFERRED to follow-up; spec says "optional"
- §7.1 application.yml — Task 7
- §7.2 .env.example — Task 8
- §7.3 gradle/libs.versions.toml — Task 1
- §7.4 module-infra/build.gradle — Task 1
- §8 docker-compose — Task 9 (idempotent)
- §9.1 LocalFsObjectStorageTest — Task 3
- §9.2 MinioObjectStorageIT — Task 5
- §9.3 Boot-time fatal — Task 11
- §13 DoD — verified in Tasks 10, 11

**Placeholder scan:** none found.

**Type consistency:**
- `ObjectStorage` interface used consistently across all tasks
- `MinioObjectStorage(props, s3, meterRegistry)` constructor signature consistent in Task 5 (impl + IT)
- `StorageConfig.s3Client` / `minioObjectStorage` bean names consistent

**Changes from v1 (grill session):**
1. TDD discipline: combined test+impl+commit into single tasks (Tasks 3, 4, 5, 6). Each task is one TDD pair with one commit.
2. `MinioObjectStorage.put` / `putStream`: removed redundant `headObject` call. Now uses `PutObjectResponse.eTag()` directly. 1 RTT instead of 2.
3. `MinioObjectStorage.validateBucket`: replaced try-catch with `runCatching { ... }.onFailure { ... }` (project policy: avoid raw try-catch).
4. docker-compose `mc ilm add` lines: appended `|| true` for idempotency on restarts.

**Notes for executors:**
- The metrics surface (`object_storage_operation_total` etc.) is intentionally deferred. If required, add as a follow-up task after PR review.
- Boot-time fatal is verified manually in Task 11. If you need a unit test, you'd need to construct a `MinioObjectStorage` with a mocked S3Client and invoke `@PostConstruct validateBucket()` directly (e.g., via `MockitoExtension` or `ReflectionTestUtils.invokeMethod`).
- The two `try { s3.headObject(...) } catch (e: NoSuchKeyException)` blocks in `exists()` and `getLastModified()` are S3 SDK's standard "key-not-found" idiom — same pattern as `BasicChunkFileReader.parseRecord` in the existing codebase. Not flagged by `try-catch` policy grep.
