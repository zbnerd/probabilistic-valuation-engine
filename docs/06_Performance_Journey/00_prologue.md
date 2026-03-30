# 프롤로그: 97 RPS에서 시작하다

> "서버가 느리다" — 이 한마디가 3개월간의 여정을 시작했다.

## 2026년 1월, 상황

MapleExpectation은 메이플스토리 장비의 확률적 기대비용을 계산하는 서비스다. 사용자가 캐릭터 이름을 입력하면, Nexon API에서 장비 데이터를 가져와 3개 프리셋의 확률을 계산하고 결과를 반환한다.

문제는 속도였다.

로컬 개발 환경에서 응답이 2초씩 걸렸다. 동시 사용자가 10명만 넘어도 타임아웃이 났다. 공식 부하 테스트에서 **223 RPS**를 기록했지만, 최적화 시도 직후 **97 RPS**까지 하락했다([2장](./02_singleflight_regression.md)). 이것이 이 여정의 실제 출발점이다.

> **Note**: 초기 비공식 측정에서는 90~120 RPS 수준이었다. 공식 부하 테스트 결과는 1장(223 RPS)과 2장(97 RPS)을 참조.

## 인프라 구성 (당시)

```
Client → Spring Boot (Java 21, Virtual Threads)
           ├── Redis 7.0 (Master + Slave + 3 Sentinel) — 캐시, 분산락, Pub/Sub
           ├── MySQL 8.0 — 영속성 저장
           ├── MongoDB — 이벤트 스토어
           └── Nexon API — 외부 장비 데이터
```

Redis, MySQL, MongoDB 세 개의 데이터베이스가 얽혀 있었다. Redis는 캐시와 분산락, MySQL은 영속성, MongoDB는 이벤트 스토어로 사용 중이었다.

## 왜 느렸나 — 초기 진단

1. **요청당 작업이 무겁다**: Nexon API 호출(257ms) + 200~300KB JSON 파싱 + 3개 프리셋 확률 계산 + GZIP 압축 = 총 약 500ms
2. **Redis 네트워크 왕복**: 캐시 조회마다 Redis를 거친다. 로컬이어도 1~2ms씩 소모
3. **동기 DB 저장**: 계산 결과를 MySQL에 저장하는 데 15~30ms. 캐시 히트가 아닌 이상 이 시간이 매 요청에 추가된다
4. **스레드풀 부족**: `expectationComputeExecutor`가 core 4, max 8, queue-capacity 200. 100명 동시 요청에 처리 한계

## 여정의 시작

이 문제를 해결하기 위해 5-Agent Council을 구성했다. Architect, Performance, QA, SRE, Auditor 다섯 관점에서 시스템을 분석하고 개선하기로 했다.

첫 번째 질문: **"현재 정확히 어디가 병목인가?"**

정확히 측정하지 않으면 최적화할 수 없다. 그래서 첫 번째 부하 테스트를 실시했다.

---

**다음 장**: [1장 — 첫 번째 측정: 현재가 얼마나 느린가](./01_chaos_baseline.md)
