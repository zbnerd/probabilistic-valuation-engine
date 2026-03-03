package maple.expectation.application.usecase;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.ExpectationV4Port;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
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
    return expectationService
        .calculateExpectationAsync(userIgn, force)
        .thenApply(response -> response);
  }

  @Override
  public CompletableFuture<byte[]> getGzipExpectationAsync(String userIgn, boolean force) {
    return expectationService.getGzipExpectationAsync(userIgn, force);
  }

  @Override
  public byte[] getGzipFromL1CacheDirect(String userIgn) {
    var result = expectationService.getGzipFromL1CacheDirect(userIgn);
    return result.orElse(null);
  }
}
