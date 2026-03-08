package maple.expectation.service.v5;

import org.springframework.boot.test.context.TestConfiguration;

/**
 * V5 CQRS Test Configuration
 *
 * <p>Provides test-specific beans for V5 integration tests.
 *
 * <p>NOTE: MongoSyncEventPublisherStub removed as part of P0-1 dual-write vulnerability fix. Event
 * publishing now uses TransactionalEventPublisher via EventOutbox pattern.
 */
@TestConfiguration
public class V5TestConfiguration {
  // Test-specific beans can be added here as needed
}
