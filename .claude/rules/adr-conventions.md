# ADR (Architecture Decision Record) 컨벤션

## ADR 작성 규칙

ADR은 길게 쓰지 않는다. 아래 구조만 사용한다.

### 필수 섹션

1. **Background / Problem** — 결정 배경, 해결하려는 문제, 목표
2. **Decision** — 최종 선택한 결정 (짧게)
3. **Trade-offs**
   - **Sensitivity**: 설계가 민감하게 반응하는 요소 (데이터 크기, 동시성, DB compute, 외부 API rate limit, Kafka backlog, JVM heap, connection pool 등)
   - **Trade-off**: 의도적으로 선택한 교환 관계 (선택 / 얻는 것 / 포기한 것)
   - **Risk**: 남아 있는 위험
   - **Non-Risk**: 이 결정으로 제거했거나 중요도가 낮아진 위험
4. **Result / Evidence** — 메트릭 테이블 + 관측 결과 (숫자 기반)
5. **Summary** — 핵심 한 문장

### 금지 패턴

- 구현 상세 (코드 스니펫, 클래스 다이어그램 등) — 코드에 있어야 할 내용
- 긴 대안 비교 (3개 이상 대안 나열) — 선택한 것과 이유만
- 장황한 설명 — 왜 이 결정을 했는지와 어떤 리스크를 받아들였는지만

### 템플릿

````md
# ADR-XXX: {결정 제목}

- Status: {Proposed | Accepted | Deprecated | Superseded}
- Date: {YYYY-MM-DD}
- Owner: {작성자/팀}

---

## 1. Background / Problem

### Background

- {배경}

### Problem

- {문제}

### Goal

- {목표}

---

## 2. Decision

> {우리는 무엇을 선택했다.}

```text
{선택한 구조 / 흐름 / 처리 방식}
```

---

## 3. Trade-offs

### Sensitivity

* {민감 요소}
* {민감 요소}

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
|    |      |       |

### Risk

* {남아 있는 위험}

### Non-Risk

* {제거된 위험}

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
|        |       |       |

### Observed Result

* {관측 결과}

---

## 5. Summary

> {핵심 한 문장}
````

## ADR 위치

`docs/01_ADR/` 디렉토리에 `ADR-{번호}_{kebab-case-제목}.md` 형식으로 저장.
