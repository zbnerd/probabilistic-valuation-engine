# 10장: 시험장 — 장애대응 테스트의 탄생

> "장애를 두려워하지 마라. 대신 장애를 이해하라."
>
> — 5-Agent Council 철학

---

## 철학: 장애를 이해하는 방법

이 책의 모든 장에서 장애대응 테스트가 등장했다. 이 장에서는 그 테스트 전략 자체를 다룬다.

장애대응 테스트의 핵심 철학은 간단하다:

**"장애가 발생할 때 어떻게 반응하는지를, 장애가 발생하기 전에 알아야 한다."**

이것은 가능한 한 많은 장애 시나리오를 미리 주입하고, 시스템의 반응을 관찰하고, 기대치를 충족하는지 검증하는 것을 의미한다.

---

## 5-Agent Council: 다섯 명의 관찰자

장애대응 테스트는 한 명이 수행하지 않는다. 다섯 명의 관찰자가 각자의 시선으로 시스템을 검증한다.

### Red — 장애 주입자 (SRE)

"시스템이 어디서 부서지는가?"

Red는 고의로 장애를 주입한다. DB를 죽이고, 네트워크를 끊고, 메모리를 가득 채우고, 디스크를 꽉 채운다. Red의 목표는 시스템을 부서뜨리는 것.

### Blue — 흐름 검증자 (Architect)

"장애 후에도 아키텍처가 유지되는가?"

Blue는 장애 후의 시스템 동작을 검증한다. 서킷 브레이커가 열렸는지, 폴백이 동작했는지, 복구가 되었는지. Blue는 Red가 부서뜨린 것을 관찰한다.

### Green — 성능 측정자 (Performance)

"장애 중에도 성능이 유지되는가?"

Green은 메트릭을 측정한다. 응답 시간, 처리량, 에러율. 장애 중에도 SLA를 충족하는지 확인한다.

### Purple — 데이터 감사자 (Auditor)

"장애 후에도 데이터가 온전한가?"

Purple은 데이터 무결성을 검증한다. 좋아요 카운트가 정확한지, 이벤트가 중복 처리되지 않았는지, 순서가 보존되었는지.

### Yellow — QA 마스터 (QA)

"전체 품질 기준을 충족하는가?"

Yellow는 종합적인 품질 평가를 수행한다. 각 에이전트의 결과를 취합하고, 전체적인 판단을 내린다.

---

## 19개의 시나리오

장애대응 테스트는 총 19개의 시나리오로 구성되어 있다. 각 시나리오는 특정 장애 상황을 재현한다.

> **시나리오 번호 규칙:** `Sxx`는 Core/Network/Resource/Connection/Data 등 카테고리별 정규 시나리오, `Nxx`는 복합 장애를 재현하는 Nightmare(극한) 시나리오입니다.

### Core (기초): DB, 메모리

| 시나리오 | 내용 | 난이도 |
|----------|------|--------|
| S02 | MySQL Death — DB 크래시 | P0 |
| S03 | OOM — 메모리 고갈 | P0 |

### Network (네트워크): 분할, 지연, 공격

| 시나리오 | 내용 | 난이도 |
|----------|------|--------|
| S04 | Split Brain — 두 명의 왕 | P0 |
| S05 | Clock Drift — 시간 갈라짐 | P0 |
| S06 | Slow Loris — 느린 공주 | P1 |
| S07 | Black Hole Commit — ACK 유실 | P0 |
| S12 | Gray Failure — 회색 실패 | P1 |

### Resource (리소스): 디스크, 재시도, 풀, GC

| 시나리오 | 내용 | 난이도 |
|----------|------|--------|
| S08 | Disk Full — 디스크 꽉참 | P1 |
| S09 | Retry Storm — 재시도 폭풍 | P0 |
| S10 | Pool Exhaustion — 커넥션 풀 고갈 | P0 |
| S11 | GC Pause — GC 정지 | P1 |

### Connection (연결): 유령 커넥션, 락

| 시나리오 | 내용 | 난이도 |
|----------|------|--------|
| S13 | Half-Open Hell — 유령 커넥션 | P1 |
| S17 | Thundering Herd Lock — 락 경합 폭풍 | P0 |

### Data (데이터): 중복, 순서, 오염

| 시나리오 | 내용 | 난이도 |
|----------|------|--------|
| S14 | Duplicate Delivery — 이벤트 중복 | P0 |
| S15 | Out-of-Order — 이벤트 순서 뒤바뀜 | P1 |
| S16 | Config Poisoning — 설정 오염 | P1 |

### Nightmare (극한): 실제 발생 가능한 최악의 시나리오

| 시나리오 | 내용 | 난이도 |
|----------|------|--------|
| N01 | Thundering Herd | P0 |
| N02 | Deadlock Trap | P0 |
| N03 | Thread Pool Exhaustion | P0 |
| N04 | Connection Vampire | P0 |
| N05 | Celebrity Problem | P0 |
| N06 | Timeout Cascade | P0 |
| N07 | Metadata Lock Freeze | P0 |
| N09 | Circular Lock Deadlock | P0 |
| N10 | Caller-Runs Policy | P1 |
| N11 | Lock Fallback Avalanche | P0 |
| N12 | Async Context Loss | P1 |
| N13 | Zombie Outbox | P0 |
| N14 | Pipeline Exception | P1 |
| N15 | AOP Order Problem | P1 |
| N16 | Self-Invocation | P1 |
| N17 | Poison Pill | P0 |
| N18 | Deep Paging | P1 |
| N19 | Compound Failures | P0 |

---

## 테스트 모듈의 분리

장애대응 테스트는 전용 모듈 `module-chaos-test`로 분리되어 있다.

```
module-chaos-test/
└── src/chaos-test/java/maple/expectation/chaos/
    ├── circuitbreaker/
    │   └── CircuitBreakerClosedToOpenChaosTest.java
    └── ... (22개 테스트)
```

일반 테스트와 분리되어 있다. `./gradlew test`로는 실행되지 않고, `./gradlew :module-chaos-test:chaosTest`로만 실행된다.

장애대응 테스트는 시스템에 고의로 장애를 주입하므로, 일반 CI 파이프라인에서 실행하면 안 된다. 전용 워크플로우에서 격리된 환경에서 실행된다.

---

## 검증 기준

각 시나리오는 명확한 Pass/Fail 기준을 가진다.

```
Pass 기준:

P0 (Critical):
  - 데이터 무결성 100%
  - 서킷 브레이커 정상 동작
  - 서비스 연속성 유지 (부분적 허용)
  - 자동 복구 가능

P1 (Important):
  - 성능 SLA 충족 (p99 < 500ms)
  - 폴백 동작 확인
  - 모니터링 메트릭 정상
```

---

## 종합 결과

```
장애대응 테스트 종합 결과:

P0 테스트 (Critical): 13/15 통과 (86.7%)
P1 테스트 (Important): 6/7 통과 (85.7%)
전체: 19/22 통과 (86.4%)

미통과 항목:
  - S16 Config Poisoning: 테스트 미구현
  - N10 Caller-Runs Policy: 시나리오 수정 중
  - N14 Pipeline Exception: 분석 완료, 수정 대기
```

---

## 교훈

**1. 장애는 실험으로 이해한다.**

장애가 발생할 때까지 기다리지 마라. 미리 주입하고 반응을 관찰하라.

**2. 다섯 명의 시선이 필요하다.**

한 명의 시선으로는 전체 그림이 보이지 않는다. 장애 주입, 흐름 검증, 성능 측정, 데이터 감사, QA — 각 관점이 모여야 완전하다.

**3. 시나리오는 실제 장애에서 나온다.**

19개의 시나리오는 이론이 아니라 실제로 겪은 장애에서 출발했다. "이런 일이 있었다" → "재현해보자" → "대응책을 검증하자"의 순환.

**4. 전용 모듈로 격리하라.**

장애대응 테스트는 시스템에 고의로 장애를 준다. 일반 테스트와 섞이면 안 된다.

---

> **다음 장:** [11장: 코드의 철학 — Zero Try-Catch와 LogicExecutor](11_zero_trycatch.md)
