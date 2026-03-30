# Guardrails - Architecture

## 개요

아키텍처 설계 원칙, 패턴, 그리고 시스템 설계에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-ARCH-001 | [ADR Decisions](adr-decisions.md) | critical | ADR, Hexagonal, DIP, Clean-Architecture, Multi-Module |
| GR-ARCH-002 | [Multi-Agent Protocol](multi-agent.md) | critical | Agent, Council, 5-Agent, Protocol, Sequential-Thinking |
| GR-ARCH-003 | [Service Modules](service-modules.md) | warning | Modules, V2, V4, Facade, Decorator, Strategy, Outbox |
| GR-ARCH-004 | [System Design](system-design.md) | critical | TieredCache, SingleFlight, CircuitBreaker, GZIP, HA, Observability |
| GR-ARCH-005 | [Stateless Architecture](stateless.md) | critical | HttpSession, SessionScope, static-mutable, Redis, MySQL |
| GR-ARCH-030 | [SOLID & DDD](solid-ddd.md) | critical | SOLID, SRP, OCP, LSP, ISP, DIP, DDD, Aggregate, Rich-Domain |
| GR-ARCH-040 | [Clean Code & Lambda Hell](clean-code.md) | critical | lambda, method-reference, optional-chaining, tap-pattern, clean-code |

## 주요 주제

### 핵심 아키텍처
- **ADR Decisions**: Hexagonal Architecture, DIP, Rich Domain Model, Exception Hierarchy
- **SOLID & DDD**: 5대 원칙 준수, Aggregate Root, ID 참조, 포트/어댑터 패턴
- **Stateless Architecture**: HttpSession 금지, Redis 상태 저장소, Scale-out 준비

### 시스템 설계
- **System Design**: TieredCache, SingleFlight, Circuit Breaker, GZIP 압축, Redis HA
- **Service Modules**: V2/V4 모듈 구조, Facade/Decorator/Strategy 패턴, Transactional Outbox

### 개발 프로세스
- **Multi-Agent Protocol**: 5-Agent Council, Pentagonal Pipeline, Sequential Thinking, Trade-off 기록
- **Clean Code**: Lambda Hell 방지, Method Reference, Optional Chaining, 3-Line Rule

## 카테고리별 가드레일

### Critical (필수 준수)

| 영역 | 가드레일 | 주요 내용 |
|------|----------|-----------|
| **DDD** | GR-ARCH-030 | SOLID 5대 원칙, Aggregate Root, ID 참조만 사용, Rich Domain Model |
| **Clean Code** | GR-ARCH-040 | 3-Line Rule, Method Reference 우선, Optional Chaining, Checked Exception 구조적 분리 |
| **Stateless** | GR-ARCH-005 | HttpSession/@SessionScope 금지, static mutable 상태 금지 |
| **Hexagonal** | GR-ARCH-001 | 포트/어댑터 패턴, DIP 준수, 도메인 순수 자바 |
| **System** | GR-ARCH-004 | TieredCache 필수, SingleFlight, Circuit Breaker + Fallback |

### Warning (권장)

| 영역 | 가드레일 | 주요 내용 |
|------|----------|-----------|
| **Modules** | GR-ARCH-003 | V4 → V2 의존성 금지, Facade 패턴 필수, Decorator Chain |

## 관련 문서

### CLAUDE.md 섹션
- [Section 4: Implementation Logic & SOLID](../../../CLAUDE.md#4-implementation-logic--solid)
- [Section 6: Design Patterns & Structure](../../../CLAUDE.md#6-design-patterns--structure)
- [Section 15: Lambda & Parenthesis Hell](../../../CLAUDE.md#15-anti-pattern-lambda--parenthesis-hell-critical)
- [Section 18: Stateless Architecture Principles](../../../CLAUDE.md#18-stateless-architecture-principures-필수)

### 기술 가이드
- [docs/00_Start_Here/architecture.md](../00_Start_Here/architecture.md) - 시스템 아키텍처 다이어그램
- [docs/00_Start_Here/multi-agent-protocol.md](../00_Start_Here/multi-agent-protocol.md) - 5-Agent Council
- [docs/03_Technical_Guides/service-modules.md](../03_Technical_Guides/service-modules.md) - 서비스 모듈 가이드

### 검증 리포트
- [docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md](../05_Reports/Architecture/2026-02-22-ddd-verification-report.md) - DDD 검증 리포트

### ADR 문서
- [docs/adr/](../01_ADR/) - 전체 ADR 목록

## Verification Skills

```bash
# SOLID 준수 검증
/verify-solids

# Clean Architecture 준수 검증
/verify-clean-architecture

# Stateless 설계 준수 검증
/verify-stateless

# 클린 코드 준수 검증
/verify-clean-code

# Lambda Hell 검증
/verify-lambda-hell

# Optional Chaining 검증
/verify-optional-chaining
```

## 최신 업데이트

- **2026-02-25**: SOLID & DDD 가드레일 추가 (GR-ARCH-030)
- **2026-02-25**: Clean Code & Lambda Hell 가드레일 추가 (GR-ARCH-040)
- **2026-02-25**: INDEX 재구성 및 카테고리별 정리
