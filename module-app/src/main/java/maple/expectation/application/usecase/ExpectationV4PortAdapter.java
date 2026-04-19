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

  @Override
  public CompletableFuture<Object> calculateExpectationAsync(String userIgn, boolean force) {
    return calculateExpectationAsync(userIgn, force, null);
  }

  @Override
  public CompletableFuture<Object> calculateExpectationAsync(
      String userIgn, boolean force, String taskId) {
    return expectationService
        .calculateExpectationAsync(userIgn, force, taskId)
        .thenApply(response -> response);
  }

  @Override
  public CompletableFuture<byte[]> getGzipExpectationAsync(String userIgn, boolean force) {
    return expectationService.getGzipExpectationAsync(userIgn, force);
  }

  /**
   * 🔥 Sync implementation for admission control Delegates to service's sync method (returns byte[]
   * directly, not Optional)
   */
  @Override
  public byte[] getGzipExpectation(String userIgn, boolean force) {
    return expectationService.getGzipExpectation(userIgn, force);
  }

  /** 🔥 Sync implementation for admission control Delegates to service's sync method */
  @Override
  public Object calculateExpectation(String userIgn, boolean force) {
    return calculateExpectation(userIgn, force, null);
  }

  @Override
  public Object calculateExpectation(String userIgn, boolean force, String taskId) {
    return expectationService.calculateExpectation(userIgn, force, taskId);
  }

  @Override
  public byte[] getGzipFromL1CacheDirect(String userIgn) {
    return expectationService.getGzipFromL1CacheDirect(userIgn);
  }
}
