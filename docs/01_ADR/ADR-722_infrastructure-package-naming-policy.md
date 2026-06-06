# ADR-722: Infrastructure Package Naming — Legacy 누적 방지 정책

- Status: Accepted
- Date: 2026-06-05
- Owner: arch
- Related: #896, #1157

---

## 1. Background / Problem

### Background

ADR-050에서 `module-infra` 분해 로드맵 수립. #896에서 `domain/v2/` 7개 엔티티를 `infrastructure/persistence/entity/`로 이관 완료. #1157에서 `concurrency/` 패키지에 6개 어댑터 도입.

### Problem

- `domain/v2/` 같은 버전 접미사 패키지가 재발 가능 — `v3/`, `v4/` 등장 시 동일 이관 작업 반복
- flat `entity/` 패키지에 모든 JPA 엔티티가 모이면 도메인 경계 불명확
- 신규 인프라 코드가 패키지 컨벤션 없이 임의 위치에 생성됨

### Goal

`module-infra` 내 패키지 명명 규칙을 정의하여 레거시 누적을 구조적으로 방지.

---

## 2. Decision

> `module-infra/infrastructure/` 하위 패키지는 **도메인 단위 + 기술 역할** 2-level 구조만 사용. 버전 접미사, flat 패키지, `domain/` 내 JPA 엔티티 금지.

```text
infrastructure/
├── persistence/
│   ├── entity/character/      (GameCharacterJpaEntity 등)
│   ├── entity/equipment/      (CharacterEquipmentJpaEntity 등)
│   ├── entity/calculation/    (CalculationJobJpaEntity 등)
│   └── repository/            (JPA repository 인터페이스)
├── concurrency/               (6 adapters from #1157)
├── cache/                     (TieredCache, SingleFlight)
├── config/                    (Spring @Configuration beans)
└── ...                        (기타 도메인별 패키지)
```

---

## 3. Trade-offs

### Sensitivity

* `module-infra` 내 패키지 수 (~30개)
* 신규 개발자가 패키지를 찾는 속도
* 마이그레이션 PR 크기 (기존 flat 패키지 → 도메인별 이동)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 도메인 단위 하위 패키지 | 관련 코드 응집, 변경 범위 국지화 | 패키지 depth +1 |
| 버전 접미사 금지 | v2/v3 재발 방지 | 일시적 병행 기간 필요 (이관 PR) |
| JPA 엔티티 `infrastructure/` 내 강제 | domain 패키지 순수성 유지 | infra 패키지 크기 증가 |

### Risk

* 기존 flat `entity/` 패키지에서 도메인별 이동 시 import 변경量大 — 점진적 이관 필요
* `config/` 패키지는 104파일로 이미 과대 — ADR-050 분해 시 함께 분할 필요

### Non-Risk

* `module-core` 순수성 — 이 정책은 `module-infra` 내부만 해당
* 빌드/런타임 영향 — 패키지 이동은 compile-only 변경

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| `domain/v2/` 잔여 파일 | 0 | #896 이관 완료 |
| `infrastructure/persistence/entity/` 파일 | 7 | #896 결과 |
| `concurrency/` 파일 | 10 | #1157 결과 |

### Observed Result

* #896 이관 후 `domain/` 패키지에 JPA 엔티티 0건
* #1157 도입 후 `concurrency/` 패키지에 동시성 코드 집중

---

## 5. Summary

> **`infrastructure/{domain}/{role}/` 구조만 허용, 버전 접미사·flat 패키지·domain 내 JPA 금지.**
