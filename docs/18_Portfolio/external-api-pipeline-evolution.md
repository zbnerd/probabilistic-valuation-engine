# External API Pipeline Evolution

## 1. 출발점: "외부 API 기반 계산 서비스"가 생각보다 무거운 워크로드였음

처음엔 단순히 Nexon Open API를 호출해서 계산 결과를 만들고, DB에 저장하고, ReadPath에서 조회하는 구조였다.

실제 워크로드를 뜯어보니 단순 CRUD가 아니었음.

```
1 view 처리 =
  외부 API 호출
  + 200~300KB JSON 응답 다운로드
  + JSON 파싱 / 정규화
  + 확률 계산
  + 결과 직렬화 / 압축 / 해시
  + DB 저장
  + Projection / Read Model 반영
```

워크로드 규모:

| 기준 | 수치 |
|------|------|
| 30 TPS | 하루 약 2,592,000건 (약 260만 건/day) |
| 건당 200~300KB | 하루 약 0.5~0.8TB raw external API payload |
| CHARACTER_BASIC 200/sec | 하루 약 17,280,000건 (약 1,728만 건/day) |
| 200 TPS x 300KB | 약 60MB/sec = 약 5.2TB/day raw payload |

## 2. 기존 구조의 문제: 강결합 God Worker

하나의 worker가 너무 많은 책임을 가지고 있었음.

```
Client Request
→ Job 생성
→ External API 호출
→ Snapshot/Input 저장
→ 계산
→ 결과 저장
→ Outbox/Projection
→ Read Model 반영
```

`external_api_queue`는 이름은 External API queue였지만 실제로는 "종합 메시지 허브"였음.

### external_api_queue 실제 책임
- OCID resolve
- Nexon API fetch
- snapshot 저장
- calculation input 생성
- pure calculation
- result serialize/gzip/hash
- result persist
- job complete
- outbox/projection trigger

병목 로그가 `ExternalApiWorker slow`로 나와도, 실제로 느린 게 외부 API인지, 계산인지, DB write인지, projection인지 알기 어려웠음.

## 3. PGMQ의 한계: SRP vs 처리량 딜레마

### PGMQ 장점
- Postgres 내장
- 운영 단순
- 별도 Kafka 인프라 불필요
- DB transaction과 함께 다루기 쉬움

### PGMQ 문제
PGMQ는 결국 Postgres-backed MQ.

```
PGMQ hop = Postgres insert/read/update/archive/WAL
```

**큐를 잘게 나누면:**
- 책임 분리 좋아짐
- 병목 관측 쉬워짐
- 하지만 PGMQ hop 증가 → Postgres read/write/archive/WAL 증가

**큐를 합치면:**
- hop 비용 감소
- 처리량 나아질 수 있음
- 하지만 God MQ가 됨
- 병목 진단 어려움
- retry 정책이 섞임

**핵심 딜레마:** PGMQ 위에서는 SRP와 처리량이 서로 충돌한다.

## 4. Supabase Pooler 마이그레이션과 커넥션 풀 병목

Vultr 직접 PostgreSQL → Supabase Pooler 전환 시 새로운 문제 발생.

실험 결과:

| 설정 | 결과 |
|------|------|
| pool=50 | 더 느림 |
| pool=15 | 더 좋음 |

**교훈:** 커넥션 수를 늘린다고 빨라지는 게 아니다. DB/Pooler가 감당 가능한 동시성보다 많으면 오히려 느려진다.

### ResultProjection 병목 상세

```
loadCalculationResults: 1,000~7,500ms
batchUpsertViews:       600~2,400ms
archiveMessages:          90~400ms
```

DB 쿼리 비용 + Supabase Pooler hop + 네트워크 RTT + projection write + PGMQ archive가 모두 섞여 있었음.

## 5. 최적화와 롤백 경험

### 성공한 것들

**Compute Key Dedup**
- 같은 캐릭터/프리셋 계산이 중복으로 들어올 때 제거
- Before: 1000 messages → 1000 calculations
- After: 1000 messages → 200 calculations (dedup 후)

**Projection 최적화**
- 단건 markPublished → batch markAllPublished
- 16~21 views/sec → 46~52 views/sec

**BYTEA decompress / JSON parse 제거**
- write 시점에 필요한 field 미리 추출
- read/projection에서 가볍게 조회

### 롤백한 것들

**Kafka 시도 → 롤백**
- 당시 30 TPS 수준에서는 PGMQ가 병목이 아니었음
- Kafka는 인프라 복잡도만 늘림
- 단, stage scale-out / Object Storage artifact pipeline으로 가면 재검토 가능

**Outbox 폴링 → 롤백**
- PGMQ가 Postgres 내장이라 same-TX publish 가능
- Outbox polling은 오히려 latency와 추가 DB write가 비용

**DB 왕복 병합 → 롤백**
- TX가 길어지고 커넥션 점유 시간 증가
- Supabase Pooler latency가 더 큰 병목

**공통 교훈:**
- 정석 패턴도 현재 인프라 맥락에 맞아야 한다
- 병목이 아닌 곳을 최적화하면 역효과가 난다

## 6. 단일 노드 30 TPS의 의미 재해석

30 TPS가 낮아 보이지만 실제 워크로드는:

```
30 TPS = 하루 약 260만 건 = 하루 0.5~0.8TB raw external API ingress
```

단순 이벤트 30 TPS가 아니라, 건당 200~300KB live external API payload를 받아서 파싱/계산/저장/projection하는 30 TPS.

30 TPS가 낮은 이유도 여러 자원이 섞인 결과:
- 외부 API latency
- 한국-일본/클라우드 네트워크 RTT
- 서버 NIC 대역폭 (600 Mbit/s ≈ 75MB/s)
- Supabase Pooler RTT
- PGMQ/Postgres queue hop
- DB write/projection
- JSON parse/gzip/hash

예: `300KB × 200/sec = 60MB/sec` — 이미 단일 600Mbit/s 포트의 큰 비중

## 7. 스케일아웃의 의미

스케일아웃은 단순히 "CPU 더 필요해서"가 아님.

**Stage별 병목 자원이 다름:**

| Stage | 병목 자원 |
|-------|----------|
| External API | network I/O, latency, rate-limit |
| Calculation | CPU, JSON parsing |
| DB Sync | batch upsert, connection pool |
| ReadPath | latency-sensitive |

한 노드에 몰아넣으면 서로 자원을 뺏어먹음.

## 8. Kafka의 의미 재정의

Kafka는 "PGMQ보다 빠른 MQ"가 아니라:

```
queue/backlog/transport 역할을 Postgres 밖으로 빼는 도구
```

### Kafka 가치
- Postgres를 MQ 역할에서 해방
- stage별 consumer group scale-out
- lag 기반 병목 관측
- External API / Calculation / WritePath 분리
- ReadPath와 ingest workload 격리

### Kafka가 가져오는 새 복잡도
- transactional outbox
- consumer idempotency
- poison message DLT
- rebalance 중복 처리
- lag monitoring
- schema/version 관리

**결론:** Kafka는 복잡도를 줄이는 도구가 아니라, 복잡도를 통제 가능한 위치로 옮기는 도구다.

## 9. Object Storage / S3 Artifact 설계

큰 payload를 Kafka/DB에 직접 싣지 않고 참조만 전달:

```
Kafka payload:
  jobId, artifactUri, artifactHash, schemaVersion, traceId

S3/Object Storage:
  raw-response/{jobId}.json.gz
  calculation-input/{jobId}.json.gz
  calculation-result/{jobId}.json.gz

DB:
  job state, artifact manifest, artifact uri/hash/size/schemaVersion
  result summary, read model, idempotency key
```

이건 Claim-check pattern / Artifact-based pipeline이라 부름.

## 10. Streaming Ingest

외부 API 응답이 200~300KB, 수백 TPS면 통째로 들고 다니기 부담.

초기 구현 (안전):
```
HTTP stream → raw 저장 → raw 다시 읽어서 input 생성
```

고도화:
```
HTTP stream → Tee
  ├─ raw artifact 저장
  └─ streaming parser로 input 추출
```

핵심: ingest 시점에 필요한 부분만 calculation-input artifact로 만들어둔다.

## 11. Observability: Scale-out하면 tail -f는 끝남

여러 노드에 로그가 흩어지면 centralized logging 필수.

```
Metrics  = 어디가 이상한지 발견
Trace    = 요청 하나가 어디서 오래 걸렸는지 확인
Logs     = 왜 실패했는지 원인 확인
```

성공 로그는 샘플링, 실패/retry/DLT는 전량 기록.

## 12. V6: External API 분리

### Before
```
Main App / Worker
  - 요청 접수
  - External API 호출
  - 계산
  - DB write
  - projection
  - read path
```

### After
```
External API Ingestor
  - OCID lookup (400/sec)
  - CHARACTER_BASIC fetch (200/sec)
  - ITEM_EQUIPMENT fetch (continuous)
  - gzip JSONL chunk artifact 저장
  - manifest / _SUCCESS 생성
  - Kafka chunk-ready event 발행

Main App / Calculator / WritePath
  - artifact 기반 계산
  - result persist
  - projection
  - read path
```

### V6 수치

| Stage | Rate | 하루 처리량 | 하루 payload |
|-------|------|-----------|-------------|
| OCID lookup | 400/sec | 약 3,456만 건 | - |
| CHARACTER_BASIC | 200/sec | 약 1,728만 건 | 최대 5.2TB |
| ITEM_EQUIPMENT | ~160/sec | 약 1,382만 건 | TBD |

### Chunk Storage 구조
```
data/external-api/runs/{runId}/{endpoint}/
  chunks/
    part-000001.jsonl.gz  (CHARACTER_BASIC: 2000 records, ITEM_EQUIPMENT: 500 records)
    part-000002.jsonl.gz
  failed.jsonl
  manifest.json
  _SUCCESS
```

### Kafka Event (Claim-check Pattern)
```json
{
  "eventType": "SNAPSHOT_CHUNK_READY",
  "runId": "20260504-030000",
  "endpoint": "item-equipment",
  "chunkId": "part-000001",
  "objectKey": "runs/20260504-030000/item-equipment/chunks/part-000001.jsonl.gz",
  "recordCount": 500,
  "compressedBytes": 18422311
}
```

## 13. 포트폴리오 서사

### 핵심 문장

```
30 TPS, 약 7.5MB/s 수준에서 병목을 분석했는데,
튜닝을 해도 병목이 사라지는 게 아니라 다른 구간으로 이동했습니다.

그래서 단순 튜닝이 아니라,
수집 / 계산 / DB 반영 / ReadPath를 stage로 분리하고,
큰 payload는 chunk artifact로 저장하며,
Kafka에는 metadata event만 흘리는 구조로 재설계했습니다.
```

### 병목 이동이 핵심

```
특정 병목 하나를 제거하면 전체 처리량이 계속 올라갈 줄 알았지만,
실제로는 PGMQ → DB write → Projection → Network → Pooler처럼
병목 지점이 이동했습니다.

그래서 특정 함수나 쿼리 하나의 문제가 아니라,
워크로드를 하나의 파이프라인에 강하게 결합한 구조 자체가 문제라고 봤습니다.
```

### MSA 서사

```
수집 stage는 network I/O와 external API rate limit이 병목이고,
계산 stage는 CPU와 JSON parsing이 병목이며,
DB sync stage는 batch upsert와 connection pool이 병목이었다.

각 stage의 병목 자원과 scale-out 방식이 달랐기 때문에
하나의 worker로 묶기보다 service/module 경계를 나누는 게 더 적합하다고 판단했다.

MSA를 목표로 한 것이 아니라,
병목 자원이 다른 stage를 독립적으로 튜닝하고 확장하기 위해
자연스럽게 service boundary가 생겼습니다.
```

### 차별점

```
병목을 튜닝하다가,
튜닝 가능한 병목과 구조적 병목을 구분했다.
```

### 이력서 bullet

```
- 건당 200~300KB 외부 API 응답을 처리하는 계산 파이프라인에서 30 TPS / 약 7.5MB/s 처리량 한계를 분석
- 커넥션 풀, PGMQ batch, Projection, JSON parse, DB write 최적화 후에도 병목이 stage 간 이동하는 구조적 한계 확인
- External API Ingest / Calculator / DB Sync / ReadPath를 분리하고, gzip JSONL chunk artifact + Kafka metadata event 기반 파이프라인으로 재설계
- per-record 저장 대신 500~2000 records 단위 chunk 저장으로 Object Storage 요청 수와 Kafka event 수를 약 1/500~1/2000 수준으로 절감
```

### 면접 요약

```
이 프로젝트에서 가장 크게 배운 건,
성능 문제를 항상 튜닝으로만 해결할 수는 없다는 점이었습니다.

처음에는 병목을 하나씩 제거하려고 했지만,
처리량이 30 TPS / 7.5MB/s 부근에서 더 이상 올라가지 않았고
병목이 계속 다른 stage로 이동했습니다.

그래서 이건 특정 코드의 문제가 아니라
외부 API I/O, 계산, DB write, projection이 강하게 결합된 구조의 문제라고 보고,
chunk artifact 기반의 stage pipeline으로 재설계했습니다.
```

## 14. 향후 방향

### Stage별 측정치 (필수)
- OCID lookup TPS
- CHARACTER_BASIC TPS
- ITEM_EQUIPMENT TPS
- artifact write TPS
- calculation TPS
- DB persist TPS
- projection TPS
- ReadPath RPS

전체 completed TPS는 이 중 가장 느린 stage가 결정함.

### PR 로드맵
```
PR-1: chunked jsonl.gz 저장 완료 ✅
PR-2: chunk-ready event publisher 추가 ✅
PR-3: Calculate chunk consumer
PR-4: Calculate result chunk writer
PR-5: PostgreSQL sync chunk consumer
```
