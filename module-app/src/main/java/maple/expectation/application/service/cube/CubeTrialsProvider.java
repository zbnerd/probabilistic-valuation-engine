package maple.expectation.application.service.cube;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.web.dto.CubeCalculationInput;

public interface CubeTrialsProvider {
  /** 특정 큐브와 설정값에 따른 목표 옵션 도달 기대 시도 횟수를 반환합니다. */
  Double calculateExpectedTrials(CubeCalculationInput input, CubeType type);
}
