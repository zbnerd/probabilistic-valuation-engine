package maple.expectation.application.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.calculator.PotentialCalculator;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;
import maple.expectation.core.domain.stat.StatType;
import maple.expectation.core.port.out.CubeRatePort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse.ItemOption;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PotentialApplicationService {

  private final CubeRatePort cubeRatePort;
  private final PotentialCalculator potentialCalculator;
  private final LogicExecutor executor;

  private ItemEquipment createMainPotentialItem(String option1, String option2, String option3) {
    return new ItemEquipment(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null, // 1-8: basic fields
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null, // 9-14: ItemOption fields
        null, // 15: potentialOptionGrade
        option1,
        option2,
        option3, // 16-18: main potential options
        null, // 19: additionalPotentialOptionGrade
        null,
        null,
        null, // 20-22: additional potential (not set)
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null // 23-36: remaining fields
        );
  }

  private ItemEquipment createAdditionalPotentialItem(
      String option1, String option2, String option3) {
    return new ItemEquipment(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null, // 1-8: basic fields
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null,
        (ItemOption) null, // 9-14: ItemOption fields
        null, // 15: potentialOptionGrade
        null,
        null,
        null, // 16-18: main potential (not set)
        null, // 19: additionalPotentialOptionGrade
        option1,
        option2,
        option3, // 20-22: additional potential options
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null // 23-36: remaining fields
        );
  }

  public Map<StatType, Integer> calculateMainPotential(
      String option1, String option2, String option3) {
    return executor.executeOrDefault(
        () -> {
          ItemEquipment itemEquipment = createMainPotentialItem(option1, option2, option3);
          return potentialCalculator.calculateMainPotential(itemEquipment);
        },
        Map.of(),
        TaskContext.of("PotentialApplicationService", "CalculateMainPotential"));
  }

  public Map<StatType, Integer> calculateAdditionalPotential(
      String option1, String option2, String option3) {
    return executor.executeOrDefault(
        () -> {
          ItemEquipment itemEquipment = createAdditionalPotentialItem(option1, option2, option3);
          return potentialCalculator.calculateAdditionalPotential(itemEquipment);
        },
        Map.of(),
        TaskContext.of("PotentialApplicationService", "CalculateAdditionalPotential"));
  }

  public int getEffectiveStat(Map<StatType, Integer> stats, StatType type) {
    return executor.executeOrDefault(
        () -> potentialCalculator.getEffectiveStat(stats, type),
        0,
        TaskContext.of("PotentialApplicationService", "GetEffectiveStat", type.name()));
  }

  public java.util.List<CubeRate> getCubeRates(CubeType cubeType) {
    return executor.executeOrDefault(
        () -> cubeRatePort.findByCubeType(cubeType),
        java.util.List.of(),
        TaskContext.of("PotentialApplicationService", "GetCubeRates", cubeType.name()));
  }

  public int calculateFinalStat(
      String option1, String option2, String option3, StatType targetStat) {
    return executor.executeOrDefault(
        () -> {
          ItemEquipment itemEquipment = createMainPotentialItem(option1, option2, option3);
          Map<StatType, Integer> stats = potentialCalculator.calculateMainPotential(itemEquipment);
          return potentialCalculator.getEffectiveStat(stats, targetStat);
        },
        0,
        TaskContext.of("PotentialApplicationService", "CalculateFinalStat", targetStat.name()));
  }
}
