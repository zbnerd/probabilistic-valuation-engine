---
id: GR-REFACTOR-001
category: architecture/refactor
severity: critical
keywords: [transactional, join, connection-pool, vampire, blocking]
languages: [java, kotlin]
---

# Connection Vampire Anti-Pattern

## DON'T (위반 사항/장애 원인)

### 위험 코드
```java
// @Transactional 범위 내에서 .join() 호출
@Transactional(propagation = Propagation.REQUIRES_NEW)
public GameCharacter createNewCharacter(String userIgn) {
    String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).join().getOcid();
    return gameCharacterRepository.saveAndFlush(new GameCharacter(cleanUserIgn, ocid));
}
```

### 위험 요소
- **Connection 점유 시간**: 최대 28초 (HTTP timeout) 동안 DB Connection을 점유
- **Connection Pool 고갈**: 다른 요청이 Connection을 획득하지 못해 Timeout 발생
- **위반 규칙**: CLAUDE.md Section 21 (Async Non-Blocking Pipeline)

### 수치 (Before)
- Connection Timeout: 40건
- Connection Hold Time: 28초
- Pool Exhaustion Risk: HIGH

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 트랜잭션 경계 분리: API 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안
@ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
public GameCharacter createNewCharacter(String userIgn) {
    return executor.executeOrCatch(
            () -> {
                // Step 1: API 호출 (트랜잭션 밖 - DB Connection 점유 없음)
                String ocid = nexonApiClient.getOcidByCharacterName(cleanUserIgn).join().getOcid();

                // Step 2: DB 저장 (트랜잭션 안 - 짧은 Connection 점유 ~100ms)
                return saveCharacterWithCaching(cleanUserIgn, ocid);
            },
            (e) -> { /* 예외 처리 */ },
            context
    );
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public GameCharacter saveCharacterWithCaching(String userIgn, String ocid) {
    // DB 저장만 트랜잭션 안에서 수행
}
```

### 개선 수치 (After)
- Connection Timeout: 40 → 0 (100% 감소)
- Connection Hold Time: 28s → ~100ms (99.6% 감소)
- Pool Exhaustion Risk: HIGH → NONE

### 핵심 원칙
1. **트랜잭션 경계 분리**: API 호출(I/O)과 DB 작업 분리
2. **Connection 점유 최소화**: 트랜잭션 안에서는 DB 작업만 수행
3. **LogicExecutor 패턴**: try-catch 직접 사용 금지

## 출처
- 문서: [docs/05_Reports/05_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md](../../../05_Reports/05_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md)
- 이슈: #226 (Connection Vampire)
- Nightmare: ConnectionVampireNightmareTest
- ADR: [ADR-010](../../../01_ADR/ADR-010-outbox-pattern.md)
