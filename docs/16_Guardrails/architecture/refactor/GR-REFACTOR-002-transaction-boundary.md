---
id: GR-REFACTOR-002
category: architecture/refactor
severity: critical
keywords: [transaction, connection-pool, async, blocking, connection-vampire]
languages: [java, kotlin]
---

# Transaction Boundary - API 호출 분리

## DON'T (Connection Vampire Anti-pattern)
- @Transactional 범위 내에서 `.join()` 호출로 DB Connection 장시간 점유
- 외부 API 호출이 트랜잭션 안에서 수행되어 Connection Pool 고갈 유발

```java
// Bad: @Transactional 내에서 .join() 호출
@Transactional(propagation = Propagation.REQUIRES_NEW)
public GameCharacter createNewCharacter(String userIgn) {
    // ❌ 28초간 DB Connection 점유
    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn)
        .join()
        .getOcid();
    return gameCharacterRepository.saveAndFlush(new GameCharacter(cleanUserIgn, ocid));
}
```

```kotlin
// Bad: 트랜잭션 내에서 블로킹 호출
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun createNewCharacter(userIgn: String): GameCharacter {
    // ❌ Connection 장시간 점유
    val ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn)
        .join()
        .ocid
    return gameCharacterRepository.saveAndFlush(GameCharacter(cleanUserIgn, ocid))
}
```

**영향:**
- Connection Hold Time: 28초
- Connection Timeout 발생
- Pool Exhaustion → 서비스 장애

## DO (Best Practice - 트랜잭션 경계 분리)
- API 호출은 트랜잭션 **밖**에서 수행
- DB 작업만 트랜잭션 **안**에서 수행
- Connection 점유 시간: 28초 → ~100ms (99.6% 개선)

```java
// Good: 트랜잭션 경계 분리
@ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
public GameCharacter createNewCharacter(String userIgn) {
    return executor.executeOrCatch(
            () -> {
                // Step 1: API 호출 (트랜잭션 밖 - DB Connection 점유 없음)
                String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn)
                    .join()
                    .getOcid();

                // Step 2: DB 저장 (트랜잭션 안 - 짧은 Connection 점유 ~100ms)
                return saveCharacterWithCaching(cleanUserIgn, ocid);
            },
            (e) -> { /* 예외 처리 */ },
            TaskContext.of("GameCharacterService", "createNewCharacter", userIgn)
    );
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public GameCharacter saveCharacterWithCaching(String userIgn, String ocid) {
    // DB 저장만 트랜잭션 안에서 수행
    return gameCharacterRepository.saveAndFlush(new GameCharacter(userIgn, ocid));
}
```

```kotlin
// Good: 명시적 경계 분리
@ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
fun createNewCharacter(userIgn: String): GameCharacter {
    return executor.executeOrCatch(
        ThrowingSupplier {
            // Step 1: API 호출 (트랜잭션 밖)
            val ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn)
                .join()
                .ocid

            // Step 2: DB 저장 (트랜잭션 안)
            saveCharacterWithCaching(cleanUserIgn, ocid)
        },
        Function { e -> /* 예외 처리 */ },
        TaskContext.of("GameCharacterService", "createNewCharacter", userIgn)
    )
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
fun saveCharacterWithCaching(userIgn: String, ocid: String): GameCharacter {
    // DB 저장만 수행
}
```

## Prometheus 메트릭 검증

```promql
# Connection Timeout (0이어야 정상)
hikaricp_connections_timeout_total{pool="MySQLLockPool"} == 0

# Connection Hold Time (1초 미만이어야 정상)
hikaricp_connections_usage_seconds_max{pool="MySQLLockPool"} < 1

# Pending Connections (0이어야 정상)
hikaricp_connections_pending{pool="MySQLLockPool"} == 0
```

## 출처
- [P1 Nightmare Issues Resolution Report](../../../../05_Reports/05_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md) - Issue #226
- [CLAUDE.md](../../../../CLAUDE.md) - Section 21 (Async Non-Blocking Pipeline)
- [async-concurrency.md](../../../../03_Technical_Guides/async-concurrency.md) - Section 21

## Before/After 메트릭

| 메트릭 | Before | After | 개선율 |
|--------|--------|-------|--------|
| Connection Timeout | 40 | 0 | 100% |
| Connection Hold Time | 28s | ~100ms | 99.6% |
| Pool Exhaustion Risk | HIGH | NONE | - |
