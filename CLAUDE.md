# Probabilistic Valuation Engine

Claude Code가 이 프로젝트에서 작업할 때 따라야 할 규칙은 `.claude/rules/` 에 분리되어 있습니다.

## Rules Index

| 파일 | 내용 | 로딩 조건 |
|------|------|-----------|
| `critical-rules.md` | .env 보호, 서버 IP, 환경 설정 | 항상 |
| `rpi-workflow.md` | RPI 워크플로우, Context 최적화 | 항상 |
| `code-rules.md` | Try-Catch 금지, Lambda Hell, BigDecimal, Optional, 로깅 | 항상 |
| `code-style.md` | 로깅 패턴, TaskContext, DTO/Entity, ObjectMapper, Bean 중복 방지 | `**/*.kt`, `**/*.java` |
| `kotlin-null-safety.md` | `!!` 금지, `.orElse(null)` 금지, Optional 체이닝 | `**/*.kt`, `**/*.java` |
| `architecture-guardrails.md` | Stateless, Lock, TieredCache, 비동기 모델, Cache Key 일관성 | 항상 |
| `module-boundaries.md` | Hexagonal Architecture, Port/Adapter, 모듈 의존성 | 항상 |
| `async-patterns.md` | join()/get()/runBlocking 금지, CF 체이닝, CompletionException 언래핑, finally 보장 | 항상 |
| `async-concurrency.md` | Semaphore, Executor sizing, Graceful shutdown, Fan-out, Flat Work Queue, Lock scope | 항상 |
| `mq-messaging.md` | MQ ACK 순서, Retry/Visibility 정렬, In-memory state 금지 | 항상 |
| `data-access.md` | N+1 금지, Transaction scope, JSONB, Bulk 연산 | `module-infra/`, `module-app/` |
| `workflow-rules.md` | Definition of Done, 검증 명령어, Flaky Test 방지 | 항상 |
| `testing-conventions.md` | Test Base Class, DatabaseCleaner, Test Tag, H2 금지 | `**/src/test/**` |
| `yaml-config.md` | YAML 수정 규칙, 프로필 구조 | `**/*.yml`, `**/*.yaml` |
| `build-conventions.md` | Plain JAR, Kotlin→Java 컴파일, JPA allOpen, 설정 외부화 | `**/build.gradle*` |
| `security-rules.md` | Deny-by-default, API Key 검증, Rate limiting | security/config 파일 |
| `db-migration.md` | Migration 완전성, Forward compatibility | `**/migration/**`, `**/*.sql` |
| `skill-routing.md` | Skill 라우팅 규칙 | 항상 |

## 상세 문서 (참조)

| 주제 | 위치 |
|------|------|
| 인프라 (Cache, Security) | [docs/03_Technical_Guides/infrastructure.md](docs/03_Technical_Guides/infrastructure.md) |
| 비동기 & 동시성 | [docs/03_Technical_Guides/async-concurrency.md](docs/03_Technical_Guides/async-concurrency.md) |
| 테스트 가이드 | [docs/03_Technical_Guides/testing-guide.md](docs/03_Technical_Guides/testing-guide.md) |
| 멀티 에이전트 프로토콜 | [docs/00_Start_Here/multi-agent-protocol.md](docs/00_Start_Here/multi-agent-protocol.md) |
| 카오스 엔지니어링 | [docs/02_Chaos_Engineering/](docs/02_Chaos_Engineering/) |
| 서비스 모듈 | [docs/03_Technical_Guides/service-modules.md](docs/03_Technical_Guides/service-modules.md) |
| 퍼포먼스 저니 | [docs/06_Performance_Journey/](docs/06_Performance_Journey/) |
| 심화 교재 | [docs/07_Deep_Dive_Textbook/](docs/07_Deep_Dive_Textbook/) |
| Scale-out 분석 | [docs/05_Reports/](docs/05_Reports/) |
| ADR | [docs/01_ADR/](docs/01_ADR/) |
| 계획 | [docs/09_Plans/](docs/09_Plans/) |
| 마이그레이션 | [docs/10_Migration/](docs/10_Migration/) |
| 관측성 | [docs/11_Observability/](docs/11_Observability/) |
| 이벤트 스키마 | [docs/12_Events/](docs/12_Events/) |
| 가드레일 | [docs/16_Guardrails/](docs/16_Guardrails/) |
| 운영 가이드 | [docs/21_Operations/](docs/21_Operations/) |
