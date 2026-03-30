# ADR (Architecture Decision Records)

이 디렉토리는 probabilistic-valuation-engine 프로젝트의 중요한 아키텍처 결정과 기술 스택 선택을 문서화합니다.

## ADR 목록

### 기술 스택 결정 (Technology Stack)

| ADR | 제목 | 날짜 | 상태 |
|-----|------|------|------|
| [ADR-048](ADR-048-java-21-virtual-threads.md) | Java 21 Virtual Threads 채택 | 2026-02-19 | Accepted |
| [ADR-049](ADR-049-spring-boot-3.5.4-adoption.md) | Spring Boot 3.5.4 채택 | 2026-02-19 | Accepted |
| [ADR-050](ADR-050-redis-7.0-redisson-3.48.0-adoption.md) | Redis 7.0 + Redisson 3.48.0 채택 | 2026-02-19 | Accepted |
| [ADR-051](ADR-051-mysql-testcontainers-adoPTION.md) | MySQL 8.0 + Testcontainers 채택 | 2026-02-19 | Accepted |
| [ADR-052](ADR-052-resilience4j-circuit-breaker.md) | Resilience4j 2.2.0 Circuit Breaker 채택 | 2026-02-19 | Accepted |
| [ADR-053](ADR-053.md) | Observability Stack (Prometheus + Grafana + Loki + OpenTelemetry) | 2026-02-19 | Accepted |
| [ADR-054](ADR-054.md) | GitHub Actions CI/CD + 4-Workflow Strategy 채택 | 2026-02-19 | Accepted |
| [ADR-055](ADR-055.md) | Redis Streams를 메시지 브로커로 채택 (Kafka 미사용) | 2026-02-19 | Accepted |
| [ADR-056](ADR-056-mongodb-cqrs-read-side.md) | V5 CQRS Read Side에 MongoDB 7.0 채택 | 2026-02-19 | Accepted |
| [ADR-057](ADR-057.md) | 낙관적 락/비관적 락 대신 Redisson 분산 락 채택 | 2026-02-19 | Accepted |
| [ADR-058](ADR-058.md) | L1 Local Cache로 Caffeine 3.1.8 채택 | 2026-02-19 | Accepted |
| [ADR-059](ADR-059-gradle-build-tool-adoption.md) | 빌드 도구로 Gradle 8.5 채택 (Maven 미사용) | 2026-02-19 | Accepted |

### 아키텍처 결정 (Architecture)

| ADR | 제목 | 날짜 | 상태 |
|-----|------|------|------|
| [ADR-041](ADR-041-multi-module-hexagonal-architecture-dip.md) | 멀티모듈 헥사고날 아키텍처와 DIP | 2026-02-19 | Accepted |
| [ADR-042](ADR-042-v2-v4-dual-generation-architecture.md) | V2/V4 이중 세대 서비스 아키텍처 | 2026-02-19 | Accepted |
| [ADR-043](ADR-043.md) | TieredCache (L1 Caffeine + L2 Redis)와 Single-flight 패턴 | 2026-02-19 | Accepted |
| [ADR-044](ADR-044-logicexecutor-zero-try-catch.md) | LogicExecutor 기반 예외 처리와 Zero Try-Catch 정책 | 2026-02-19 | Accepted |
| [ADR-045](ADR-045.md) | Virtual Threads와 AbortPolicy를 사용한 비동기 Non-Blocking 파이프라인 | 2026-02-19 | Accepted |
| [ADR-046](ADR-046.md) | Transactional Outbox 패턴과 Triple Safety Net | 2026-02-19 | Accepted |
| [ADR-047](ADR-047.md) | Redisson Watchdog를 사용한 회복탄력적 분산 락과 MySQL Fallback | 2026-02-19 | Accepted |

### V5 CQRS 아키텍처 (V5 CQRS Architecture)

| ADR | 제목 | 날짜 | 상태 |
|-----|------|------|------|
| [ADR-036](ADR-036-v5-cqrs-mongodb.md) | V5 CQRS: MongoDB Read Side | 2026-02-15 | Accepted |
| [ADR-037](ADR-037-v5-cqrs-command-side.md) | V5 CQRS: Command Side (MySQL + Queue) | 2026-02-15 | Accepted |
| [ADR-038](ADR-038-v5-cqrs-implementation.md) | V5 CQRS 구현 (Redis Stream + MongoDB) | 2026-02-15 | Accepted |
| [ADR-V5](ADR-V5-cqrs-mongodb-readside.md) | V5 CQRS MongoDB Readside (상세) | 2026-02-15 | Accepted |

### 시스템 평가 및 문서화 (System Assessment)

| ADR | 제목 | 날짜 | 상태 |
|-----|------|------|------|
| [ADR-035](ADR-035.md) | 멀티모듈 마이그레이션 완료 | 2025-02-13 | Accepted |
| [ADR-039](ADR-039-current-architecture-assessment.md) | 현재 아키텍처 평가 (module-app bloat) | 2026-02-16 | Accepted |
| [ADR-040](ADR-040-chaos-engineering-documentation-update.md) | 카오스 엔지니어링 문서화 업데이트 | 2026-02-18 | Accepted |

## 카테고리별 요약

### 📦 Technology Stack (12개)
- **Java**: Java 21 Virtual Threads (8.1x 처리량 개선)
- **Framework**: Spring Boot 3.5.4 (Jakarta EE 10, Virtual Threads 지원)
- **Database**: MySQL 8.0 (GZIP 90% 압축), MongoDB 7.0 (V5 CQRS Read Side)
- **Cache**: Redis 7.0 + Redisson (Master-Slave + Sentinel), Caffeine 3.1.8 (L1 Local Cache)
- **Message Broker**: Redis Streams (Kafka 미사용, Phase 8 전환 계획)
- **Resilience**: Resilience4j 2.2.0 Circuit Breaker
- **Observability**: Prometheus + Grafana + Loki + OpenTelemetry
- **CI/CD**: GitHub Actions 4-Workflow Strategy
- **Build**: Gradle 8.5 (Build cache, CI 시간 50% 단축)

### 🏗️ Architecture (7개)
- **Multi-Module**: 헥사고날 아키텍처 (module-app→infra→core→common)
- **Service Evolution**: V2/V4 이중 세대 (7.6x throughput 개선)
- **Caching**: TieredCache + Single-flight 패턴
- **Exception Handling**: LogicExecutor + Zero Try-Catch 정책
- **Async**: Virtual Threads + AbortPolicy (8.1x 개선)
- **Reliability**: Transactional Outbox + Triple Safety Net
- **Locking**: Redisson Watchdog + MySQL Fallback (965 RPS)

### 🔄 CQRS (4개)
- **Read Side**: MongoDB 7.0 (50x-300x 성능 개선)
- **Write Side**: MySQL + Redis Streams
- **Event Bus**: Redis Streams Consumer Group
- **TTL**: 24시간 자동 정리

## 성과 요약

| 카테고리 | 지표 | 개선 전 | 개선 후 |
|---------|------|---------|---------|
| **처리량** | RPS | 89 (blocking) | 719 (async) |
| **지연시간** | P99 | 5000ms+ | 450ms |
| **캐시 적중률** | L1 Hit Rate | - | 85-95% |
| **압축** | GZIP | - | 90% |
| **CI 통과율** | Pass Rate | 85% | 99.7% |
| **Flaky Test** | Incidents | 47/월 | <1/월 |
| **분산 락** | Throughput | 300 RPS | 965 RPS |

## 관련 문서

- [아키텍처 개요](../00_Start_Here/architecture.md)
- [로드맵](../00_Start_Here/ROADMAP.md)
- [기술 가이드](../03_Technical_Guides/infrastructure.md)
- [카오스 엔지니어링](../02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md)

## ADR 작성 가이드

모든 ADR은 5장식 한국어 내러티브 형식을 따릅니다:

1. **제1장: 문제의 발견** - 어떤 문제를 해결하고자 했는가?
2. **제2장: 선택지 탐색** - 어떤 대안들이 있었는가?
3. **제3장: 결정의 근거** - 왜 이 선택을 했는가?
4. **제4장: 구현의 여정** - 어떻게 구현했는가? (코드 증거)
5. **제5장: 결과와 학습** - 무엇을 얻었고, 무엇을 배웠는가?
