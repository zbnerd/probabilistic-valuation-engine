package maple.expectation.infrastructure.storage

import maple.pipeline.artifact.storage.LocalFsObjectStorage
import maple.pipeline.artifact.storage.MinioProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@SpringBootTest(classes = [StorageConfig::class])
@EnableConfigurationProperties(MinioProperties::class)
@Import(JacksonAutoConfiguration::class)
@TestPropertySource(
    properties = [
        "storage.backend=local",
        "storage.local.base-path=/tmp/test-storage",
        "storage.minio.endpoint=http://localhost:9000",
        "storage.minio.access-key=test",
        "storage.minio.bucket=test",
    ],
)
class StorageConfigTest {

    @Autowired
    private lateinit var objectStorage: maple.expectation.common.storage.ObjectStorage

    @Test
    fun `local backend produces LocalFsObjectStorage bean`() {
        assertThat(objectStorage).isInstanceOf(LocalFsObjectStorage::class.java)
    }
}
