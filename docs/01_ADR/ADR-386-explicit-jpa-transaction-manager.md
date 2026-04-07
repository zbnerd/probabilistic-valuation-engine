# ADR-386: Explicit JPA TransactionManager Bean Registration

**Status**: Accepted
**Date**: 2026-04-04
**Context**: TransactionConfig, LockHikariConfig

## Context

프로젝트는 2개의 DataSource를 사용한다:

1. **Primary DataSource**: Spring Boot 자동설정 (`spring.datasource.*`)
2. **Lock DataSource**: `LockHikariConfig`에서 수동 생성 (`lockDataSource`)

`LockHikariConfig`은 락 전용 `lockTransactionManager` bean을 생성하는데,
이 bean의 타입이 `PlatformTransactionManager`이어서
Spring Boot의 `@ConditionalOnMissingBean(PlatformTransactionManager.class)` 조건이 트리거되어
**JPA `transactionManager` bean 자동설정이 백오프**된다.

### 증상

```
NoSuchBeanDefinitionException:
No bean named 'transactionManager' available
```

`@Transactional("transactionManager")`을 사용하는 `BatchWriter` 등의
스케줄드 태스크가 5초마다 실패.

### 왜 local 프로필은 괜찮았나

`LockHikariConfig`은 `@Profile("!test & !chaos & !container & !pgtest")`로
모든 프로필에서 활성화된다. local에서도 동일한 문제가 있을 수 있으나,
`BatchWriter`가 사용하는 `MessageQueue` bean이 local에서 비활성화되어
에러가 표면화되지 않았을 가능성이 높다.

## Decision

`TransactionConfig`에 명시적으로 JPA `transactionManager` bean을 등록한다.

```kotlin
@Bean("transactionManager")
@Primary
fun transactionManager(entityManagerFactory: EntityManagerFactory): PlatformTransactionManager =
    JpaTransactionManager(entityManagerFactory)
```

### 고려한 대안

| 대안 | 기각 이유 |
|------|----------|
| LockHikariConfig에서 `@ConditionalOnMissingBean` 우회 | Spring Boot 내부 조건 변경 불가 |
| `@Qualifier`로 모든 `@Transactional` 변경 | 전체 코드베이스 수정 필요 |
| `lockTransactionManager` 타입 변경 | `PlatformTransactionManager` 인터페이스 필요 |

## Consequences

### 긍정
- `@Transactional("transactionManager")` 정상 동작
- multi-DataSource 환경에서 bean 이름 명확화
- `@Primary`로 기본 트랜잭션 매니저 지정

### 부정
- Spring Boot 자동설정 대신 수동 bean 등록
- `EntityManagerFactory` 의존성 추가

### 주의
- `lockTransactionManager`는 락 전용이므로 JPA 트랜잭션에 사용되지 않음
- 새 DataSource 추가 시 반드시 전용 TransactionManager와 함께 명시적 등록 필요
