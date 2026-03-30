---
id: GR-REFACTOR-002
category: architecture/refactor
severity: warning
keywords: [timeout, hierarchy, zombie-request, cascade, redis, mysql]
languages: [java, kotlin, yaml]
---

# Timeout Hierarchy 불일치

## DON'T (위반 사항/장애 원인)

### 위험 설정
```yaml
# 불일치하는 타임아웃 계층
Redis: timeout 3s
MySQL: lock_wait 10s
Transaction: timeout 5s
HTTP: connect 3s + response 5s (× 3회 재시도)
TimeLimiter: 28s (상한)
```

### 위험 요소
- **Zombie Request 발생**: 클라이언트 타임아웃 < 서버 처리 체인으로 낭비
- **Connection Pool 낭비**: 처리 중이나 클라이언트는 연결 해제된 요청
- **순서 위반**: 상위 계층 타임아웃이 하위보다 짧아야 함

### 수치 (Before)
- Zombie Request 발생: 빈번
- Redis timeout이 너무 짧아 Connection 실패 과다

## DO (수정 방법/재발 방지)

### 수정 설정
```
TimeLimiter: 28s (상한)
└── HTTP: connect 3s + response 5s (× 3회 재시도)
    └── Redis: timeout 8s, connect 5s
        └── MySQL: lock_wait 8s
            └── TX: timeout 10s
```

### 수정 코드
```java
// RedissonConfig.java
.setTimeout(8000)        // Issue #225: 3s → 8s (Timeout Hierarchy 정렬)
.setConnectTimeout(5000) // Issue #225: 10s → 5s (빠른 연결 실패 감지)

// application.yml
connection-init-sql: "SET SESSION lock_wait_timeout = 8"  # 10 → 8

// TransactionConfig.java
template.setTimeout(10); // 5 → 10 (MySQL lock_wait 8s보다 여유 있게)
```

### 핵심 원칙
1. **계층별 정렬**: Client > HTTP > Redis > MySQL > Transaction 순서
2. **여유 있는 상위**: 상위 계층은 하위 합산보다 충분히 커야 함
3. **빠른 실패 감지**: Connect timeout은 operation timeout보다 짧게

## 출처
- 문서: [docs/05_Reports/05_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md](../../../05_Reports/05_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md)
- 이슈: #225 (Timeout Hierarchy)
- Nightmare: TimeoutCascadeNightmareTest
