# 2장: 정렬 — 공식을 세우다

> "측정 없이는 최적화할 수 없다. 공식 없이는 측정할 수 없다."

## 2026년 3월 8일, PR #572

1장의 문제를 해결하기 위해 **P2 Unit 7: Connection Pool Alignment with Thread Pool** 작업을 수행했다.

- **PR**: `fix(units-6-7-8): Transaction Binding, Connection Pool, N+1 Query Optimization` (#572)
- **PR**: `fix: Technical debt resolution - All 8 units (P0 security + P1 architecture)` (#573)
- **커밋**: `284861b4`, `57f9f75e`, `7029df5a`

## 풀 사이즈 공식

PostgreSQL 커넥션 풀 사이즈를 결정하는 공식을 수립했다:

```
optimal_pool_size = (CPU cores × 2) + effective_disk_count
```

이 공식은 PostgreSQL wiki에서 제안하는 것이다. 하지만 우리 시스템은 I/O-bound이므로 추가 스케일링이 필요했다.

### t3.small (2 vCPUs) 계산

```
Base:     (2 × 2) + 1 = 5
I/O-bound scaling: 5 × 5 = 25
Alignment rule:    max-pool-size = Tomcat threads
Result:            25
```

### 프로필별 적용

| 프로필 | Tomcat Threads | HikariCP Pool | 변경 전 | 상태 |
|--------|---------------|---------------|---------|------|
| Local | 100 | 100 | 20 | **5배 증가** |
| CI | 200 | 200 | 10 | **20배 증가** |
| Production | 25 | 25 | 20 | 정렬 완료 |

```yaml
# application-ci.yml (수정 후)
spring:
  datasource:
    hikari:
      maximum-pool-size: 200  # P2 Unit 7: Match default Tomcat threads (200)
      minimum-idle: 10
      leak-detection-threshold: 60000  # 60초 이상 반납 안되면 누수 의심
      register-mbeans: true  # JMX로 지표 노출

# application-prod.yml (수정 후)
spring:
  datasource:
    hikari:
      maximum-pool-size: 25  # Match Tomcat threads (prevent connection starvation)
      minimum-idle: 5  # Allow dynamic scaling (save ~50-100MB RAM during low traffic)
      leak-detection-threshold: 60000
      register-mbeans: true

# application-local.yml (수정 후)
spring:
  datasource:
    hikari:
      maximum-pool-size: 100
      minimum-idle: 50
      leak-detection-threshold: 60000
      register-mbeans: true
```

## 모니터링 추가

HikariCP 메트릭을 Prometheus + Grafana에 노출:

```
JMX Metrics (via /actuator/metrics/hikaricp.*):
  ├── hikaricp.connections.active     — 현재 사용 중인 커넥션
  ├── hikaricp.connections.idle       — 유휴 커넥션
  ├── hikaricp.connections.pending    — 대기 중인 스레드
  ├── hikaricp.connections.max        — 최대 커넥션
  ├── hikaricp.connections.min        — 최소 커넥션
  ├── hikaricp.connections.timeout    — 타임아웃 발생 횟수
  └── hikaricp.connections.creation   — 커넥션 생성 시간
```

### Leak Detection

```yaml
leak-detection-threshold: 60000  # 60초
```

커넥션이 60초 이상 반납되지 않으면 경고 로그를 출력한다. 이 설정은 나중에 **Advisory Lock이 커넥션을 훔치는 문제**를 발견하는 데 결정적 역할을 했다 ([5장](./05_advisory_lock.md)).

## 결과: 일시적 개선

풀 정렬 후 단일 인스턴스에서의 커넥션 대기가 사라졌다.

```
Before (max-pool-size: 20):
  connections.active:  ████████████████████ 20/20  POOL EXHAUSTED
  connections.pending: ++++++++++ 47 threads waiting

After (max-pool-size: 25):
  connections.active:  █████████████████    17/25  여유 있음
  connections.pending: 0 threads waiting
```

### 하지만 근본 문제는 남아 있었다

풀을 키웠을 뿐, **왜 커넥션이 부족한지**에 대한 근본 원인은 해결하지 못했다:

1. 여전히 3개의 데이터베이스에 각각 커넥션 풀이 필요
2. 3개의 Outbox 스케줄러가 커넥션을 상시 점유
3. Scale-out 시 인스턴스당 커넥션이 선형 증가

풀 사이즈 정렬은 **지엽적 해결책**이었다. 진짜 문제는 아키텍처에 있었다.

## ADR 문서화

이 결정을 ADR-014로 문서화했다.

```
ADR-014: Connection Pool Alignment with Thread Pool
상태: Accepted
결정: HikariCP maximum-pool-size를 Tomcat threads.max와 정렬
근거:
  - 커넥션 대기 시간이 요청 latency의 97%를 차지
  - 공식: (CPU cores × 2) + effective_disk_count, I/O-bound 스케일링 적용
  - 모니터링: JMX + leak detection 활성화
트레이드오프:
  - 메모리 사용량 증가 (커넥션당 ~5MB)
  - PostgreSQL max_connections 고려 필요
```

---

**다음 장**: [3장 — Scale-out의 벽: 5대에서 RPS 하락](./03_scale_out_wall.md)
