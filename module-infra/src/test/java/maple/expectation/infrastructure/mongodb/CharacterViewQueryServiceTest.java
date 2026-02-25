package maple.expectation.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("V5: MongoDB Query Service Tests")
class CharacterViewQueryServiceTest {

  @Mock private CharacterValuationRepository mockRepository;

  @Mock private LogicExecutor mockExecutor;

  @Mock private io.micrometer.core.instrument.MeterRegistry mockMeterRegistry;

  @Mock private io.micrometer.core.instrument.Timer mockTimer;

  @Test
  @DisplayName("MongoDB 조회 성공 시 결과 반환")
  void findByUserIgnReturnsView() throws Exception {
    CharacterValuationView view =
        new CharacterValuationView(
            null, "testUser", null, null, null, null, null, null, null, 100000L, null, null, null);

    when(mockRepository.findByUserIgn("testUser")).thenReturn(view);
    when(mockMeterRegistry.timer(anyString(), any(String[].class))).thenReturn(mockTimer);
    when(mockExecutor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<CharacterValuationView> supplier = inv.getArgument(0);
              return supplier.get();
            });

    CharacterViewQueryService service =
        new CharacterViewQueryService(
            mockRepository, mock(MongoTemplate.class), mockExecutor, mockMeterRegistry);

    var result = service.findByUserIgn("testUser");

    assertThat(result).isNotNull();
    assertThat(result.getUserIgn()).isEqualTo("testUser");
  }

  @Test
  @DisplayName("MongoDB 장애 시 null 반환")
  void mongoDBFailureReturnsNull() throws Exception {
    when(mockExecutor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            inv -> {
              return inv.getArgument(1); // return default value
            });

    CharacterViewQueryService service =
        new CharacterViewQueryService(
            mockRepository, mock(MongoTemplate.class), mockExecutor, mockMeterRegistry);

    var result = service.findByUserIgn("testUser");

    assertThat(result).isNull();
  }
}
