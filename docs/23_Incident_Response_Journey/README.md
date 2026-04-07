# 장애대응 여정기 — 97 RPS에서 7,347 RPS까지, 실패가 만든 방패

> "장애는 언제 들이닥칠지 모른다. 하지만 장애 앞에서 어떻게 반응할지는 우리가 결정한다."
>
> — probabilistic-valuation-engine 운영 일지, 2026년 1월

---

## 책 소개

이 책은 **probabilistic-valuation-engine** 프로젝트가 겪은 장애와 대응의 여정을 기록합니다.

2026년 1월, 이 시스템은 초당 97개의 요청을 겨우 처리하는 불안정한 상태였습니다. 3개의 데이터베이스(MySQL, Redis, MongoDB)가 얽힌 복잡한 아키텍처, 외부 API 장애에 속수무책인 서킷 브레이커 없는 구조, 커넥션 풀 고갈에 대한 대비 없는 설계 — 어느 하나 튼튼한 곳이 없었습니다.

3개월 후, 이 시스템은 초당 7,347개의 요청을 안정적으로 처리하는 시스템이 되었습니다. 단 하나의 데이터베이스로 단순화되었고, 34개의 장애대응 테스트(정규 16 + 극한 18)가 모든 위험 시나리오를 검증하며, 88개의 가드레일이 코드 수준에서 재발을 방지합니다.

이 변화는 한 번의 마법이 아니었습니다. 수많은 장애, 새벽의 디버깅, 실험과 실패, 그리고 "이건 다시는 안 된다"는 다짐의 누적이었습니다.

이 책은 그 여정을 담습니다.

---

## 목차

### [프롤로그: 세 개의 데이터베이스와 97개의 요청](00_prologue.md)
*2026년 1월, 불안정한 시작*

### [1장: 도미노 — 외부 API 하나가 쓰러뜨린 전체 시스템](01_cascade_failure.md)
*Nexon API 타임아웃 → 스레드 풀 고갈 → 전체 서비스 마비. 장애 격리가 없다는 것이 무엇을 의미하는지 몸소 체험한 날*

### [2장: 방패를 들다 — Resilience4j와 서킷 브레이커의 탄생](02_circuit_breaker.md)
*ADR-052. "더 이상 외부 장애가 우리 시스템을 죽이지 못하게 하자" — Resilience4j 도입과 323회의 서킷 브레이커 트립 기록*

### [3장: 보이지 않는 적 — 커넥션 풀 고갈의 여정](03_connection_pool.md)
*89개에서 25개로. HikariCP 정렬 불일치, 스케줄러 스레드 무한 생성, 그리고 3개 DB에서 1개 PostgreSQL로의 대이주까지*

### [4장: 뇌우 — 캐시 스탬피드와 SingleFlight의 깨달음](04_cache_stampede.md)
*캐시 만료 순간, 100개의 동일한 쿼리가 DB로 쏟아지는 날. ADR-003, TieredCache, 그리고 "완벽한 최적화가 역효과를 낸 이야기"*

### [5장: 가상의 그림자 — Virtual Thread Pinning과의 사투](05_virtual_thread.md)
*"synchronized 하나가 carrier thread를 고정시킨다고?" — Virtual Thread의 함정과 ReentrantLock으로의 전환*

### [6장: 락의 미로 — Advisory Lock과 교착상태의 함정](06_advisory_lock.md)
*세션 스코프 락이 HikariCP와 만났을 때 벌어진 일. pg_advisory_lock에서 pg_try_advisory_xact_lock으로의 탈출*

### [7장: 침묵의 경보 — 장애 중 알림마저 죽었을 때](07_alert_silence.md)
*커넥션 풀이 고갈되었는데 Discord 알림도 안 온다. 알림 시스템이 DB에 의존하고 있었다는 충격적인 발견*

### [8장: 좋아요의 역습 — Like 도메인 레이스 컨디션](08_like_domain.md)
*liked 상태와 like_count가 서로 다른 값을 보여준 날. 원자적 연산과 DB 트리거, 그리고 401 인증 버그까지*

### [9장: 대이주 — Redis Outbox에서 PGMQ로](09_great_migration.md)
*이중 쓰기의 복잡성, 42개 파일의 Outbox 코드, 그리고 PostgreSQL 네이티브 큐로의 전환. 5단계에 걸친 대규모 마이그레이션*

### [10장: 시험장 — 장애대응 테스트의 탄생](10_test_strategy.md)
*Red, Blue, Green, Purple, Yellow — 5-Agent Council 접근법. 34개의 시나리오(정규 16 + 극한 18)가 시스템을 어떻게 단련시켰는가*

### [11장: 코드의 철학 — Zero Try-Catch와 LogicExecutor](11_zero_trycatch.md)
*"try-catch를 쓰지 마라" — 7가지 실행 패턴으로 예외 처리를 체계화한 이유와 과정. ADR-044*

### [12장: 완성 — 7,347 RPS, 실패가 만든 성과](12_7347_rps.md)
*p99 4,100ms에서 36ms로. 76배의 성능 향상. 하지만 진짜 성과는 숫자가 아니었다*

### [에필로그: 아직 끝나지 않은 여정](epilogue.md)
*해결된 것과 여전히 남아있는 것들. 다음 장애를 대비하는 자세*

---

## 용어 통일

이 책에서는 다음 용어를 **장애대응 테스트**로 통일하여 사용합니다:

| 이전 용어 | 통일 용어 |
|-----------|----------|
| 카오스 테스트 (Chaos Test) | 장애대응 테스트 |
| 카오스 엔지니어링 (Chaos Engineering) | 장애대응 테스트 |
| 나이트메어 테스트 (Nightmare Test) | 장애대응 테스트 (극한 시나리오) |

---

## 관련 문서

| 주제 | 위치 |
|------|------|
| ADR (아키텍처 결정 기록) | [docs/01_ADR/](../01_ADR/) |
| 장애대응 테스트 원본 시나리오 | [docs/02_Chaos_Engineering/](../02_Chaos_Engineering/) |
| 가드레일 인덱스 (88개 패턴) | [docs/16_Guardrails/INDEX.md](../16_Guardrails/INDEX.md) |
| 커넥션 풀 여정기 | [docs/13_Connection_Pool_Journey/](../13_Connection_Pool_Journey/) |
| 퍼포먼스 여정기 | [docs/06_Performance_Journey/](../06_Performance_Journey/) |
| 관측성 시스템 | [docs/11_Observability/](../11_Observability/) |
| 보안 장애 대응 플레이북 | [docs/16_Guardrails/security/](../16_Guardrails/security/) |
