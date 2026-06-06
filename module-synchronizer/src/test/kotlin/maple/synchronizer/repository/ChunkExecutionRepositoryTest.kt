package maple.synchronizer.repository

import maple.expectation.common.event.ChunkExecutionIdentity
import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.common.event.ChunkExecutionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

class ChunkExecutionRepositoryTest {

    @BeforeEach
    fun resetTable() {
        jdbcTemplate.execute("TRUNCATE TABLE chunk_execution RESTART IDENTITY")
    }

    @Test
    fun `duplicate insert same identity returns true then false and keeps one row`() {
        val first = repository.insertPendingIfAbsent(command())
        val second = repository.insertPendingIfAbsent(command())

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(rowCount()).isEqualTo(1)
        assertThat(repository.findStatus(identity)).isEqualTo(ChunkExecutionStatus.Pending)
    }

    @Test
    fun `find execution state returns status retry lease and attempt count`() {
        val nextRetryAt = Instant.parse("2026-05-18T10:00:00Z")
        repository.insertPendingIfAbsent(command())
        val claim = requireNotNull(repository.claimProcessing(identity, Duration.ofMinutes(10)))
        repository.markFailedRetryable(identity, claim.attemptCount, "temporary failure", nextRetryAt)

        val state = repository.findExecutionState(identity)

        assertThat(state?.status).isEqualTo(ChunkExecutionStatus.FailedRetryable(nextRetryAt))
        assertThat(state?.nextRetryAt).isEqualTo(nextRetryAt)
        assertThat(state?.leaseUntil).isNull()
        assertThat(state?.attemptCount).isEqualTo(1)
    }

    @Test
    fun `claim PENDING changes status to PROCESSING with first attempt and lease`() {
        repository.insertPendingIfAbsent(command())

        val claimed = repository.claimProcessing(identity, Duration.ofMinutes(10))

        val row = row()
        assertThat(claimed?.attemptCount).isEqualTo(1)
        assertThat(row.status).isEqualTo("PROCESSING")
        assertThat(row.attemptCount).isEqualTo(1)
        assertThat(row.leaseUntil).isNotNull()
    }

    @Test
    fun `non-expired PROCESSING claim returns false`() {
        repository.insertPendingIfAbsent(command())
        repository.claimProcessing(identity, Duration.ofMinutes(10))

        val claimed = repository.claimProcessing(identity, Duration.ofMinutes(10))

        val row = row()
        assertThat(claimed).isNull()
        assertThat(row.status).isEqualTo("PROCESSING")
        assertThat(row.attemptCount).isEqualTo(1)
    }

    @Test
    fun `expired PROCESSING reclaims and increments attempt count`() {
        repository.insertPendingIfAbsent(command())
        repository.claimProcessing(identity, Duration.ofMinutes(10))
        expireLease()

        val claimed = repository.claimProcessing(identity, Duration.ofMinutes(10))

        val row = row()
        assertThat(claimed?.attemptCount).isEqualTo(2)
        assertThat(row.status).isEqualTo("PROCESSING")
        assertThat(row.attemptCount).isEqualTo(2)
        assertThat(row.leaseUntil).isNotNull()
    }

    @Test
    fun `retryable failure with null retry time remains claimable`() {
        repository.insertPendingIfAbsent(command())
        val firstClaim = requireNotNull(repository.claimProcessing(identity, Duration.ofMinutes(10)))
        repository.markFailedRetryable(identity, firstClaim.attemptCount, "temporary failure", Instant.now().minusSeconds(60))
        clearNextRetryAt()

        val claimed = repository.claimProcessing(identity, Duration.ofMinutes(10))

        val row = row()
        assertThat(claimed?.attemptCount).isEqualTo(2)
        assertThat(row.status).isEqualTo("PROCESSING")
        assertThat(row.attemptCount).isEqualTo(2)
    }

    @Test
    fun `mark success changes PROCESSING to SUCCEEDED and clears lease`() {
        repository.insertPendingIfAbsent(command())
        val claim = requireNotNull(repository.claimProcessing(identity, Duration.ofMinutes(10)))

        val updated = repository.markSucceeded(identity, claim.attemptCount)

        val row = row()
        assertThat(updated).isTrue()
        assertThat(row.status).isEqualTo("SUCCEEDED")
        assertThat(row.nextRetryAt).isNull()
        assertThat(row.lastError).isNull()
        assertThat(row.terminalReason).isNull()
        assertThat(row.leaseUntil).isNull()
    }

    @Test
    fun `mark retryable failure populates failure timestamps retry time and error`() {
        val nextRetryAt = Instant.parse("2026-05-18T10:00:00Z")
        repository.insertPendingIfAbsent(command())
        val claim = requireNotNull(repository.claimProcessing(identity, Duration.ofMinutes(10)))

        val updated = repository.markFailedRetryable(identity, claim.attemptCount, "temporary failure", nextRetryAt)

        val row = row()
        assertThat(updated).isTrue()
        assertThat(row.status).isEqualTo("FAILED_RETRYABLE")
        assertThat(row.firstFailedAt).isNotNull()
        assertThat(row.lastFailedAt).isNotNull()
        assertThat(row.nextRetryAt).isEqualTo(Timestamp.from(nextRetryAt))
        assertThat(row.lastError).isEqualTo("temporary failure")
        assertThat(row.terminalReason).isNull()
        assertThat(row.leaseUntil).isNull()
    }

    @Test
    fun `mark terminal failure populates terminal reason and clears retry time`() {
        repository.insertPendingIfAbsent(command())
        val claim = requireNotNull(repository.claimProcessing(identity, Duration.ofMinutes(10)))

        val updated = repository.markFailedTerminal(identity, claim.attemptCount, "bad schema", "UNSUPPORTED_SCHEMA")

        val row = row()
        assertThat(updated).isTrue()
        assertThat(row.status).isEqualTo("FAILED_TERMINAL")
        assertThat(row.firstFailedAt).isNotNull()
        assertThat(row.lastFailedAt).isNotNull()
        assertThat(row.lastError).isEqualTo("bad schema")
        assertThat(row.terminalReason).isEqualTo("UNSUPPORTED_SCHEMA")
        assertThat(row.nextRetryAt).isNull()
        assertThat(row.leaseUntil).isNull()
    }

    @Test
    fun `stale expired claim cannot mark success or failure after reclaim`() {
        val nextRetryAt = Instant.parse("2026-05-18T10:00:00Z")
        repository.insertPendingIfAbsent(command())
        val staleClaim = requireNotNull(repository.claimProcessing(identity, Duration.ofMinutes(10)))
        expireLease()
        val currentClaim = requireNotNull(repository.claimProcessing(identity, Duration.ofMinutes(10)))

        val staleSuccess = repository.markSucceeded(identity, staleClaim.attemptCount)
        val staleRetryableFailure = repository.markFailedRetryable(
            identity,
            staleClaim.attemptCount,
            "stale retryable failure",
            nextRetryAt,
        )
        val staleTerminalFailure = repository.markFailedTerminal(
            identity,
            staleClaim.attemptCount,
            "stale terminal failure",
            "STALE",
        )

        val row = row()
        assertThat(currentClaim.attemptCount).isEqualTo(2)
        assertThat(staleSuccess).isFalse()
        assertThat(staleRetryableFailure).isFalse()
        assertThat(staleTerminalFailure).isFalse()
        assertThat(row.status).isEqualTo("PROCESSING")
        assertThat(row.attemptCount).isEqualTo(2)
        assertThat(row.nextRetryAt).isNull()
        assertThat(row.lastError).isNull()
        assertThat(row.terminalReason).isNull()
        assertThat(row.leaseUntil).isNotNull()
    }

    private fun command() = InsertChunkExecutionCommand(
        identity = identity,
        topic = "result-topic",
        messageKey = "message-key",
        eventType = "CalculatorResultChunkReadyEvent",
        schemaVersion = 1,
        eventPayloadJson = """{"runId":"run-1"}""",
    )

    private fun rowCount(): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_execution", Int::class.java) ?: 0

    private fun row(): ChunkExecutionRow =
        namedJdbc.query(
            """
            SELECT
                status,
                attempt_count,
                next_retry_at,
                first_failed_at,
                last_failed_at,
                last_error,
                terminal_reason,
                lease_until
            FROM chunk_execution
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
            """.trimIndent(),
            identityParams(),
        ) { rs, _ ->
            ChunkExecutionRow(
                status = rs.getString("status"),
                attemptCount = rs.getInt("attempt_count"),
                nextRetryAt = rs.getTimestamp("next_retry_at"),
                firstFailedAt = rs.getTimestamp("first_failed_at"),
                lastFailedAt = rs.getTimestamp("last_failed_at"),
                lastError = rs.getString("last_error"),
                terminalReason = rs.getString("terminal_reason"),
                leaseUntil = rs.getTimestamp("lease_until"),
            )
        }.first()

    private fun expireLease() {
        namedJdbc.update(
            """
            UPDATE chunk_execution
            SET lease_until = now() - interval '1 second'
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
            """.trimIndent(),
            identityParams(),
        )
    }

    private fun clearNextRetryAt() {
        namedJdbc.update(
            """
            UPDATE chunk_execution
            SET next_retry_at = NULL
            WHERE execution_type = :executionType
              AND run_id = :runId
              AND endpoint = :endpoint
              AND chunk_id = :chunkId
            """.trimIndent(),
            identityParams(),
        )
    }

    private fun identityParams(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("executionType", identity.executionType.name)
            .addValue("runId", identity.runId)
            .addValue("endpoint", identity.endpoint)
            .addValue("chunkId", identity.chunkId)

    private data class ChunkExecutionRow(
        val status: String,
        val attemptCount: Int,
        val nextRetryAt: Timestamp?,
        val firstFailedAt: Timestamp?,
        val lastFailedAt: Timestamp?,
        val lastError: String?,
        val terminalReason: String?,
        val leaseUntil: Timestamp?,
    )

    private class PostgresTestContainer :
        GenericContainer<PostgresTestContainer>(DockerImageName.parse("postgres:17-alpine"))

    private companion object {
        private val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            runId = "run-1",
            endpoint = "equipment",
            chunkId = "chunk-1",
        )

        private const val TEST_SCHEMA = "chunk_execution_repository_test"

        private lateinit var jdbcTemplate: JdbcTemplate
        private lateinit var namedJdbc: NamedParameterJdbcTemplate
        private lateinit var repository: ChunkExecutionRepository
        private var postgres: PostgresTestContainer? = null

        @BeforeAll
        @JvmStatic
        fun setUpDatabase() {
            val dataSource = testDataSource()
            ResourceDatabasePopulator(ClassPathResource("db/migration/V128__chunk_execution.sql"))
                .execute(dataSource)
            jdbcTemplate = JdbcTemplate(dataSource)
            namedJdbc = NamedParameterJdbcTemplate(dataSource)
            repository = ChunkExecutionRepository(namedJdbc)
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            postgres?.stop()
        }

        private fun testDataSource(): DataSource {
            val containerDataSource = tryContainerDataSource()
            if (containerDataSource != null) {
                return containerDataSource
            }
            return localDataSource()
        }

        private fun tryContainerDataSource(): DataSource? {
            return runCatching {
                val container = PostgresTestContainer()
                    .withEnv("POSTGRES_DB", "testdb")
                    .withEnv("POSTGRES_USER", "test")
                    .withEnv("POSTGRES_PASSWORD", "test")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort())
                container.start()
                postgres = container
                schemaDataSource(
                    baseUrl = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/testdb",
                    username = "test",
                    password = "test",
                )
            }.getOrNull()
        }

        private fun localDataSource(): DataSource =
            schemaDataSource(
                baseUrl = "jdbc:postgresql://${env("PGHOST", "localhost")}:${env("PGPORT", "5432")}/${env("PGDATABASE", "postgres")}",
                username = env("PGUSER", System.getProperty("user.name")),
                password = env("PGPASSWORD", ""),
            )

        private fun schemaDataSource(baseUrl: String, username: String, password: String): DataSource {
            val baseDataSource = driverManagerDataSource(baseUrl, username, password)
            JdbcTemplate(baseDataSource).execute("CREATE SCHEMA IF NOT EXISTS $TEST_SCHEMA")
            return driverManagerDataSource("$baseUrl?currentSchema=$TEST_SCHEMA", username, password)
        }

        private fun driverManagerDataSource(url: String, username: String, password: String): DataSource =
            DriverManagerDataSource().apply {
                setDriverClassName("org.postgresql.Driver")
                this.url = url
                this.username = username
                this.password = password
            }

        private fun env(name: String, fallback: String): String = System.getenv(name) ?: fallback
    }
}
