---
id: GR-REFACTOR-006
category: architecture/refactor
severity: warning
keywords: [decorator, duplication, v2-v4, generic, template-method]
languages: [java, kotlin]
---

# Cube Decorator 계산 로직 중복 (V2 vs V4)

## DON'T (위반 사항/장애 원인)

### 중복 코드
```java
// V2: long 기반
@Override
public long calculateCost() {
    long previousCost = super.calculateCost();
    long expectedTrials = calculateTrials();
    long costPerTrial = costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade());
    return previousCost + (expectedTrials * costPerTrial);
}

// V4: BigDecimal 기반 (논리는 동일)
@Override
public BigDecimal calculateCost() {
    BigDecimal previousCost = super.calculateCost();
    BigDecimal expectedTrials = calculateTrials();
    BigDecimal costPerTrial = BigDecimal.valueOf(
        costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade()));
    return previousCost.add(blackCubeCost);
}
```

### 위험 요소
- **논리적 중복**: V2와 V4의 계산 알고리즘은 100% 동일
- **확장성 문제**: 새로운 큐브 타입 추가 시 V2, V4 각각 구현 필요
- **6개 Decorator 중복**: Black, Red, Additional, Starforce × V2/V4

### 수치
- 중복 Decorator: 6개
- 코드 유사도: 90%

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 1. 제네릭 기반 추상 Decorator 생성
public abstract class AbstractCubeDecorator<N extends Number>
    extends EquipmentEnhanceDecorator {

    // Template Method Pattern
    @Override
    public N calculateCost() {
        N previousCost = getPreviousCost();
        N expectedTrials = calculateTrials();
        N costPerTrial = getCostPerTrial();
        return addCosts(previousCost, multiply(expectedTrials, costPerTrial));
    }

    // Subclass에서 타입별 구현
    protected abstract N getPreviousCost();
    protected abstract N calculateTrials();
    protected abstract N getCostPerTrial();
    protected abstract N addCosts(N a, N b);
    protected abstract N multiply(N a, N b);
}

// 2. V2/V4 구현체는 단순 래퍼
public class BlackCubeDecoratorV2 extends AbstractCubeDecorator<Long> {
    @Override protected Long addCosts(Long a, Long b) { return a + b; }
    @Override protected Long multiply(Long a, Long b) { return a * b; }
}

public class BlackCubeDecoratorV4 extends AbstractCubeDecorator<BigDecimal> {
    @Override protected BigDecimal addCosts(BigDecimal a, BigDecimal b) { return a.add(b); }
    @Override protected BigDecimal multiply(BigDecimal a, BigDecimal b) { return a.multiply(b); }
}
```

### 개선 수치 (After)
- 코드 중복 제거: 90% 감소
- OCP 준수: 신규 큐브 타입 추가 시 V2/V4 자동 지원

### 핵심 원칙
1. **Template Method Pattern**: 공통 알고리즘은 추상 클래스에 정의
2. **제네릭 활용**: 타입 파라미터로 V2(long)와 V4(BigDecimal) 통합
3. **SRP 준수**: 각 구현체는 타입별 연산만 담당

## 출처
- 문서: [docs/05_Reports/05_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/05_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P0 (심각한 중복)
