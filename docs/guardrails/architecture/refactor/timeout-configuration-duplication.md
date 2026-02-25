---
id: GR-REFACTOR-008
category: architecture/refactor
severity: info
keywords: [timeout, configuration, duplication, magic-number, properties]
languages: [java, kotlin, yaml]
---

# Timeout 설정 패턴 중복

## DON'T (위반 사항/장애 원인)

### 중복 코드
```java
// EquipmentService.java
private static final int LEADER_DEADLINE_SECONDS = 30;
private static final int FOLLOWER_TIMEOUT_SECONDS = LEADER_DEADLINE_SECONDS;

// EquipmentExpectationServiceV4.java
private static final int ASYNC_TIMEOUT_SECONDS = 30;
private static final int DATA_LOAD_TIMEOUT_SECONDS = 30;

// CharacterCreationService.java
private static final long API_TIMEOUT_SECONDS = 10L;

// EquipmentDataResolver.java
private static final int NEXON_API_TIMEOUT_SECONDS = 30;
```

### 위험 요소
- **매직 넘버 분산**: 10, 30 등의 타임아웃 값이 14개 파일에 하드코딩
- **의존성 불일치**: LEADER와 FOLLOWER 타임아웃이 다른 파일에서 다르게 설정될 수 있음
- **테스트 어려움**: 타임아웃 변경 시 14개 파일에서 수정 필요

### 수치
- 중복 파일: 14개
- 매직 넘버 분산

## DO (수정 방법/재발 방지)

### 수정 코드
```yaml
# 1. 중앙화된 Timeout 설정 (application.yml)
app:
  timeout:
    async-computation:
      leader: 30s
      follower: 30s
    external-api:
      nexon: 10s
    cache:
      single-flight: 30s
```

```java
// 2. @ConfigurationProperties로 바인딩
@ConfigurationProperties("app.timeout")
public record TimeoutProperties(
    Duration asyncComputationLeader,
    Duration asyncComputationFollower,
    Duration externalApiNexon,
    Duration cacheSingleFlight
) {}

// 3. Service에서 주입받아 사용
@Service
public class EquipmentService {
    private final TimeoutProperties timeoutProperties;

    public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationAsync(String userIgn) {
        return doCalculation(userIgn)
            .orTimeout(timeoutProperties.asyncComputationLeader().toSeconds(), TimeUnit.SECONDS);
    }
}
```

### 개선 수치 (After)
- 타임아웃 설정 중앙화
- 환경별 타임아웃 조정 용이 (local vs prod)
- 테스트 시 Mock 편리성

### 핵심 원칙
1. **외부화**: 모든 타임아웃을 application.yml로 이동
2. **ConfigurationProperties**: 타입 안전한 바인딩 사용
3. **환경별 구성**: local/dev/prod별로 다른 타임아웃 설정 가능

## 출처
- 문서: [docs/05_Reports/04_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/04_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P1 (중간 수준 중복)
