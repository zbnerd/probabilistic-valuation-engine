# Chunk Replay Architecture: Claim-Check + Stage Isolation

## 개요

external-api 수집 결과를 gzip JSONL chunk artifact로 보존하고, Kafka event는 chunk의 metadata(objectKey)만 전달하는 구조. 이를 통해 수집 단계와 계산 단계를 독립적으로 분리하여, 외부 API 재호출 없이 특정 stage만 replay 가능.

## 아키텍처

```
External-API ──publish──▶ Kafka ──consume──▶ Calculator ──publish──▶ Kafka ──consume──▶ DB-Sync
     │                  (chunk-ready)           │              (result-ready)           │
     │                                          │                                    │
     ▼                                          ▼                                    ▼
 Storage                                    Storage                              MongoDB
 snapshot chunk                             result chunk                         PostgreSQL
 (gzip JSONL)                               (gzip JSONL)                         read model
```

## 핵심 설계: Kafka는 payload 운반용이 아님

Kafka message는 실제 데이터가 아닌, **데이터가 있는 위치를 알려주는 작업 지시서**.

```json
{
  "eventType": "SNAPSHOT_CHUNK_READY",
  "runId": "20260505-030000",
  "endpoint": "item-equipment",
  "chunkId": "part-000123",
  "objectKey": "runs/20260505-030000/item-equipment/chunks/part-000123.jsonl.gz",
  "recordCount": 500
}
```

- Kafka message 1개 = 500 users / ~35,000 item rows를 가진 chunk 작업 1개
- 실제 payload는 Storage의 gzip JSONL 파일
- 이 패턴은 **claim-check pattern**으로, 실무 데이터 파이프라인에서 널리 사용됨

## 실전 검증: Calculator 독립 Replay

### 시나리오

calculator의 `createFullCalculator()` 수정 후, external-api 재실행 없이 calculator만 replay 테스트.

### 절차

1. 기존 item-equipment snapshot chunk 파일은 이미 Storage에 보존됨
2. Kafka topic에 기존 objectKey를 담은 chunk-ready event 재발행
3. calculator가 Storage에서 chunk 읽어 재계산
4. 결과를 새 result JSONL.gz chunk로 저장 후 result-ready event 발행

### 결과

| 항목 | 값 |
|------|-----|
| 대상 유저 | 288,422명 |
| 처리 아이템 | 20,257,932개 |
| 에러 | 0 |
| External API 재호출 | 없음 |
| 처리 속도 | 167 users/s (calculator 단독) |

**핵심**: 외부 API(Nexon)를 다시 호출하지 않고, 보존된 snapshot chunk만으로 calculator stage를 독립 검증.

## Stage 분리가 주는 이점

### 1. 독립 테스트

```
외부 API 없이 calculator만 재실행      ← 이미 검증 완료
calculator 없이 db-sync만 재실행       ← result chunk 보존되면 가능
DB schema 변경 후 result chunk로 read model 재구성 ← 동일 패턴 적용 가능
```

### 2. 장애 복구

```
consumer가 죽어도:
  offset commit 전이면 다시 consume
  chunk 파일은 Storage에 남아 있으므로 언제든 재처리 가능
```

### 3. Scale-out

```
calculator worker를 늘리면 같은 topic의 partition을 나눠 처리:
  calculator-01 → partition 0
  calculator-02 → partition 1
```

chunk 1개가 35,000 row짜리 작업이므로, 메시지 수가 577개여도 scale-out 의미 있음.

### 4. Backfill / 재계산

계산식 변경 시 기존 snapshot chunk에서 전체 재계산 가능:

```
snapshot.chunk-replay-requested event 발행
→ calculator가 기존 objectKey의 chunk 읽어 재계산
→ 새 result chunk 생성
→ db-sync 재반영
```

## Kafka를 선택한 이유

메시지 수가 적어 보여도(577개) Kafka를 사용한 이유:

1. **Stage 분리**: 각 모듈이 서로 직접 호출하지 않음. calculator가 느려도 external-api가 막히지 않음
2. **재처리**: Storage에 artifact가 남아 있으면 event 재발행만으로 재처리 가능
3. **장애 복구**: consumer offset 기반으로 장애 시 중복/누락 없이 복구
4. **관측성**: chunk lifecycle event로 stage별 latency, 실패율, 처리량 추적
5. **확장성**: partition 기반 consumer group으로 worker 수평 확장

## Chunk 매핑 보장

```
source chunk 1개 = user 500명
source JSONL line 1개 = user 1명
result chunk 1개 = source chunk 1개와 1:1
result chunk rotate 없음
```

- 어떤 ocid가 `part-000001`에 있으면, 그 ocid의 장비 계산 결과는 전부 `result-part-000001.jsonl.gz` 안에 있음
- chunk 간 ocid 분산 없음, chunk 내부 row 순서만 섞일 수 있음
- DB sync 시 result chunk 단위로 ocid group by하면 user boundary complete 보장

## E2E 테스트 지표

### JVM / GC (8-core, -Xmx1g)

| Module | Young GC | Full GC | Total GCT | Old Gen |
|--------|----------|---------|-----------|---------|
| Calculator | 4,513회 / 618s | 0 | 618s | 2% |
| External-API | 2,241회 / 8s | 0 | 8s | 4% |

Full GC 0회, STW 없이 안정 동작. 단일 인스턴스에서 두 JVM 동시 실행 시 CPU 경합 발생 (load average 16.61 on 8 cores). 분리 인스턴스 운영으로 해결 가능.

### GC 최적화

Calculator의 `CalculationResultWriter`에서 `objectMapper.writeValueAsString()` 대신 Jackson `JsonGenerator` streaming write 적용. 2,100만 개 result row 처리 시 중간 String/byte[] allocation 제거.
