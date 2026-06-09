package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Health
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.S3Exception

class MinioHealthIndicatorTest {

    private fun props() = MinioProperties(
        endpoint = "http://minio:9000",
        accessKey = "k",
        secretKey = "s",
        bucket = "b",
    )

    @Test
    fun `health returns UP when headBucket succeeds`() {
        val s3 = org.mockito.kotlin.mock<S3Client>(name = "happyS3")
        val indicator = MinioHealthIndicator(props(), s3)
        val health = indicator.health()
        assertThat(health.status).isEqualTo(Health.up().build().status)
        assertThat(health.details).containsEntry("bucket", "b")
        assertThat(health.details).containsEntry("endpoint", "http://minio:9000")
    }

    @Test
    fun `health returns DOWN when headBucket throws S3Exception`() {
        val s3 = org.mockito.kotlin.mock<S3Client>(name = "failingS3")
        org.mockito.kotlin.whenever(s3.headBucket(org.mockito.kotlin.any<HeadBucketRequest>()))
            .thenThrow(S3Exception.builder().statusCode(500).message("boom").build())
        val indicator = MinioHealthIndicator(props(), s3)
        val health = indicator.health()
        assertThat(health.status).isEqualTo(Health.down().build().status)
    }
}
