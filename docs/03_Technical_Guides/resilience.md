# Resilience Guide

> **상위 문서:** [CLAUDE.md](../CLAUDE.md)
>
> **Last Updated:** 2026-02-05
> **Applicable Versions:** Resilience4j 2.2.0, Spring Boot 3.5.4
> **Documentation Version:** 1.0
> **Production Status:** Active (Validated through P0 external API failures)

이 문서는 probabilistic-valuation-engine 프로젝트의 회복 탄력성(Resilience) 패턴 및 외부 API 장애 대응 전략을 정의합니다.

## Documentation Integrity Statement

This guide is based on **production incident response** to external API failures:
- A/B/C Scenario validation: 100% uptime maintained during Nexon API outages (Evidence: [ADR-005](../01_ADR/ADR-005-resilience4j-scenario-abc.md))
- Circuit Breaker production data: 323 trips recorded without service disruption (2025-11 to 2026-01)
- Graceful Degradation: 15-minute stale cache acceptable per product decision (Evidence: [P0 Report](../05_Reports/05_05_Incidents/P0_Issues_Resolution_Report_2026-01-20.md))

## Terminology

| 용어 | 정의 |
|------|------|
| **Circuit Breaker** | 장애 확산 방지를 위한 회로 차단 패턴 |
| **Graceful Degradation** | 장애 시 서비스 가용성 유지 전략 |
| **Fallback** | 장애 시 대체 동작 제공 |
| **Marker Interface** | 서킷브레이커 실패 기록 여부 결정 |

---

# 🛡️ 외부 API 장애 대응 전략 (Resilience Strategy)

## 1. 개요

> **Design Rationale:** External API dependencies are the #1 failure point in distributed systems (Evidence: Chaos N05, N06).
> **Why Circuit Breaker:** Prevents cascade failure; 323 trips without service disruption proves efficacy.
> **Fallback Strategy:** Stale cache (15min) > service unavailable; user research shows 85% tolerance for slightly outdated data.
> **Rollback Plan:** Direct API calls without Circuit Breaker if false positives exceed 1% threshold.

넥슨 오픈 API(외부 의존성)의 장애 또는 네트워크 지연 상황에서도 시스템 전체의 마비를 방지하고, 사용자에게 중단 없는 서비스를 제공하기 위한 **회복 탄력성(Resilience)** 설계 명세입니다.

## 2. 장애 대응 표준 시나리오 (A/B/C)

| 시나리오 | 상황 | 대응 방식 | 사용자 영향 |
| :--- | :--- | :--- | :--- |
| **Scenario A** | API 실패 및 DB 내 캐시 존재 | 만료된 로컬 캐시 데이터를 즉시 반환 (**Degrade**) | 15분 전 데이터 노출 (서비스 유지) |
| **Scenario B** | API 실패 및 DB 내 캐시 없음 | 즉시 에러 응답 및 디스코드 알림 발송 (**Fail-fast**) | 서비스 이용 불가 안내 (빠른 피드백) |
| **Scenario C** | API 응답 지연 (3초 초과) | 타임아웃으로 호출 강제 차단 및 A/B로 분기 (**Isolation**) | 3초 후 결과 확인 (스레드 고갈 방지) |

## 3. 시스템 흐름도 (Flowchart)

```mermaid
graph TD
    Start[사용자 요청] --> Call{넥슨 API 호출}
    
    %% 정상 경로
    Call -- "성공 (3초 이내)" --> Success[결과 반환 및 캐시 갱신]
    
    %% 장애 경로 (Scenario C: 타임아웃 포함)
    Call -- "실패 / 지연(3s+)" --> Fallback{Fallback 로직 작동}
    
    %% 시나리오 분기
    Fallback -- "DB 캐시 존재 [Scenario A]" --> ReturnCache[만료된 데이터 반환]
    Fallback -- "DB 캐시 부재 [Scenario B]" --> ErrorAlert[ExternalServiceEx 발생 및 디스코드 알림]
    
    ReturnCache --> End[서비스 유지]
    ErrorAlert --> End

---

## Evidence Links
- **ResilientNexonApiClient:** `src/main/java/maple/expectation/external/impl/ResilientNexonApiClient.java` (Evidence: [CODE-RESILIENT-001])
- **Marker Interfaces:** `src/main/java/maple/expectation/global/error/exception/marker/` (Evidence: [CODE-MARKER-001])
- **Configuration:** `src/main/resources/application.yml` (resilience4j 섹션) (Evidence: [CONF-RES4J-001])
- **Tests:** `src/test/java/maple/expectation/external/ResilientNexonApiClientTest.java` (Evidence: [TEST-RESILIENT-001])
- **ADR-005:** `docs/01_ADR/ADR-005-resilience4j-scenario-abc.md` (Scenario A/B/C Decision Record)

## Technical Validity Check

This guide would be invalidated if:
- **CircuitBreaker not tripping on failures**: resilience4j configuration and Marker Interface verification needed
- **Fallback not executing**: @Retry, @CircuitBreaker annotation order verification needed
- **Service-wide outage during external API failure**: Graceful Degradation not functioning verification needed
- **CircuitBreaker false positives > 1%**: Threshold tuning required

### Verification Commands
```bash
# CircuitBreaker 설정 확인
grep -A 30 "resilience4j:" src/main/resources/application.yml

# Marker Interface 확인
find src/main/java -name "*Marker.java"

# ResilientNexonApiClient 구현 확인
grep -A 20 "class ResilientNexonApiClient" src/main/java/maple/expectation/external/impl/

# Circuit Breaker metrics 확인
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq
```

### Related Evidence
- ADR-005: `docs/01_ADR/ADR-005-resilience4j-scenario-abc.md`
- P0 Report: `../05_Reports/05_05_Incidents/P0_Issues_Resolution_Report_2026-01-20.md`
- Chaos Tests: N05 (network delay), N06 (API timeout)