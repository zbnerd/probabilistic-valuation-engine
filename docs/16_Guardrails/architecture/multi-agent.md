---
id: GR-ARCH-020
category: architecture
severity: critical
keywords: [5-Agent Council, Pentagonal Pipeline, Sequential Thinking, SOLID, Trade-off]
---

# Multi-Agent Protocol & Development Workflow Guardrails

## Overview

probabilistic-valuation-engine 프로젝트는 **5-Agent Council** 프로토콜을 통해 모든 기능 구현과 리팩토링을 검토합니다. 이는 단순한 형식이 아니라 **실제 PR 리뷰와 ADR 검증에 사용되는 프로세스**입니다.

---

## GR-ARCH-020: Pentagonal Pipeline 워크플로우 필수

### DON'T (안티패턴)

```markdown
## ❌ 안티패턴: 단일 에이전트 또는 건너뛰기

[Feature Request] 새로운 기능 추가

1. 개발자가 바로 코드 작성  # ❌ Blue 설계 생략
2. PR 제출
3. 팀장이 빠른 리뷰 후 머지  # ❌ Green, Yellow, Purple, Red 검토 생략

결과: 성능 문제, 테스트 부족, 보안 취약점, 장애 발생
```

### DO (베스트 프랙티스)

```markdown
## ✅ 베스트 프랙티스: Pentagonal Pipeline 준수

[Feature Request] 큐브 기대값 엔진 고도화

### Phase 1: Draft (Blue - Architect)
- 🟦 Blue: Decorator 패턴으로 확장 가능한 구조 설계
- 산출물: 클래스 다이어그램, 인터페이스 정의

### Phase 2: Optimize (Green - Performance)
- 🟩 Green: O(1) DP 테이블 룩업, 누적 확률 캐싱
- 산출물: 성능 벤치마크, 복잡도 분석

### Phase 3: Test (Yellow - QA)
- 🟨 Yellow: 경계값 테스트, Monte Carlo 시뮬레이션
- 산출물: 테스트 커버리지 90%+, Edge Case 문서화

### Phase 4: Audit (Purple - Auditor)
- 🟪 Purple: BigDecimal 정밀도 검증, Kahan Summation 적용
- 산출물: 오차 범위 < 0.01%, 재현성 시나리오

### Phase 5: Deploy Check (Red - SRE)
- 🟥 Red: 서킷브레이커 설정, 타임아웃, 폴백
- 산출물: 배포 체크리스트, Runbook

최종 승인: 5개 에이전트 모두 PASS ✅
```

**핵심 규칙:**
- 모든 기능 구현은 5단계 파이프라인을 거쳐야 함
- 단계 건너뛰기 금지 (예: 테스트 없이 배포)
- 각 에이전트의 **MANDATE(책임)**을 명확히 준수
- 충돌 시 우선순위: Purple > Red > Yellow > Green > Blue

### 출처
- [multi-agent-protocol.md](../../00_Start_Here/multi-agent-protocol.md) - Section 2: Best Practice

---

## GR-ARCH-021: 에이전트 역할 준수 필수

### DON'T (안티패턴)

```markdown
## ❌ 안티패턴: 역할 침범 또는 무시

### 잘못된 예 1: Green이 보안을 검토
🟩 Green: "이 API는 인증이 필요합니다"  # ❌ Purple 영역

### 잘못된 예 2: Blue가 성능을 무시
🟦 Blue: "SOLID 준수했으므로 구현 완료"  # ❌ Green 검토 생략

### 잘못된 예 3: Yellow가 Edge Case를 누락
🟨 Yellow: "정상 케이스 테스트 통과"  # ❌ 경계값 미검증
```

### DO (베스트 프랙티스)

```markdown
## ✅ 베스트 프랙티스: 에이전트 역할 명확히 준수

### 🟦 Blue: Spring-Architect (The Designer)
**MANDATE**: SOLID 원칙, 디자인 패턴, DDD, Clean Architecture

**체크리스트:**
- [ ] 코드가 유지보수 가능한 구조인가?
- [ ] 의존성 역전(DIP)이 지켜졌는가?
- [ ] Facade, Decorator, Strategy 패턴이 올바르게 적용되었는가?

**예시 리뷰:**
> "EquipmentService가 너무 많은 책임을 가집니다. Facade 패턴으로 GameCharacterFacade를 도입하여 책임을 분리하세요."

---

### 🟩 Green: Performance-Guru (The Optimizer)
**MANDATE**: O(1) 지향, Redis Lua Script, SQL Tuning, Non-blocking I/O

**체크리스트:**
- [ ] 이 로직이 10만 RPS를 견디는가?
- [ ] 불필요한 객체 생성이나 루프가 없는가?
- [ ] N+1 쿼리 문제가 없는가?

**예시 리뷰:**
> "큐브 계산에서 중복 루프가 발견되었습니다. DP 테이블 룩업으로 O(n²) → O(n) 최적화가 가능합니다."

---

### 🟨 Yellow: QA-Master (The Tester)
**MANDATE**: JUnit 5, Mockito, Testcontainers, Locust, Edge Case 발굴

**체크리스트:**
- [ ] 테스트 커버리지가 충분한가? (목표: 90%+)
- [ ] 경계값(Boundary)에서 터지지 않는가?
- [ ] Flaky Test가 없는가?

**예시 리뷰:**
> "스타포스 25성 파괴 확률 테스트가 누락되었습니다. Boundary Value Analysis를 수행하세요."

---

### 🟪 Purple: Financial-Grade-Auditor (The Sheriff)
**MANDATE**: 무결성(Integrity), 보안(Security), 정밀도, 트랜잭션 검증

**체크리스트:**
- [ ] 확률 계산에 오차 누적이 없는가?
- [ ] PII 정보가 로그에 남지 않는가?
- [ ] 트랜잭션 경계가 올바른가?

**예시 리뷰:**
> "Double precision 사용으로 누적 오차가 발생합니다. BigDecimal + Kahan Summation으로 전환하세요."

---

### 🟥 Red: SRE-Gatekeeper (The Guardian)
**MANDATE**: Resilience(Circuit Breaker, Timeout), Thread Pool, Config, Infra

**체크리스트:**
- [ ] 서버가 죽지 않는 설정인가?
- [ ] CallerRunsPolicy 같은 폭탄이 없는가?
- [ ] 장애 복구 절차(Runbook)이 있는가?

**예시 리뷰:**
> "WebClient에 타임아웃이 없습니다. 무한 대기로 스레드 고갈 가능성이 있습니다. 10초 타임아웃을 설정하세요."
```

### 출처
- [multi-agent-protocol.md](../../00_Start_Here/multi-agent-protocol.md) - Section 1: The Council of Five

---

## GR-ARCH-022: Sequential Thinking 필수

### DON'T (안티패턴)

```markdown
## ❌ 안티패턴: 단계 건너뛰기

문제: "기대값 계산이 너무 느려요"
해결: 바로 코드 수정 시작  # ❌ 분석/설계 생략

결과: 근본 원인 미파악, 임시 해결만으로 문제 재발
```

### DO (베스트 프랙티스)

```markdown
## ✅ 베스트 프랙티스: Sequential Thinking 7단계

### 1. 배경 (Context)
- 기대값 계산 API 응답 시간이 P99 3초 초과
- 사용자 불만 접수, 모니터링 알림 발생

### 2. 정의 (Definition)
- 명확한 문제 정의: "P99 응답 시간을 500ms 이하로 개선"
- 성공 기준: "부하 테스트 1000 RPS 통과"

### 3. 분석 (Analysis)
- 프로파일링 결과: 큐브 확률 계산에서 O(n²) 복잡도 발견
- 병목 지점: 중첩 루프로 인한 CPU 사용량 90%+

### 4. 설계 (Design)
- DP 테이블 룩업으로 O(n²) → O(n) 최적화
- Decorator 패턴으로 계산 체인 구조화
- V4 서비스 분리 (719 RPS 검증됨)

### 5. 구현 (Implementation)
- CubeDpCalculator 리팩토링
- 단위 테스트 작성 (기존 로직 결과와 일치 검증)

### 6. 검증 (Verification)
- JMeter 부하 테스트: 1000 RPS, P99 420ms 달성
- 정확도 검증: Monte Carlo 시뮬레이션 오차 < 0.01%

### 7. 회고 (Retrospective)
- 개선점: 캐시 적용으로 추가 15% 성능 향상 가능
- 다음 액션: L1 캐시 TTL 튜닝
```

**핵심 규칙:**
- 단계 건너뛰기 금지 (특히 분석/설계)
- 각 단계 산출물 문서화
- 회고에서 다음 액션 도출

### 출처
- [multi-agent-protocol.md](../../00_Start_Here/multi-agent-protocol.md) - Section 3: Core Principles

---

## GR-ARCH-023: Trade-off 기록 필수

### DON'T (안티패턴)

```markdown
## ❌ 안티패턴: Trade-off 미기록

### PR 제목: 큐브 엔진 리팩토링

## 변경 사항
- DP 테이블 룩업 도입
- 테스트 코드 추가

## 테스트
- [x] 단위 테스트 통과

# ❌ 왜 이 결정을 내렸는지 기록 없음
# ❌ 대안과 비교 없음
# ❌ 단점과 리스크 미기록
```

### DO (베스트 프랙티스)

```markdown
## ✅ 베스트 프랙티스: Trade-off 명확히 기록

### PR 제목: 큐브 엔진 고도화 - DP 기반 누적 확률 연산 도입

## 💱 트레이드 오프 결정 근거

### 결정: 누적 확률 연산 도입 (DP 테이블 룩업)

| 관점 | 현장 (Monte Carlo) | 도입 (DP 테이블) | 선택 |
|------|-------------------|-----------------|------|
| **정확도** | 오차 < 0.01% | 오차 < 0.001% | ✅ DP 우위 |
| **성능** | O(n × trials) | O(n) | ✅ DP 1000배 빠름 |
| **메모리** | 10 MB | 50 MB | ⚠️ DP 5배 사용 |
| **복잡도** | 단순 루프 | DP 테이블 구현 | ⚠️ DP 복잡 |

**최종 결정:** DP 테이블 도입 (성능 > 메모리)
- 이유: 기대값 API는 핫 경로, 메모리는 충분히 여유 있음
- 단점: 메모리 사용량 증가 (50 MB → 넉넉히 허용)

### 대안 고려

**대안 1: Monte Carlo 시뮬레이션 (현장)**
- ✅ 장점: 구현 단순, 메모리 효율적
- ❌ 단점: 느림 (10000 trials × 30 슬롯 = 300K 연산)
- **기각:** P99 응답 시간 3초 초과

**대안 2: DP 테이블 룩업 (도입)**
- ✅ 장점: O(1) 룩업, 정확도 높음
- ❌ 단점: 메모리 사용량 증가, 구현 복잡
- **채택:** 성능 개선 효과가 메모리 비용 상쇄

**대안 3: 캐싱 (추가 고려)**
- ✅ 장점: 중복 계산 방지
- ❌ 단점: 캐시 무효화 로직 복잡
- **향후:** V2 단계에서 도입 검토

### 에이전트 승인

- 🟦 Blue: DP 테이블 구조 설계 적절함 ✅
- 🟩 Green: O(n) 최적화로 1000배 성능 향상 ✅
- 🟨 Yellow: Monte Carlo와 결과 일치 검증 완료 ✅
- 🟪 Purple: BigDecimal 정밀도로 오차 최소화 ✅
- 🟥 Red: 메모리 사용량 50MB 허용 가능 ✅

**최종 승인:** 만장일치 (5/5 PASS)
```

**핵심 규칙:**
- 모든 PR에 Trade-off 섹션 필수
- 대안과 비교 분석 포함
- 5-Agent Council 승인 기록
- 단점과 리스크 명시

### 출처
- [multi-agent-protocol.md](../../00_Start_Here/multi-agent-protocol.md) - Section 5: Agent 간 의사결정 규칙
- [PR_TEMPLATE.md](../../98_Templates/PR_TEMPLATE.md) - PR 템플릿

---

## GR-ARCH-024: 디자인 패턴 적용 가이드

### DON'T (안티패턴)

```java
// 안티패턴 1: 관습 없이 무조건 패턴 적용
public interface EquipmentService {
    EquipmentData get(String ocid);
}
public class EquipmentServiceFactory {  // 불필요한 Factory
    public EquipmentService create() {
        return new EquipmentServiceImpl();
    }
}
// 구현체가 1개뿐인데 Factory 도입

// 안티패턴 2: 패턴 남용으로 가독성 저하
public abstract class AbstractEquipmentFactoryDecoratorProxyBuilder {
    // 패턴 중첩으로 이해 불가능한 코드
}
```

### DO (베스트 프랙티스)

```java
// Good: 문제 해결을 위한 적절한 패턴 적용

// Strategy: 알고리즘 교체 필요 시
public interface PaymentStrategy {
    PaymentResult pay(DonationRequest request);
}

// Decorator: 동적 책임 추가 필요 시
public abstract class EquipmentEnhanceDecorator {
    // 장비 강화 비용 누적 계산
}

// Facade: 복잡한 하위 시스템 단순화 필요 시
@Service
public class GameCharacterFacade {
    // 여러 서비스 조합을 단순화
}

// Factory: 객체 생성 복잡도 은폐 필요 시
public class ExpectationCalculatorFactory {
    // 조건부 Decorator 체인 생성
}
```

**핵심 규칙:**
- **문제 맥락에 따른 패턴 선택**
- 복잡한 분기 처리 → **Strategy**
- 외부 통신 → **Facade**
- 객체 생성 → **Factory**
- 확장 가능한 템플릿 → **Template Method**
- 동적 책임 추가 → **Decorator**

### 출처
- [multi-agent-protocol.md](../../00_Start_Here/multi-agent-protocol.md) - Section 3: Design Patterns

---

## Verification Checklist

```markdown
## PR 제출 전 Self-Check

### 🟦 Blue (Architect)
- [ ] SOLID 원칙 준수?
- [ ] 적절한 디자인 패턴 적용?
- [ ] 의존성 방향 올바름?

### 🟩 Green (Performance)
- [ ] 시간 복잡도 분석 완료?
- [ ] N+1 쿼리 없음?
- [ ] 불필요한 객체 생성 없음?

### 🟨 Yellow (QA)
- [ ] 테스트 커버리지 90%+?
- [ ] Edge Case 테스트 작성?
- [ ] Flaky Test 없음?

### 🟪 Purple (Auditor)
- [ ] 데이터 무결성 보장?
- [ ] 보안 취약점 없음?
- [ ] 정밀도 요구사항 충족?

### 🟥 Red (SRE)
- [ ] 타임아웃 설정?
- [ ] Circuit Breaker 적용?
- [ ] 장애 시 Fallback 존재?

### 공통
- [ ] Trade-off 기록 포함?
- [ ] 5-Agent Council 승인 완료?
```

---

## Evidence Links

- [multi-agent-protocol.md](../../00_Start_Here/multi-agent-protocol.md) - 전체 프로토콜
- [PR_TEMPLATE.md](../../98_Templates/PR_TEMPLATE.md) - PR 템플릿
- [ISSUE_TEMPLATE.md](../../98_Templates/ISSUE_TEMPLATE.md) - 이슈 템플릿
- [P0 Report](../../05_Reports/05_05_Incidents/P0_Issues_Resolution_Report_2026-01-20.md) - 에이전트 역할 검증
- [ADR Directory](../../01_ADR/) - Trade-off 결정 사례
