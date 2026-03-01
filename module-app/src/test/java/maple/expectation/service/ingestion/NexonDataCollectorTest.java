package maple.expectation.service.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import maple.expectation.core.port.out.EventPublisher;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.support.TestLogicExecutors;
import maple.expectation.testfixtures.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

/** Unit tests for {@link NexonDataCollector}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("NexonDataCollector Tests (Reactive)")
class NexonDataCollectorTest {

  @Mock(name = "mapleWebClient")
  private WebClient webClient;

  @Mock private EventPublisher eventPublisher;

  private NexonDataCollector collector;

  @BeforeEach
  void setUp() {
    LogicExecutor executor = TestLogicExecutors.passThrough();
    collector = new NexonDataCollector(webClient, eventPublisher, null);
    ReflectionTestUtils.setField(collector, "apiKey", "test-api-key");
    ReflectionTestUtils.setField(collector, "executor", executor);
  }

  @Test
  @DisplayName("fetchAndPublish() should fetch from API and publish event")
  @org.junit.jupiter.api.Disabled("Requires MockWebServer for WebClient testing")
  void testFetchAndPublish_Success() {
    // Given - use positional arguments for Java
    String ocid = "test-ocid-123";
    NexonApiCharacterData expectedData =
        Fixtures.nexonApiCharacterData(
            null, // id
            ocid, // ocid
            "TestCharacter", // characterName
            "Scania", // worldName
            "Night Lord", // characterClass
            250, // characterLevel
            null, // guildName
            null, // characterImageUrl
            null // date
            );

    doReturn(CompletableFuture.completedFuture(null))
        .when(eventPublisher)
        .publishAsync(eq("nexon-data"), any(IntegrationEvent.class));

    // Requires MockWebServer for complete WebClient testing
  }

  @Test
  @DisplayName("fetchAndPublish() should handle API failure with ExternalServiceException")
  @org.junit.jupiter.api.Disabled("Requires MockWebServer for WebClient testing")
  void testFetchAndPublish_ApiFailure() {
    // Given
    String ocid = "test-ocid-123";
    // Requires MockWebServer for complete WebClient error testing
  }

  @Test
  @DisplayName("fetchAndPublish() should publish event even if publishAsync fails")
  @org.junit.jupiter.api.Disabled("Requires MockWebServer for WebClient testing")
  void testFetchAndPublish_PublishFailure() {
    // Given - use positional arguments for Java
    String ocid = "test-ocid-123";
    NexonApiCharacterData expectedData =
        Fixtures.nexonApiCharacterData(
            null, ocid, "TestCharacter", null, null, null, null, null, null);

    doReturn(CompletableFuture.failedFuture(new RuntimeException("Queue unavailable")))
        .when(eventPublisher)
        .publishAsync(eq("nexon-data"), any(IntegrationEvent.class));

    // Requires MockWebServer for complete WebClient testing
  }
}
