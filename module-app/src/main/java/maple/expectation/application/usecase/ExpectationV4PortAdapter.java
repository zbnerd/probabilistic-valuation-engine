package maple.expectation.application.usecase;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.expectation.EquipmentExpectationServiceV4;
import maple.expectation.core.port.inbound.ExpectationV4Port;
import org.springframework.stereotype.Component;

/**
 * ExpectationV4Port 구현체 (ADR-005)
 *
 * <p>책임: EquipmentExpectationServiceV4에 위임
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpectationV4PortAdapter implements ExpectationV4Port {

  private final EquipmentExpectationServiceV4 expectationService;

  // Implement interface methods with presetNo parameter (Kotlin default params)
  @Override
  public CompletableFuture<Object> calculateExpectationAsync(
      String userIgn, boolean force, int presetNo) {
    return expectationService
        .calculateExpectationAsync(userIgn, force, null, presetNo)
        .thenApply(response -> response);
  }

  @Override
  public CompletableFuture<Object> calculateExpectationAsync(
      String userIgn, boolean force, String taskId, int presetNo) {
    return expectationService
        .calculateExpectationAsync(userIgn, force, taskId, presetNo)
        .thenApply(response -> response);
  }

  @Override
  public CompletableFuture<byte[]> getGzipExpectationAsync(
      String userIgn, boolean force, int presetNo) {
    return expectationService.getGzipExpectationAsync(userIgn, force, presetNo);
  }

  @Override
  public byte[] getGzipExpectation(String userIgn, boolean force, int presetNo) {
    return expectationService.getGzipExpectation(userIgn, force, presetNo);
  }

  @Override
  public Object calculateExpectation(String userIgn, boolean force, int presetNo) {
    return expectationService.calculateExpectation(userIgn, force, null, presetNo);
  }

  @Override
  public Object calculateExpectation(String userIgn, boolean force, String taskId, int presetNo) {
    return expectationService.calculateExpectation(userIgn, force, taskId, presetNo);
  }

  @Override
  public Object calculateExpectationWriteOnly(
      String userIgn, boolean force, String taskId, int presetNo) {
    return expectationService.calculateExpectationWriteOnly(userIgn, force, taskId, presetNo);
  }

  @Override
  public byte[] getGzipFromL1CacheDirect(String userIgn) {
    return expectationService.getGzipFromL1CacheDirect(userIgn);
  }
}
