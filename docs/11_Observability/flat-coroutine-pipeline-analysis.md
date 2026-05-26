# Flat Coroutine Pipeline 성능 분석

## 개요

module-calculator의 `SnapshotChunkProcessor`는 3-stage Channel 기반 코루틴 파이프라인으로, 이전 2-stage 구조 대비 **2.4x 처리 속도 향상**을 달성했다.

## 아키텍처 비교

### 이전: 2-stage (IO + CPU 혼합)

```
readAndFlatten (IO + JSON 파싱 + 프리셋 분해)  →  Channel<FlatItem>  →  N workers (계산)
```

- Reader가 GZIP decompress(IO) + `objectMapper.readTree()` + `parseAllPresets()`(CPU)를 모두 수행
- 싱글 코루틴에서 IO와 CPU가 혼합되어, JSON 파싱이 느려지면 다음 라인 읽기가 블록됨
- JSON 파싱이 병렬화되지 않음 (1개 스레드만 담당)

### 현재: 3-stage (IO / CPU / CPU 분리)

```
readLines (IO만)  →  Channel<String>  →  N parsers (CPU)  →  Channel<FlatItem>  →  N workers (CPU)
```

- Stage 1: `Dispatchers.IO` — GZIP decompress + 라인 읽기만 (순수 IO)
- Stage 2: `Dispatchers.Default` × 4 — JSON 파싱 + 프리셋 분해 (순수 CPU)
- Stage 3: `Dispatchers.Default` × 4 — 장비 기대치 계산 (순수 CPU)

## 성능 향상 요인

### 1. IO와 CPU 분리

이전에는 Reader가 IO 작업 중간에 CPU-heavy JSON 파싱을 수행. 파싱이 느려지면 GZIP 스트림 읽기가 멈추고, 반대로 GZIP 읽기가 느려지면 파서가 대기.

3-stage에서는 IO 스레드는 읽기만, CPU 스레드는 파싱만 담당. 서로 다른 병목이 독립적으로 동작.

### 2. JSON 파싱 병렬화

한 유저의 장비 데이터는 40~60개 아이템을 포함. `objectMapper.readTree()` + `parseAllPresets()`는 꽤 무거운 CPU 작업인데, 이전에는 싱글 Reader에서 순차 처리했다.

현재는 4개 파서 워커가 병렬로 파싱. 35K 아이템 기준 파싱 병목이 해소됨.

### 3. 세밀한 Backpressure

Channel이 2개(`lineChannel`, `itemChannel`)라 병목 지점이 더 정확히 흡수됨:
- 파싱이 계산보다 빠르면 `itemChannel`이 차면서 파서가 자연스럽게 대기
- 읽기가 파싱보다 빠르면 `lineChannel`이 차면서 Reader가 대기
- 메모리 과다 사용 없이 생산자-소비자 속도 차이를 흡수

## 측정 결과

| 지표 | 이전 (2-stage) | 현재 (3-stage) |
|------|----------------|----------------|
| 35K 아이템 처리 시간 | ~10.5s | **4.4s** |
| 향상 비율 | — | **2.4x** |
| JSON 파싱 병렬도 | 1 (Reader에서 순차) | 4 (parser workers) |
| 계산 병렬도 | 4 | 4 |
| Channel 수 | 1 | 2 |

## 핵심 인사이트

병렬 처리 자체는 이전에도 있었지만, **IO와 CPU를 한 코루틴에 혼합**해 놓은 것이 병목이었다. 관심사의 분리(Separation of Concerns)를 파이프라인 Stage 단위로 적용한 것이 핵심 개선.

## 관련 커밋

- `90af01fbf`: flat coroutine pipeline 도입 (2-stage: readAndFlatten → workers)
- `77c150cf4`: 3-stage 분리 + Caffeine cache 추가 (readLines → parseLines → processItems)
