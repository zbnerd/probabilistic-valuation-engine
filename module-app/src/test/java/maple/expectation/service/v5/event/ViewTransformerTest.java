package maple.expectation.service.v5.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import java.time.Instant;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.core.event.ExpectationCalculationCompletedEvent;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * ViewTransformer 단위 테스트
 *
 * <p>ADR-085: P1 소수점 파싱으로 데이터 손실 수정
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ViewTransformer 단위 테스트")
class ViewTransformerTest {

  private static final String TEST_USER_IGN = "testUser";
  private static final String TEST_TASK_ID = "12345";
  private static final String TEST_OCID = "ocid-123";

  @Mock private LogicExecutor executor;
  private ObjectMapper objectMapper =
      new ObjectMapper().registerModule(new KotlinModule.Builder().build());

  private ViewTransformer transformer;

  @BeforeEach
  void setUp() {
    transformer = new ViewTransformer(executor, objectMapper);

    // Generic mock that executes the task directly for any type
    Answer<Object> executeAnswer =
        invocation -> {
          ThrowingSupplier<?> task = invocation.getArgument(0);
          return task.get();
        };
    lenient()
        .when(executor.executeOrDefault(any(), any(), any(TaskContext.class)))
        .thenAnswer(executeAnswer);
  }

  @Nested
  @DisplayName("ADR-085 P1: 소수점 파싱으로 데이터 손실 수정")
  class P1DecimalParsingTests {

    @Test
    @DisplayName("소수점이 있는 비용 - 소수점 이하 절삭")
    void parseCostWithDecimal_ShouldTruncateDecimal() {
      // Given
      ExpectationCalculationCompletedEvent event = createEvent();
      event.setTotalExpectedCost("123.45");

      // When
      CharacterValuationView result = transformer.toDocument(event);

      // Then: 123.45 -> 123 (소수점 이하 절삭)
      assertThat(result.getTotalExpectedCost()).isEqualTo(123L);
    }

    @Test
    @DisplayName("천단위 콤마가 있는 비용 - 콤마 제거 후 파싱")
    void parseCostWithComma_ShouldRemoveComma() {
      // Given
      ExpectationCalculationCompletedEvent event = createEvent();
      event.setTotalExpectedCost("1,234.56");

      // When
      CharacterValuationView result = transformer.toDocument(event);

      // Then: 1,234.56 -> 1234
      assertThat(result.getTotalExpectedCost()).isEqualTo(1234L);
    }

    @Test
    @DisplayName("큰 수치 (천만 단위) - 콤마와 소수점 모두 처리")
    void parseLargeCost_ShouldHandleCommaAndDecimal() {
      // Given
      ExpectationCalculationCompletedEvent event = createEvent();
      event.setTotalExpectedCost("10,000,000.99");

      // When
      CharacterValuationView result = transformer.toDocument(event);

      // Then: 10,000,000.99 -> 10000000
      assertThat(result.getTotalExpectedCost()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("정수만 있는 비용 - 그대로 반환")
    void parseIntegerCost_ShouldReturnAsIs() {
      // Given
      ExpectationCalculationCompletedEvent event = createEvent();
      event.setTotalExpectedCost("5000");

      // When
      CharacterValuationView result = transformer.toDocument(event);

      // Then
      assertThat(result.getTotalExpectedCost()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("빈 문자열 - 0 반환")
    void parseEmptyCost_ShouldReturnZero() {
      // Given
      ExpectationCalculationCompletedEvent event = createEvent();
      event.setTotalExpectedCost("");

      // When
      CharacterValuationView result = transformer.toDocument(event);

      // Then
      assertThat(result.getTotalExpectedCost()).isEqualTo(0L);
    }

    @Test
    @DisplayName("null 비용 - 0 반환")
    void parseNullCost_ShouldReturnZero() {
      // Given
      ExpectationCalculationCompletedEvent event = createEvent();
      event.setTotalExpectedCost(null);

      // When
      CharacterValuationView result = transformer.toDocument(event);

      // Then
      assertThat(result.getTotalExpectedCost()).isEqualTo(0L);
    }
  }

  @Nested
  @DisplayName("전체 변환 테스트")
  class FullTransformationTests {

    @Test
    @DisplayName("Event를 Document로 변환 - 모든 필드 포함")
    void toDocument_ShouldTransformAllFields() {
      // Given
      ExpectationCalculationCompletedEvent event = createEvent();
      event.setTotalExpectedCost("1,234.56");
      event.setCalculatedAt(Instant.now().toString());

      // When
      CharacterValuationView result = transformer.toDocument(event);

      // Then
      assertThat(result.getUserIgn()).isEqualTo(TEST_USER_IGN);
      assertThat(result.getTotalExpectedCost()).isEqualTo(1234L);
      assertThat(result.getCharacterOcid()).isEqualTo(TEST_OCID);
      assertThat(result.getVersion()).isEqualTo(Long.parseLong(TEST_TASK_ID));
      assertThat(result.getId()).isEqualTo(TEST_USER_IGN + ":" + TEST_TASK_ID);
    }
  }

  // ==================== Helper Methods ====================

  private ExpectationCalculationCompletedEvent createEvent() {
    return ExpectationCalculationCompletedEvent.builder()
        .userIgn(TEST_USER_IGN)
        .taskId(TEST_TASK_ID)
        .messageId("msg-123")
        .characterOcid(TEST_OCID)
        .characterClass("전사")
        .characterLevel(250)
        .maxPresetNo(1)
        .calculatedAt(Instant.now().toString())
        .payload(null) // Null to skip Kotlin data class deserialization
        .build();
  }
}
