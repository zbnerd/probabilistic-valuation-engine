# 백엔드 시스템 포트폴리오: 확률적 기대비용 계산 엔진

> **Probabilistic Valuation Engine** — 9개월간의 설계, 구현, 최적화, 운영 기록
> 2025년 7월 ~ 2026년 4월 | 1,228 커밋 | 425+ PR | 6모듈 헥사고날 아키텍처

---

## 이 책을 쓴 이유

성능 최적화 서사는 강하다. 97 RPS에서 7,347 RPS까지, 76배 향상.
하지만 백엔드 엔지니어로서 보여야 할 것은 성능 한 축이 아니다.

이 책은 **"성능만 하는 엔지니어"**가 아니라
**"실무형 백엔드 시스템을 설계하고 구현하고 운영하는 엔지니어"**라는 것을
9개월의 실제 기록으로 증명하기 위해 썼다.

---

## 책의 구성

| 장 | 제목 | 다루는 역량 축 |
|----|------|---------------|
| [프롤로그](./00_프롤로그_시스템_개요.md) | 시스템 개요 | 서비스 전체 그림, 핵심 API, 요청 흐름 |
| [1장](./01_API_설계.md) | API 설계 | 엔드포인트 구조, DTO, 상태코드, 예외 응답, 버전 전략 |
| [2장](./02_도메인_모델링.md) | 도메인 모델링 | 비즈니스 복잡성, 엔티티/애그리거트, 도메인 규칙 |
| [3장](./03_아키텍처_진화.md) | 아키텍처 진화 | 모놀리스 → 멀티모듈 → 헥사고날 → CQRS |
| [4장](./04_트랜잭션과_정합성.md) | 트랜잭션과 정합성 | 즉시/최종 일관성, Write-Behind 손실 경계, 불변조건 |
| [5장](./05_성능_엔지니어링.md) | 성능 엔지니어링 | 97→7,347 RPS 여정, 병목 추적, 실패-롤백-재설계 |
| [6장](./06_테스트_전략.md) | 테스트 전략 | 단위/통합/동시성/카오스 테스트, 회귀 방지 |
| [7장](./07_관측성과_운영.md) | 관측성과 운영 | 메트릭, 알람, 로그 구조화, 트레이싱, SLI/SLO |
| [8장](./08_보안과_안전장치.md) | 보안과 안전장치 | 인증/인가, Rate Limiting, 입력값 검증, Circuit Breaker |
| [9장](./09_한계와_다음단계.md) | 한계와 다음 단계 | 현재 한계, 스케일아웃 조건, 최적화 후보 |

---

## 핵심 수치

| 지표 | 시작 | 최종 | 개선 |
|------|------|------|------|
| RPS | 97 | **7,347** | **76배** |
| p99 지연 | 4,100ms | 36ms | **99% 감소** |
| 인프라 | Redis + MySQL + MongoDB | PostgreSQL 단일 | **3개 DB → 1개** |
| 에러율 | 59.7% | 0% | **완전 제거** |
| SOLID 준수 | - | 88% | **Good** |
| 무상태 준수 | - | 94% | **Production Ready** |
| 보안 등급 | - | A- | **Excellent** |
| 테스트 | 47 Flaky | 0 Flaky | **CI Pass Rate 99.7%** |

---

## 기술 스택

```
Language:    Kotlin 2.1.0 / Java 21 (Virtual Threads)
Framework:   Spring Boot 3.5.4
Database:    PostgreSQL (영속성, 캐시 L2, Advisory Lock, NOTIFY, PGMQ)
Cache:       Caffeine (L1) + PostgreSQL UNLOGGED (L2)
Resilience:  Resilience4j (Circuit Breaker, Retry, TimeLimiter)
Auth:        Spring Security 6.x + JWT (HS256)
Observability: Prometheus + Loki + Grafana
CI/CD:       GitHub Actions
Testing:     JUnit 5, Testcontainers, ArchUnit, Awaitility
Build:       Gradle (Kotlin DSL)
```

---

## 프로젝트 개요

**probabilistic-valuation-engine**은 메이플스토리 장비의 확률적 기대비용을 계산하는 백엔드 시스템이다.
사용자가 캐릭터 이름을 입력하면 Nexon API에서 장비 데이터를 가져와
3개 프리셋의 큐브/스타포스 기대비용을 계산하고 반환한다.

- **도메인 복잡성**: 확률 분포 계산, 큐브 등급 시스템, 스타포스 강화 시뮬레이션
- **외부 의존성**: Nexon Open API (rate limit, 장애 불가피)
- **데이터 볼륨**: 200K~300K 캐릭터, 300KB JSON 응답, GZIP 90% 압축
- **트래픽 특성**: 인기 캐릭터 Cache Stampede, 실시간 좋아요 동기화

---

## 관련 문서

| 주제 | 위치 |
|------|------|
| 성능 여정 (상세) | [docs/06_Performance_Journey/](../06_Performance_Journey/) |
| ADR 모음 | [docs/01_ADR/](../01_ADR/) |
| 카오스 엔지니어링 | [docs/02_Chaos_Engineering/](../02_Chaos_Engineering/) |
| 기술 가이드 | [docs/03_Technical_Guides/](../03_Technical_Guides/) |
| 리포트 모음 | [docs/05_Reports/](../05_Reports/) |
| 아키텍처 | [docs/00_Start_Here/architecture.md](../00_Start_Here/architecture.md) |
