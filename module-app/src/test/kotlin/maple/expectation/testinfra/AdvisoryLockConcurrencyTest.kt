package maple.expectation.testinfra

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Advisory Lock 동시성 검증 테스트
 *
 * <p>Single Flight가 진짜 작동하는지 검증한다.
 */
@Tag("infra-verification")
class AdvisoryLockConcurrencyTest : IntegrationTestBase() {

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `동시 10개 스레드에서 Advisory Lock이 정확히 1개만 획득된다`() {
        val lockKey = 12345L
        val acquired = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val latch = CountDownLatch(10)

        val executor = Executors.newFixedThreadPool(10)
        repeat(10) {
            executor.submit {
                try {
                    dataSource.connection.use { conn ->
                        val rs = conn.createStatement()
                            .executeQuery("SELECT pg_try_advisory_lock($lockKey)")
                        rs.next()
                        if (rs.getBoolean(1)) {
                            acquired.incrementAndGet()
                            Thread.sleep(500) // 락 보유 시간
                            conn.createStatement()
                                .execute("SELECT pg_advisory_unlock($lockKey)")
                        } else {
                            rejected.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // 동시에 도착해도 락은 1개만 획득
        assertThat(acquired.get()).isEqualTo(1)
        assertThat(rejected.get()).isEqualTo(9)
    }

    @Test
    fun `Advisory Lock 해제 후 다른 스레드가 획득할 수 있다`() {
        val lockKey = 99999L

        dataSource.connection.use { conn ->
            val rs = conn.createStatement()
                .executeQuery("SELECT pg_try_advisory_lock($lockKey)")
            rs.next()
            assertThat(rs.getBoolean(1)).isTrue()

            conn.createStatement().execute("SELECT pg_advisory_unlock($lockKey)")
        }

        // 해제 후 새 커넥션에서 즉시 획득 가능
        dataSource.connection.use { conn ->
            val rs = conn.createStatement()
                .executeQuery("SELECT pg_try_advisory_lock($lockKey)")
            rs.next()
            assertThat(rs.getBoolean(1)).isTrue()

            conn.createStatement().execute("SELECT pg_advisory_unlock($lockKey)")
        }
    }
}
