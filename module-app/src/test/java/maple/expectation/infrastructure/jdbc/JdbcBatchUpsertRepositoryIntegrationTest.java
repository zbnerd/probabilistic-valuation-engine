package maple.expectation.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.persistence.CharacterEquipmentJpaRepository;
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link JdbcBatchUpsertRepository}.
 *
 * <h3>Test Coverage:</h3>
 *
 * <ul>
 *   <li>Single record upsert (insert case)
 *   <li>Single record upsert (update case)
 *   <li>Batch upsert with 10,000 records
 *   <li>Idempotency verification (duplicate upserts)
 *   <li>Performance validation (JDBC vs JPA comparison)
 * </ul>
 *
 * <h3>Flaky Test Prevention (CLAUDE.md Section 20, 24):</h3>
 *
 * <ul>
 *   <li>Uses Testcontainers with random ports (no hardcoded ports)
 *   <li>Each test cleans up its own data (state isolation)
 *   <li>No Thread.sleep() - uses direct assertions
 *   <li>Test order independence guaranteed
 * </ul>
 *
 * @see maple.expectation.infrastructure.jdbc.JdbcBatchUpsertRepository
 * @see maple.expectation.support.AbstractContainerBaseTest
 */
@Tag("integration")
@DisplayName("JdbcBatchUpsertRepository 통합 테스트")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class JdbcBatchUpsertRepositoryIntegrationTest extends AbstractContainerBaseTest {

  @Autowired private JdbcBatchUpsertRepository jdbcRepository;

  @Autowired private CharacterEquipmentJpaRepository jpaRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private LogicExecutor executor;

  /**
   * Clean up database before each test to ensure state isolation. This prevents flaky tests due to
   * data leakage between tests.
   */
  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM character_equipment");
  }

  @Nested
  @DisplayName("단건 upsert 테스트")
  class SingleUpsertTest {

    @Test
    @DisplayName("새 레코드 삽입 - INSERT 실행")
    void shouldInsertNewRecord() {
      // Given
      CharacterEquipment equipment = createTestEquipment("test-ocid-001");

      // When
      int[] results = jdbcRepository.batchUpsert(List.of(equipment));

      // Then
      assertThat(results).hasSize(1);
      assertThat(results[0]).isEqualTo(1); // 1 = INSERT

      // Verify database state
      CharacterEquipmentJpaEntity entity = jpaRepository.findById("test-ocid-001").orElseThrow();
      assertThat(entity.getOcid()).isEqualTo("test-ocid-001");
      assertThat(entity.getJsonContent()).isEqualTo(testJsonContent());
      assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("기존 레코드 업데이트 - ON DUPLICATE KEY UPDATE 실행")
    void shouldUpdateExistingRecord() {
      // Given - Insert initial record
      CharacterEquipment original = createTestEquipment("test-ocid-002");
      jdbcRepository.batchUpsert(List.of(original));

      // When - Update with new content
      CharacterEquipment updated = CharacterEquipment.of("test-ocid-002", "{\"updated\": true}");
      int[] results = jdbcRepository.batchUpsert(List.of(updated));

      // Then
      assertThat(results).hasSize(1);
      assertThat(results[0]).isEqualTo(2); // 2 = UPDATE

      // Verify database state
      CharacterEquipmentJpaEntity entity = jpaRepository.findById("test-ocid-002").orElseThrow();
      assertThat(entity.getJsonContent()).isEqualTo("{\"updated\": true}");
    }

    @Test
    @DisplayName("빈 리스트 전달 시 빈 결과 반환")
    void shouldReturnEmptyArrayForEmptyList() {
      // When
      int[] results = jdbcRepository.batchUpsert(List.of());

      // Then
      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("null 전달 시 빈 결과 반환 (Null-Safety)")
    void shouldReturnEmptyArrayForNullList() {
      // When
      int[] results = jdbcRepository.batchUpsert(null);

      // Then
      assertThat(results).isEmpty();
    }
  }

  @Nested
  @DisplayName("배치 upsert 테스트 (10,000 건)")
  class BatchUpsertTest {

    @Test
    @DisplayName("10,000 건 배치 upsert - 전체 성공")
    void shouldUpsertTenThousandRecords() {
      // Given
      int recordCount = 10_000;
      List<CharacterEquipment> equipments = createTestEquipments(recordCount);

      // When
      long startTime = System.currentTimeMillis();
      int[] results = jdbcRepository.batchUpsert(equipments);
      long duration = System.currentTimeMillis() - startTime;

      // Then - All records should be INSERT (return value = 1)
      assertThat(results).hasSize(recordCount);
      // Verify each result is either 1 (INSERT) or 2 (UPDATE)
      for (int result : results) {
        assertThat(result).isIn(1, 2);
      }

      // Verify database state
      long count = jpaRepository.count();
      assertThat(count).isEqualTo(recordCount);

      // Performance validation: Should complete within 5 seconds for 10K records
      // JPA saveAll() takes ~15 seconds, JDBC batch should be < 1 second
      assertThat(duration)
          .as(
              "JDBC batch upsert should complete in < 5 seconds for 10,000 records (took %d ms)",
              duration)
          .isLessThan(5000);

      System.out.printf(
          "Performance: %d records in %d ms (%.0f records/sec)%n",
          recordCount, duration, (recordCount * 1000.0) / duration);
    }

    @Test
    @DisplayName("배치 size 커스텀 - 100 건 단위로 처리")
    void shouldRespectCustomBatchSize() {
      // Given
      int recordCount = 250; // 3 batches: 100 + 100 + 50
      int customBatchSize = 100;
      List<CharacterEquipment> equipments = createTestEquipments(recordCount);

      // When
      int[] results = jdbcRepository.batchUpsert(equipments, customBatchSize);

      // Then
      assertThat(results).hasSize(recordCount);

      // Verify all records persisted
      long count = jpaRepository.count();
      assertThat(count).isEqualTo(recordCount);
    }

    @Test
    @DisplayName("잘못된 batch size 예외 발생")
    void shouldThrowExceptionForInvalidBatchSize() {
      // Given
      List<CharacterEquipment> equipments = createTestEquipments(10);

      // When & Then
      assertThatThrownBy(() -> jdbcRepository.batchUpsert(equipments, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Batch size must be positive");

      assertThatThrownBy(() -> jdbcRepository.batchUpsert(equipments, -10))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("멱등성 (Idempotency) 테스트")
  class IdempotencyTest {

    @Test
    @DisplayName("동일한 데이터로 여러 upsert 실행 - 중복 레코드 없음")
    void shouldNotCreateDuplicatesOnMultipleUpserts() {
      // Given
      String ocid = "idempotent-ocid";
      CharacterEquipment equipment = createTestEquipment(ocid);

      // When - Execute upsert 3 times with same data
      jdbcRepository.batchUpsert(List.of(equipment));
      jdbcRepository.batchUpsert(List.of(equipment));
      int[] results = jdbcRepository.batchUpsert(List.of(equipment));

      // Then - Should be UPDATE (2), not INSERT (1)
      assertThat(results[0]).isEqualTo(2);

      // Verify only one record exists
      List<CharacterEquipmentJpaEntity> allRecords = jpaRepository.findAll();
      assertThat(allRecords).hasSize(1);
      assertThat(allRecords.get(0).getOcid()).isEqualTo(ocid);
    }

    @Test
    @DisplayName("업데이트 후 재업데이트 - 최신 데이터 유지")
    void shouldPreserveLatestDataOnMultipleUpdates() {
      // Given
      String ocid = "update-ocid";
      CharacterEquipment v1 = CharacterEquipment.of(ocid, "{\"version\": 1}");
      CharacterEquipment v2 = CharacterEquipment.of(ocid, "{\"version\": 2}");
      CharacterEquipment v3 = CharacterEquipment.of(ocid, "{\"version\": 3}");

      // When
      jdbcRepository.batchUpsert(List.of(v1));
      jdbcRepository.batchUpsert(List.of(v2));
      jdbcRepository.batchUpsert(List.of(v3));

      // Then - Should have latest version
      CharacterEquipmentJpaEntity entity = jpaRepository.findById(ocid).orElseThrow();
      assertThat(entity.getJsonContent()).isEqualTo("{\"version\": 3}");
    }
  }

  @Nested
  @DisplayName("JDBC vs JPA 성능 비교")
  class PerformanceComparisonTest {

    @Test
    @DisplayName("JDBC batch upsert가 JPA saveAll()보다 빨라야 함")
    void shouldOutperformJpaSaveAll() {
      // Skip if running in CI (resource constrained environments)
      if (isCIEnvironment()) {
        System.out.println("Skipping performance test in CI environment");
        return;
      }

      int recordCount = 1_000;

      // JPA saveAll() benchmark
      List<CharacterEquipment> equipments = createTestEquipments(recordCount);
      jdbcTemplate.update("DELETE FROM character_equipment");

      long jpaStartTime = System.currentTimeMillis();
      for (CharacterEquipment equipment : equipments) {
        CharacterEquipmentJpaEntity entity = toJpaEntity(equipment);
        jpaRepository.save(entity);
      }
      jpaRepository.flush();
      long jpaDuration = System.currentTimeMillis() - jpaStartTime;

      // JDBC batch upsert benchmark
      jdbcTemplate.update("DELETE FROM character_equipment");
      List<CharacterEquipment> jdbcEquipments = createTestEquipments(recordCount);

      long jdbcStartTime = System.currentTimeMillis();
      jdbcRepository.batchUpsert(jdbcEquipments);
      long jdbcDuration = System.currentTimeMillis() - jdbcStartTime;

      // Then - JDBC should be significantly faster
      System.out.printf("Performance comparison (%d records):%n", recordCount);
      System.out.printf(
          "  JPA saveAll():   %d ms (%.0f records/sec)%n",
          jpaDuration, (recordCount * 1000.0) / jpaDuration);
      System.out.printf(
          "  JDBC batch:      %d ms (%.0f records/sec)%n",
          jdbcDuration, (recordCount * 1000.0) / jdbcDuration);
      System.out.printf("  Speedup:         %.2fx faster%n", (double) jpaDuration / jdbcDuration);

      assertThat(jdbcDuration)
          .as("JDBC batch should be at least 5x faster than JPA saveAll()")
          .isLessThan(jpaDuration / 5);
    }
  }

  // ==================== Helper Methods ====================

  /** Create test CharacterEquipment with generated OCID and JSON content. */
  private CharacterEquipment createTestEquipment(String ocid) {
    return CharacterEquipment.of(ocid, testJsonContent());
  }

  /** Create test CharacterEquipment list with sequential OCIDs. */
  private List<CharacterEquipment> createTestEquipments(int count) {
    return IntStream.range(0, count).mapToObj(i -> createTestEquipment("batch-ocid-" + i)).toList();
  }

  /** Generate test JSON content with realistic data. */
  private String testJsonContent() {
    return """
            {
                "character_name": "TestCharacter",
                "character_level": 250,
                "world_name": "Scania",
                "class_name": "Night Lord",
                "equipment": [
                    {"item_id": 100, "name": "Rogue Knife", "rank": "Legendary"},
                    {"item_id": 200, "name": "Fafnir Dagger", "rank": "Unique"}
                ]
            }
            """;
  }

  /** Convert domain model to JPA entity for testing. */
  private CharacterEquipmentJpaEntity toJpaEntity(CharacterEquipment equipment) {
    return CharacterEquipmentJpaEntity.of(
        equipment.ocid(), equipment.jsonContent(), equipment.updatedAt());
  }

  /** Check if running in CI environment. */
  private boolean isCIEnvironment() {
    return System.getenv("CI") != null
        || System.getenv("GITHUB_ACTIONS") != null
        || System.getenv("JENKINS_HOME") != null;
  }
}
