# 1장: 첫 번째 경고 — HikariCP 정렬 실패

> "20개의 커넥션으로 200개의 스레드를 막을 수 없다."

## 문제의 발견

2026년 2월, 성능 여정 7장에서 940 RPS를 달성했다. Scale-out 테스트를 위해 인스턴스를 5대로 늘렸다.

기대: RPS 5배 증가 (940 × 5 = 4,700)

현실: RPS **하락**.

```
Load Test Results (5 instances):
  Instance 1: 187 RPS
  Instance 2: 192 RPS
  Instance 3: 189 RPS
  Instance 4: 191 RPS
  Instance 5: 188 RPS
  ──────────────────────────────
  Total: 947 RPS  ← 5대나 1대나 거의 동일
```

합계가 947 RPS. 1대일 때(940 RPS)와 거의 차이가 없었다.

## 원인 분석: HikariCP 커넥션 고갈

Grafana에서 HikariCP 메트릭을 확인했다.

```
# application.yml (당시)
spring:
  datasource:
    hikari:
      maximum-pool-size: 20        ← 문제의 핵심
      minimum-idle: 10

# Tomcat 설정 (당시)
server:
  tomcat:
    threads:
      max: 200                     ← HikariCP의 10배
```

**HikariCP 풀 사이즈(20)가 Tomcat 스레드 풀(200)에 비해 너무 작았다.**

### 커넥션 대기 시간 증가

```
Timeline (단일 요청):
0ms    ─ Tomcat 스레드 할당
1ms    ─ Cache 조회 (Redis)
2ms    ─ Cache miss 감지
2ms    ─ HikariCP 커넥션 요청 ← 여기서 대기 발생
       ...
152ms  ─ 드디어 커넥션 획득 (150ms 대기!)
153ms  ─ MySQL 쿼리 실행 (1ms)
154ms  ─ 커넥션 반환
155ms  ─ 응답 완료

총 소요: 155ms (그 중 커넥션 대기: 150ms = 97%)
```

요청 처리 시간의 **97%** 가 커넥션 대기 시간이었다.

### 왜 20이었나

HikariCP의 공식 권장사항은:

> "Most connection pools can function well with a properly-sized pool of 10-20 connections."

HikariCP의 설계자는 "적은 커넥션이 더 빠르다"고 주장한다. 디스크 I/O가 병목인 전통적인 데이터베이스에서는 맞는 말이다.

하지만 우리 시스템은 **I/O-bound**였다:
- 캐시 히트율 99.99% (대부분 Redis에서 해결)
- MySQL 쿼리는 캐시 미스 시에만 실행
- 동시에 200개 스레드가 실행 중

200개 스레드가 동시에 캐시 미스를 겪으면, 20개 커넥션으로는 절대 부족하다.

## 모니터링에서 포착된 패턴

```
HikariCP Pool Usage Over Time:

  20 ┤██████████████████████████████████████████████████
     │ ← 커넥션 풀 고갈 구간 (active = max)
  15 ┤████████████████████████
     │ ← 정상 구간 (여유 있음)
  10 ┤████████████
   5 ┤██████
   0 ┤
     └────┬─────┬─────┬─────┬─────┬─────┬─────┬────
         00:00 04:00 08:00 12:00 16:00 20:00 24:00

     ↑ 트래픽 증가할 때마다 풀 고갈 반복
```

**Active connections가 `max-pool-size`에 도달하면, 이후 요청은 모두 대기.** 이것이 Scale-out이 효과가 없었던 이유였다.

각 인스턴스가 독립적으로 커넥션 풀 고갈을 겪었기 때문에, 인스턴스를 늘려도 전체 RPS가 향상되지 않았다.

## CI 환경의 더 심각한 문제

CI 프로필에서 상황이 더 나빴다.

```yaml
# application-ci.yml (당시)
spring:
  datasource:
    hikari:
      maximum-pool-size: 10   ← CI가 10이었다!
```

CI 환경의 Tomcat 기본 스레드는 200인데, HikariCP는 10. **20:1 비율.** 테스트만 해도 커넥션 부족이 발생하는 수준이었다.

## 커넥션 풀 모니터링의 부재

더 큰 문제는 **모니터링 부재**였다.

```yaml
# 당시 설정 — JMX 미활성화
spring:
  datasource:
    hikari:
      # register-mbeans: 없음
      # leak-detection-threshold: 없음
```

커넥션 풀 메트릭이 Actuator에 노출되지 않았다. 커넥션 대기 시간이 길어지는 것을 로그로만 확인할 수 있었고, 이마저도 `DEBUG` 레벨이라 기본적으로 보이지 않았다.

## 배운 점

> **"커넥션 풀 사이즈는 '충분히 큰가?'가 아니라 '얼마나 많은 스레드가 동시에 DB에 접근하는가?'로 결정해야 한다."**

HikariCP의 "10-20개면 충분하다"는 권장은 **커넥션당 처리량이 높은 전통적인 웹 애플리케이션**을 기준으로 한 것이다. 캐시 뒤에 있는 데이터베이스에 폭발적으로 접근하는 현대적인 아키텍처에는 맞지 않았다.

---

**다음 장**: [2장 — 정렬: 공식을 세우다](./02_alignment_fix.md)
