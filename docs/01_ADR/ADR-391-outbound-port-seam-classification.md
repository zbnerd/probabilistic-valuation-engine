# ADR-391: 아웃바운드 포트 seam 분류 (Issue #897)

- Status: Proposed
- Date: 2026-06-05
- Owner: TBD
- Related: Issue #897

---

## 1. Background / Problem

### Background

`module-core`의 아웃바운드 포트가 49개(`core/port/out/` 43 + `core/calculator/port/` 5 + `core/flame/port/` 1). 신규 개발자가 어떤 포트를 사용해야 하는지 판단하기 어렵고, `BufferStatusQuery`처럼 어댑터 0개 no-op stub이 존재.

### Problem

- Real seam(어댑터 2개+)과 hypothetical seam(어댑터 1개)이 코드상 구분 불가
- Like 관련 포트 6개, Monitoring 관련 포트 7개가 책임 중복 가능성
- Dead/Hypothetical 포트가 타입 그래프 비대화

### Goal

49개 포트 전수 분류 + Like/Monitoring 그룹 병합 시그니처 제안. **코드 변경 없음** — 본 이슈는 조사/문서화만. 실제 제거/병합 PR은 후속 이슈.

---

## 2. Decision

> 모든 아웃바운드 포트를 어댑터 개수 기준으로 4등급(Real/Active/Hypothetical/Dead) 분류하고, Like 6→2 / Monitoring 7→2 병합안을 spec에 포함한다. 산출물은 `docs/superpowers/specs/2026-06-05-897-port-audit-design.md` + 본 ADR.

```text
classify(port) = {
  Real         if prod+test adapters ≥ 2
  Active       if prod adapters = 1 AND 교체 가능성 중간
  Hypothetical if prod adapters = 1 AND 교체 가능성 낮음
  Dead         if prod adapters = 0
}
```

---

## 3. Trade-offs

### Sensitivity

* **어댑터 카운트 정확성** — 잘못 세면 잘못된 분류로 이어짐. 1개 차이로 Hypothetical↔Active 이동.
* **교체 가능성 휴리스틱** — 주관적 요소 포함. 외부 시스템 wrapping은 명확, 그 외는 판단 영역.
* **테스트 fake 정의** — unit test의 in-memory stub만 카운트, prod wiring 없는 mock은 제외.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 분류표 + 권장안만 (코드 변경 없음) | 빠른 의사결정, 리스크 0, 후속 이슈로 점진적 진행 | 즉각적인 코드 단순화 효과는 없음 |
| 본 이슈에서 실제 제거/병합까지 | 즉시 코드베이스 축소 | 단일 PR이 거대해지고, 리뷰 비용 증가, 회귀 위험 |

→ **선택: 분류표 + 권장안만.** 점진적 정리가 Hexagonal Architecture 원칙 변경보다 안전.

### Risk

* 분류가 잘못된 경우: 후속 이슈에서 "Active로 분류된 줄 알았는데 어댑터가 0이었다" 같은 발견 가능. mitigation: §8 검증 단계에서 49행 전수 TBD 해소를 강제.
* Like/Monitoring 병합안이 도메인 이해 부족으로 부적합할 수 있음. mitigation: spec §5/§6의 시그니처는 *sketch*이며 후속 이슈에서 재설계 가능.

### Non-Risk

* 인바운드 포트(16개) — 본 이슈 범위 밖, 분리됨.
* 기존 호출자 코드 — 본 이슈는 변경 없음.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| 분류 대상 포트 | 49 | core/port/out 43 + calculator/port 5 + flame/port 1 |
| Dead seam 후보 (사전 확인) | ≥ 1 | BufferStatusQuery (issue body 명시) |
| Like 그룹 병합안 | 6 → 2 | spec §5 |
| Monitoring 그룹 병합안 | 7 → 2 | spec §6 |
| 본 이슈 코드 변경 | 0 | spec + ADR만 |

### Observed Result

* 분류표(49행) 완성: spec §4
* Like/Monitoring 병합 시그니처: spec §5, §6
* 두 문서 spec 작성 + ADR 작성으로 종료

---

## 5. Summary

> 49개 아웃바운드 포트를 4등급으로 분류하고 Like 6→2 / Monitoring 7→2 병합안을 spec으로 제시하되, 본 이슈는 문서화만 수행한다.
