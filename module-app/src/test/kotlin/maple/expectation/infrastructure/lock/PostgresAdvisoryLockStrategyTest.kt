package maple.expectation.infrastructure.lock

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Unit tests for [PostgresAdvisoryLockStrategy].
 *
 * <p><strong>Test Coverage (ADR-005):</strong>
 *
 * <ul>
 *   <li>Leader acquires lock, follower waits</li>
 <li>Lock release allows next leader</li>
 *   <li>Concurrent lock contention</li>
 *   <li>Connection pool safety</li>
 * </ul>
 *
 * @see PostgresAdvisoryLockStrategy
 * @see LeaderElectionStrategy
 */
@DisplayName("PostgresAdvisoryLockStrategy Tests")
@Tag("unit")
@Tag("advisory-lock")
@Tag("infra-verification")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "scheduler.nexon-api-collector.enabled=false",
    ],
)
@ActiveProfiles("test")
class PostgresAdvisoryLockStrategyTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var strategy: PostgresAdvisoryLockStrategy

    @BeforeEach
    override fun cleanUp() {
        // Call parent cleanUp for database cleanup
        super.cleanUp()

        // Ensure clean lock state for each test
        // pg_advisory_unlock_all() returns boolean, use execute() instead of update()
        jdbcTemplate.execute("SELECT pg_advisory_unlock_all()")
    }

    @Nested
    @DisplayName("Leader Election - Single Caller")
    inner class SingleCallerLeaderElection {

        @Test
        @DisplayName("First caller should become leader and execute leader task")
        fun `first caller should become leader`() {
            // Given
            val key = "test-single-leader"
            val leaderResult = "leader-executed"
            val followerResult = "follower-executed"

            // When
            val result = strategy.executeWithLeaderElection(
                key = key,
                waitTimeSeconds = 5,
                leaderTask = { leaderResult },
                followerTask = { followerResult },
            )

            // Then
            assertThat(result).isEqualTo(leaderResult)
        }

        @Test
        @DisplayName("Leader task exception should be propagated")
        fun `leader task exception should be propagated`() {
            // Given
            val key = "test-leader-exception"
            val expectedException = IllegalStateException("Leader failed")

            // When & Then
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                strategy.executeWithLeaderElection(
                    key = key,
                    waitTimeSeconds = 5,
                    leaderTask = { throw expectedException },
                    followerTask = { "follower" },
                )
            }.also { exception ->
                assertThat(exception.message).isEqualTo("Leader failed")
            }
        }

        @Test
        @DisplayName("Lock should be released after leader task completes")
        fun `lock should be released after leader task completes`() {
            // Given
            val key = "test-lock-release"
            val lockId = generateLockId(key)

            // When - First call acquires and releases lock
            strategy.executeWithLeaderElection(
                key = key,
                waitTimeSeconds = 5,
                leaderTask = { "first" },
                followerTask = { "follower" },
            )

            // Then - Lock should be available (not held by any session)
            val lockCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_locks
                WHERE locktype = 'advisory'
                  AND objid = $lockId
                  AND pid = pg_backend_pid()
                """.trimIndent(),
                Long::class.java,
            ) ?: 0L

            assertThat(lockCount).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("Leader Election - Sequential Callers")
    inner class SequentialCallers {

        @Test
        @DisplayName("After leader completes, next caller should become new leader")
        fun `next caller should become leader after previous leader completes`() {
            // Given
            val key = "test-sequential-leader"
            val leaderResult = "leader-executed"

            // When - First caller becomes leader
            val firstResult = strategy.executeWithLeaderElection(
                key = key,
                waitTimeSeconds = 5,
                leaderTask = { leaderResult },
                followerTask = { "follower" },
            )

            // When - Second caller should also become leader (lock released)
            val secondResult = strategy.executeWithLeaderElection(
                key = key,
                waitTimeSeconds = 5,
                leaderTask = { leaderResult },
                followerTask = { "follower" },
            )

            // Then
            assertThat(firstResult).isEqualTo(leaderResult)
            assertThat(secondResult).isEqualTo(leaderResult)
        }
    }

    @Nested
    @DisplayName("Leader Election - Concurrent Callers")
    inner class ConcurrentCallers {

        @Test
        @DisplayName("Concurrent callers should elect single leader")
        fun `concurrent callers should elect single leader`() {
            // Given
            val key = "test-concurrent-leader"
            val leaderCount = AtomicInteger(0)
            val followerCount = AtomicInteger(0)
            val completionLatch = CountDownLatch(5)
            val startLatch = CountDownLatch(1)

            val executorService = Executors.newFixedThreadPool(5)

            // When - 5 concurrent callers
            repeat(5) {
                executorService.submit {
                    try {
                        startLatch.await() // Synchronize start

                        strategy.executeWithLeaderElection(
                            key = key,
                            waitTimeSeconds = 10,
                            leaderTask = {
                                leaderCount.incrementAndGet()
                                Thread.sleep(100) // Hold lock briefly
                                "leader"
                            },
                            followerTask = {
                                followerCount.incrementAndGet()
                                "follower"
                            },
                        )
                    } finally {
                        completionLatch.countDown()
                    }
                }
            }

            startLatch.countDown() // Start all threads simultaneously
            val completed = completionLatch.await(15, TimeUnit.SECONDS)
            executorService.shutdown()

            // Then - Only one leader should be elected
            assertThat(completed).isTrue
            assertThat(leaderCount.get()).isEqualTo(1)
            assertThat(followerCount.get()).isEqualTo(4)
        }

        @Test
        @DisplayName("Followers should wait for leader completion")
        fun `followers should wait for leader completion`() {
            // Given
            val key = "test-follower-wait"
            val leaderExecutionTime = 500L
            val results = mutableListOf<String>()
            val completionLatch = CountDownLatch(3)
            val startLatch = CountDownLatch(1)

            val executorService = Executors.newFixedThreadPool(3)

            // When
            // Thread 1: Leader (slow)
            executorService.submit {
                try {
                    startLatch.await()
                    val result = strategy.executeWithLeaderElection(
                        key = key,
                        waitTimeSeconds = 10,
                        leaderTask = {
                            Thread.sleep(leaderExecutionTime)
                            results.add("leader-done")
                            "leader"
                        },
                        followerTask = {
                            results.add("follower-done")
                            "follower"
                        },
                    )
                    results.add("t1-result: $result")
                } finally {
                    completionLatch.countDown()
                }
            }

            // Thread 2 & 3: Followers (wait for leader)
            repeat(2) {
                executorService.submit {
                    try {
                        Thread.sleep(50) // Slight delay to ensure leader goes first
                        startLatch.await()
                        val result = strategy.executeWithLeaderElection(
                            key = key,
                            waitTimeSeconds = 10,
                            leaderTask = {
                                results.add("late-leader")
                                "late-leader"
                            },
                            followerTask = {
                                results.add("follower-waited")
                                "follower"
                            },
                        )
                        results.add("follower-result: $result")
                    } finally {
                        completionLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            val completed = completionLatch.await(15, TimeUnit.SECONDS)
            executorService.shutdown()

            // Then
            assertThat(completed).isTrue
            assertThat(results).contains("leader-done")
            assertThat(results).contains("follower-waited")
        }
    }

    @Nested
    @DisplayName("Connection Pool Safety")
    inner class ConnectionPoolSafety {

        @Test
        @DisplayName("Lock should not leak when using connection pool")
        fun `lock should not leak when using connection pool`() {
            // Given
            val key = "test-connection-pool"
            val lockId = generateLockId(key)

            // When - Execute multiple times
            repeat(10) {
                strategy.executeWithLeaderElection(
                    key = key,
                    waitTimeSeconds = 5,
                    leaderTask = { "result-$it" },
                    followerTask = { "follower" },
                )
            }

            // Then - No locks should be held
            val lockCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_locks
                WHERE locktype = 'advisory'
                  AND objid = $lockId
                """.trimIndent(),
                Long::class.java,
            ) ?: 0L

            assertThat(lockCount).isEqualTo(0L)
        }

        @Test
        @DisplayName("Different keys should use different lock IDs")
        fun `different keys should use different lock IDs`() {
            // Given
            val key1 = "test-key-1"
            val key2 = "test-key-2"

            // When
            val lockId1 = generateLockId(key1)
            val lockId2 = generateLockId(key2)

            // Then
            assertThat(lockId1).isNotEqualTo(lockId2)
        }

        @Test
        @DisplayName("Same key should generate same lock ID")
        fun `same key should generate same lock ID`() {
            // Given
            val key = "test-consistent-key"

            // When
            val lockId1 = generateLockId(key)
            val lockId2 = generateLockId(key)

            // Then
            assertThat(lockId1).isEqualTo(lockId2)
        }
    }

    @Nested
    @DisplayName("Follower Timeout")
    inner class FollowerTimeout {

        @Test
        @DisplayName("Follower should timeout and execute follower task after timeout")
        fun `follower should timeout and execute follower task`() {
            // Given
            val key = "test-follower-timeout"
            val followerResult = "follower-after-timeout"

            // Start a long-running leader in background
            val leaderLatch = CountDownLatch(1)
            val leaderExecutor = Executors.newSingleThreadExecutor()
            leaderExecutor.submit {
                strategy.executeWithLeaderElection(
                    key = key,
                    waitTimeSeconds = 30,
                    leaderTask = {
                        leaderLatch.countDown()
                        Thread.sleep(5000) // Hold lock for 5 seconds
                        "leader"
                    },
                    followerTask = { "leader-follower" },
                )
            }

            // Wait for leader to acquire lock
            leaderLatch.await(2, TimeUnit.SECONDS)

            // When - Follower with short timeout
            val startTime = System.currentTimeMillis()
            val result = strategy.executeWithLeaderElection(
                key = key,
                waitTimeSeconds = 1, // 1 second timeout
                leaderTask = { "should-not-be-leader" },
                followerTask = { followerResult },
            )
            val elapsed = System.currentTimeMillis() - startTime

            leaderExecutor.shutdown()
            leaderExecutor.awaitTermination(10, TimeUnit.SECONDS)

            // Then - Follower should timeout and execute follower task
            assertThat(result).isEqualTo(followerResult)
            assertThat(elapsed).isGreaterThanOrEqualTo(1000) // At least 1 second
        }

        @Test
        @DisplayName("Follower should succeed if leader releases before timeout")
        fun `follower should succeed if leader releases before timeout`() {
            // Given
            val key = "test-follower-success-before-timeout"
            val leaderLatch = CountDownLatch(1)

            // Start a short-lived leader
            val leaderExecutor = Executors.newSingleThreadExecutor()
            leaderExecutor.submit {
                strategy.executeWithLeaderElection(
                    key = key,
                    waitTimeSeconds = 5,
                    leaderTask = {
                        leaderLatch.countDown()
                        Thread.sleep(500) // Release after 500ms
                        "leader"
                    },
                    followerTask = { "leader-follower" },
                )
            }

            // Wait for leader to acquire lock
            leaderLatch.await(2, TimeUnit.SECONDS)

            // When - Follower with longer timeout
            val result = strategy.executeWithLeaderElection(
                key = key,
                waitTimeSeconds = 5,
                leaderTask = { "should-not-be-leader" },
                followerTask = { "follower-after-leader" },
            )

            leaderExecutor.shutdown()
            leaderExecutor.awaitTermination(10, TimeUnit.SECONDS)

            // Then - Follower should execute after leader releases
            assertThat(result).isEqualTo("follower-after-leader")
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandling {

        @Test
        @DisplayName("Leader task exception should release lock")
        fun `leader task exception should release lock`() {
            // Given
            val key = "test-leader-exception-release"
            val lockId = generateLockId(key)

            // When & Then
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                strategy.executeWithLeaderElection(
                    key = key,
                    waitTimeSeconds = 5,
                    leaderTask = { throw IllegalStateException("Leader error") },
                    followerTask = { "follower" },
                )
            }

            // Then - Lock should be released despite exception
            val lockCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_locks
                WHERE locktype = 'advisory'
                  AND objid = $lockId
                  AND pid = pg_backend_pid()
                """.trimIndent(),
                Long::class.java,
            ) ?: 0L

            assertThat(lockCount).isEqualTo(0L)
        }

        @Test
        @DisplayName("Follower task exception should be propagated")
        fun `follower task exception should be propagated`() {
            // Given
            val key = "test-follower-exception"
            val expectedException = IllegalStateException("Follower failed")

            // Start leader to hold lock
            val leaderLatch = CountDownLatch(1)
            val leaderExecutor = Executors.newSingleThreadExecutor()
            leaderExecutor.submit {
                strategy.executeWithLeaderElection(
                    key = key,
                    waitTimeSeconds = 5,
                    leaderTask = {
                        leaderLatch.countDown()
                        Thread.sleep(1000)
                        "leader"
                    },
                    followerTask = { "leader-follower" },
                )
            }

            // Wait for leader to acquire lock
            leaderLatch.await(2, TimeUnit.SECONDS)

            // When & Then - Follower throws exception after timeout
            try {
                strategy.executeWithLeaderElection(
                    key = key,
                    waitTimeSeconds = 1,
                    leaderTask = { "should-not-be-leader" },
                    followerTask = { throw expectedException },
                )
                org.junit.jupiter.api.fail("Expected exception to be thrown")
            } catch (e: IllegalStateException) {
                assertThat(e.message).isEqualTo("Follower failed")
            } finally {
                leaderExecutor.shutdown()
                leaderExecutor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Generate lock ID using the same method as PostgresAdvisoryLockStrategy.
     * This is a test helper to verify lock behavior.
     */
    private fun generateLockId(key: String): Long = jdbcTemplate.queryForObject(
        "SELECT hashtext(?)",
        Long::class.java,
        "latch:char:$key",
    ) ?: key.hashCode().toLong()
}
