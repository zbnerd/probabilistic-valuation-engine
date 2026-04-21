package maple.expectation.application.service.expectation.cache;

import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4;
import org.springframework.stereotype.Component;

/**
 * Cached Response Builder (Issue #644: God Object Decomposition)
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Build response with fromCache=true flag
 *   <li>Preserve all original response fields
 * </ul>
 *
 * <p>Extracted from ExpectationCacheCoordinator to follow Single Responsibility Principle.
 */
@Component
public class CachedResponseBuilder {

  /**
   * Build response with fromCache=true flag
   *
   * @param original Original response
   * @return New response with fromCache=true
   */
  public EquipmentExpectationResponseV4 buildWithCacheFlag(
      EquipmentExpectationResponseV4 original) {
    return EquipmentExpectationResponseV4.builder()
        .userIgn(original.getUserIgn())
        .calculatedAt(original.getCalculatedAt())
        .fromCache(true)
        .totalExpectedCost(original.getTotalExpectedCost())
        .totalCostText(original.getTotalCostText())
        .totalCostBreakdown(original.getTotalCostBreakdown())
        .maxPresetNo(original.getMaxPresetNo())
        .presets(original.getPresets())
        .build();
  }
}
