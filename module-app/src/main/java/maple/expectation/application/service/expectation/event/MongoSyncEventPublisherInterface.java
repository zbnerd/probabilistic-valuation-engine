package maple.expectation.application.service.expectation.event;

import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4;

/** Interface for MongoSyncEventPublisher to allow stub implementation */
public interface MongoSyncEventPublisherInterface {

  void publishCalculationCompleted(String taskId, EquipmentExpectationResponseV4 response);
}
