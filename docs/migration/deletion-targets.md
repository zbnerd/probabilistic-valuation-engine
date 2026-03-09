# Deletion Targets - PostgreSQL Migration

> **Last Updated**: 2026-03-09
> **Branch**: `v2/postgresql-redesign`
> **Related Issues**: #548, #547, #551

## Overview

이 문서는 PostgreSQL 단일 DB 마이그레이션 과정에서 삭제될 파일들을 추적합니다.
삭제는 해당 기능이 PostgreSQL 기반 구현으로 대체된 후 수행됩니다.

---

## Phase 0: Foundation (Current Phase)

### 삭제 불필요
현재 단계는 Kotlin 변환 기반 구축만 수행하므로 삭제 대상 없음.

---

## Phase 1: Core Data Layer

### MySQL 관련 파일 (삭제 예정)

```
# JPA Entities (PostgreSQL jsonb로 대체 후 삭제)
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/*.kt

# MySQL Repository (PostgreSQL Repository로 대체 후 삭제)
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/*RepositoryImpl.kt

# MySQL 설정 (PostgreSQL 설정으로 대체 후 삭제)
module-infra/src/main/resources/application-mysql.yml
```

### MongoDB 관련 파일 (삭제 예정)

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/MongoDBConfig.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/MongoDBHealthIndicator.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/view/*.kt (MongoDB Views)
```

---

## Phase 2: Message Queue

### Redis Streams 관련 파일 (PGMQ로 대체 후 삭제)

```
# Stream Publisher/Consumer
module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/RedisStreamPublisher.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/RedisStreamEventConsumer.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/RedisEventPublisher.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/RedisMessageQueue.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/RedisMessageTopic.kt

# Outbox Processors (PGMQ 기반으로 대체 후 삭제)
module-infra/src/main/kotlin/maple/expectation/infrastructure/donation/outbox/OutboxFetchFacade.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/donation/outbox/OutboxMetrics.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/donation/outbox/OutboxProcessor.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/event/outbox/EventDlqHandler.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/event/outbox/EventOutboxFetchFacade.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/event/outbox/EventOutboxProcessor.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/nexon/outbox/NexonApiOutboxFetchFacade.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/nexon/outbox/NexonApiOutboxMetrics.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/nexon/outbox/NexonApiOutboxProcessor.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/nexon/outbox/NexonApiRetryClient.kt
```

---

## Phase 3: Locking & Caching

### Redisson 분산 락 관련 파일 (Advisory Lock으로 대체 후 삭제)

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/config/RedissonConfig.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/RedisDistributedLockStrategy.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/redis/RedissonOperationAdapter.kt
```

### Redis 캐시 관련 파일 (Caffeine으로 대체 후 삭제)

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/RedisCacheInvalidationPublisher.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/RedisCacheInvalidationSubscriber.kt
```

### Redis Buffer 관련 파일 (PostgreSQL UNLOGGED TABLE로 대체 후 삭제)

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RedisBufferRepositoryImpl.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RedisSessionRepositoryImpl.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RedisRefreshTokenRepositoryImpl.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/RedisLikeBufferStorage.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/RedisLikeRelationBuffer.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/RedisLikeRelationBufferAdapter.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/compensation/RedisCompensationCommand.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/realtime/RedisLikeEventPublisher.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/realtime/RedisLikeEventSubscriber.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/realtime/ReliableRedisLikeEventPublisher.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/realtime/ReliableRedisLikeEventSubscriber.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/persistence/RedisEquipmentPersistenceTracker.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/strategy/RedisLuaScriptExecutor.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/strategy/RedisBufferStrategy.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/strategy/RedisQueueMetricsManager.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/strategy/RedisQueueRecoveryHandler.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/RedisKey.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/redis/script/RedissonLikeAtomicOperations.kt
module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/RedisMetricsCollector.kt
```

---

## Phase 9: Deployment

### Docker Compose 정리

```yaml
# docker-compose.yml에서 제거
services:
  db:              # MySQL → PostgreSQL로 대체
  mongodb:         # 제거 (PostgreSQL jsonb로 대체)
  redis-master:    # 제거 (PGMQ + Advisory Lock으로 대체)
  redis-slave:     # 제거
  redis-sentinel-*: # 제거

# 추가
services:
  postgres:        # PostgreSQL 16 + PGMQ Extension
```

### application.yml 정리

```yaml
# 제거
spring:
  datasource:
    url: jdbc:mysql://...    # MySQL 설정 제거
  data:
    mongodb:                  # MongoDB 설정 제거
  redis:                      # Redis 설정 제거
```

---

## Disabled Files (현재 비활성화된 파일)

```
module-core/src/test/java/maple/expectation/properties/DeterminismPropertiesTemplate.java.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/event/outbox/EventOutboxIntegrationTest.kt.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/event/outbox/EventOutboxProcessorTest.kt.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/event/outbox/EventDlqHandlerTest.kt.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/messaging/RedisStreamEventConsumerTest.kt.disabled
```

---

## Deletion Checklist

각 Phase 완료 시 해당 파일들을 삭제하고 체크:

- [ ] Phase 1: MySQL + MongoDB 파일 삭제
- [ ] Phase 2: Redis Streams + Outbox 파일 삭제
- [ ] Phase 3: Redisson + Redis Cache/Buffer 파일 삭제
- [ ] Phase 9: docker-compose.yml 정리

---

## Notes

1. **삭제 순서**: 각 기능이 PostgreSQL 기반 구현으로 대체된 후에만 삭제
2. **테스트 검증**: 삭제 전 해당 파일을 사용하는 테스트가 모두 새 구현으로 업데이트되었는지 확인
3. **Git History**: 삭제된 파일은 Git history에서 복구 가능
4. **의존성 정리**: 파일 삭제 후 build.gradle.kts에서 관련 의존성도 제거 필요
