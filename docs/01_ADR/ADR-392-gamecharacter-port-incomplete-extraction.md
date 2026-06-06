# ADR-392: GameCharacterPort 미완성 추출 (Issue #1153)

- Status: Proposed
- Date: 2026-06-05
- Owner: TBD
- Related: #1153 (follow-up of #897)

---

## 1. Background / Problem

### Background

Issue #897 감사에서 `GameCharacterPort` (49개 중 하나) 가 Dead seam (prod 어댑터 0개) 으로 분류됨. 그러나 호출자 3곳 존재:
- `module-infra/.../worker/AbstractExpectationCalcWorker.kt:38` (field declaration)
- `module-infra/.../worker/ExpectationCalcWorker.kt:36, 54` (param forwarding only)
- `module-infra/.../worker/ExpectationCalcLowWorker.kt:36, 54` (param forwarding only)

호출자 존재 + 구현체 부재 = Spring DI 실패 위험.

### Problem

`./gradlew :module-calculator:compileKotlin` → BUILD SUCCESSFUL. 즉 컴파일 단계는 통과. 그러나 active module 중 어느 것도 `AbstractExpectationCalcWorker` 빈을 생성하지 않으므로 런타임에 NPE/누락 빈이 발생하지 않음. **latent dead code** 상태:

1. 누군가 worker를 wiring하면 즉시 `NoSuchBeanDefinitionException: GameCharacterPort`로 시작 실패
2. `calculateOnly` 경로의 `gameCharacterPort.getCharacterOrThrow` (line 87)는 PgmqWorker에서 호출 가능 (line 303/379) — 활성화만 되면 즉시 호출됨
3. port의 7개 메서드 중 4개만 `GameCharacterRepository` (도메인 인터페이스)와 시그니처 일치, 3개(`isNonExistent`, `enrichCharacterBasicInfo`, `getCharacterForUpdate`)는 도메인 repo에 없음

### Git history

```
3d0911f62 refactor: ADR-005 Web Controller Migration - Port 추출 1단계 (#464)
```

→ Issue #464의 port 추출 리팩토링이 인터페이스만 `core/port/out/`으로 이동, 어댑터 작성 없이 종료. **미완성 추출**.

### Goal

`GameCharacterPort` 의 미완성 추출을 해소한다. 옵션:
- (A) **어댑터 추가** — `GameCharacterPortAdapter` 가 `GameCharacterRepository`에 위임. Real/Active seam 전환.
- (B) **호출자 + port 동시 제거** — worker 3개가 어디서도 사용되지 않으므로 통째로 제거.
- (C) **port만 제거** — 호출자는 보존하되 port type을 `GameCharacterRepository`로 교체.

---

## 2. Decision

> **옵션 (A) 채택: 어댑터 추가.** `GameCharacterPortAdapter` 를 `module-infra/.../adapter/outgoing/GameCharacterPortAdapter.kt` 에 추가하고, `GameCharacterRepository` 에 위임. 누락 메서드 3개는 TODO + `UnsupportedOperationException` 으로 시작 (호출 경로가 inactive).

```text
GameCharacterPort (core/port/out)
        ↑ implements
GameCharacterPortAdapter (module-infra/adapter/outgoing)
        ↓ delegates
GameCharacterRepository (core/domain/repository) ← GameCharacterRepositoryImpl
```

---

## 3. Trade-offs

### Sensitivity

* **Active 모듈에서 worker wiring 여부** — 4개 active 모듈 (calculator/external-api/synchronizer/rest-controller) 어느 것도 AbstractExpectationCalcWorker를 사용하지 않음. wiring 추가되는 즉시 어댑터 필요.
* **누락 메서드 3개의 사용처** — `isNonExistent`, `enrichCharacterBasicInfo`, `getCharacterForUpdate` 가 호출되는 경로 부재. 호출처 발견 시 어댑터에 구현 필요.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| (A) 어댑터 추가 | latent dead code 해소, port 정합성 회복, future wiring safe | 누락 메서드 3개는 `UnsupportedOperationException` — 호출 시 명시적 에러 |
| (B) port + worker 통째 제거 | dead code 영구 제거, 코드 단순화 | 향후 worker 재활성화 시 port + adapter 동시 재작성 필요 |
| (C) port만 제거, caller는 `GameCharacterRepository`로 교체 | port 그래프 축소 | worker의 port 의존성 (잠재적 port-first 설계 의도) 손실 |

→ **선택 (A)**: 가장 안전. 추출 의도(ADR-005)를 존중하면서 즉시 컴파일/런타임 정합성 회복.

### Risk

* **잘못된 위임** — `GameCharacterRepository` 메서드명이 port와 다름 (예: `findByUserIgn` vs `getCharacterIfExist`). 어댑터에서 변환 시 누락 가능. mitigation: 어댑터에 단위 테스트 4개 추가 (메서드별 1:1 위임 확인).
* **TX 경계 변경** — `GameCharacterRepository` 는 `@Transactional(readOnly=true)`. port의 mutation 메서드 (`createNewCharacter`, `saveCharacter`) 호출 시 write TX 필요. mitigation: 어댑터에 `@Transactional` 재선언 (write 경로).

### Non-Risk

* `module-calculator`는 worker를 사용하지 않으므로 현재 runtime 영향 없음.
* 기존 `GameCharacterRepositoryImpl` 변경 없음.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| `GameCharacterPort` 메서드 | 7 | isNonExistent / getCharacterIfExist / createNewCharacter / saveCharacter / getCharacterOrThrow / enrichCharacterBasicInfo / getCharacterForUpdate |
| `GameCharacterRepository` 메서드 | ~7 | findByOcid, findByUserIgn, save, findByUserIgnIn 등 |
| 시그니처 일치 | 4 | 4개 메서드는 직접 위임 가능 |
| 시그니처 불일치 (TODO) | 3 | isNonExistent, enrichCharacterBasicInfo, getCharacterForUpdate |
| Latent bean failures | 1 | Spring DI 시 NoSuchBeanDefinitionException 위험 |

### Observed Result

* 미완성 추출 확인: 인터페이스만 존재, 어댑터 없음 (issue #464, commit 3d0911f62)
* 호출자 3개 모두 dead code (어디서도 wiring 안 됨)
* port 7개 메서드 중 3개는 도메인 repo에 동등 메서드 없음

---

## 5. Summary

> `GameCharacterPort` 의 미완성 추출 (Issue #464, 3d0911f62) 을 해소하기 위해 `GameCharacterPortAdapter` 를 추가하고 `GameCharacterRepository` 에 위임한다. 누락 메서드 3개는 `UnsupportedOperationException` 으로 stub 처리하여 호출처 발견 시 명시적 실패를 보장한다.
