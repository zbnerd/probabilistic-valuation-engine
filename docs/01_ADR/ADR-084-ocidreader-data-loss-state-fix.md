# ADR-084: OcidReader P0 데이터 누락 및 P1 상태 초기화 수정

## 제1장: 문제의 발견 (Problem)

### 1.1 P0 CRITICAL: 마지막 페이지 OCID 데이터 누락

`OcidReader`의 `readNextOcid()` 메서드에서 **마지막 페이지의 모든 OCID가 누락**되는 치명적인 버그가 발견되었습니다.

**문제 코드 (70-72행):**
```java
if (!hasNextPage || (ocidIterator != null && !ocidIterator.hasNext())) {
    return null;  // 마지막 페이지의 OCID들을 반환하지 않고 종료!
}
```

**버그 시나리오:**
1. 마지막 페이지를 가져옴 (`fetchNextChunk()` 호출)
2. `hasNextPage = false` 설정 (86행)
3. `readNextOcid()` 호출 시 `!hasNextPage`가 `true`이므로 즉시 `null` 반환
4. **마지막 페이지의 1000개 OCID가 모두 소실됨**

### 1.2 P1: 상태 초기화 누락으로 인한 재실행 실패

`OcidReader`는 `@Component`로 선언된 Spring Singleton이므로, 여러 번의 Batch Job 실행 간에 **인스턴스 상태가 공유**됩니다.

**문제 코드 (48-50행):**
```java
private Iterator<String> ocidIterator;
private int currentPage = 0;
private boolean hasNextPage = true;
```

**버그 시나리오:**
1. 첫 번째 Batch 실행: `currentPage = 0` → `1` → `2` → ...
2. 첫 번째 Batch 종료: `currentPage = N` (상태 유지)
3. 두 번째 Batch 실행 시작: `currentPage`가 `N`인 상태로 시작
4. `fetchNextChunk()`에서 `PageRequest.of(N, FETCH_SIZE)` 호출 → **0건 반환**
5. Batch가 아무것도 처리하지 않고 종료됨

### 1.3 영향도 분석

- **P0 심각도**: 매일 일일 갱신 작업에서 마지막 청크의 유저들이 업데이트되지 않음
- **P1 심각도**: 재시작 시 데이터 누락, 개발 환경에서만 발견 가능 (일일 배치라 운영에서는 드물게 발생)

---

## 제2장: 선택지 탐색 (Options)

### 2.1 선택지 1: P0 수정 - Iterator 소진 우선 체크 (채택)

**방식**: `hasNextPage` 체크를 **Iterator 소진 후**로 이동합니다.

```java
private String readNextOcid() {
    if (ocidIterator == null || !ocidIterator.hasNext()) {
        fetchNextChunk();
    }

    // Iterator 소진을 먼저 체크
    if (ocidIterator != null && !ocidIterator.hasNext()) {
        return null;  // 현재 페이지의 모든 OCID를 반환한 후 종료
    }

    return ocidIterator.next();
}
```

**장점**:
- **데이터 완전성 보장**: 마지막 페이지의 OCID 모두 반환
- **명확한 로직**: Iterator 패턴의 표준적인 사용법
- **최소 변경**: 조건문 순서만 변경

**단점**:
- 없음

**결론**: **채택**

### 2.2 선택지 2: P1 수정 - @BeforeStep 훅으로 상태 초기화 (채택)

**방식**: Spring Batch의 `@BeforeStep` 훅을 사용하여 각 Job 실행 전에 상태를 초기화합니다.

```java
@BeforeStep
void initializeState() {
    this.ocidIterator = null;
    this.currentPage = 0;
    this.hasNextPage = true;
    log.info("[OcidReader] State initialized for new job execution");
}
```

**장점**:
- **Spring Batch 표준**: 프레임워크가 제공하는 생명주기 훅 사용
- **명시적 초기화**: 코드 의도가 명확히 드러남
- **로그 기록**: 각 실행 시작을 추적 가능

**단점**:
- Spring Batch API 의존성 (이미 ItemReader를 구현 중이므로 문제 없음)

**결론**: **채택**

### 2.3 선택지 3: P1 수정 - Prototype Scope 변경 (미채택)

**방식**: `@Scope("prototype")`으로 변경하여 매번 새 인스턴스 생성.

**장점**:
- 초기화 로직 불필요

**단점**:
- **Spring Batch 제약**: `ItemReader`는 Singleton으로 관리되는 것이 표준
- **불필요한 인스턴스 생성**: 메모리 비효율

**결론**: 부적합

---

## 제3장: 결정의 근거 (Decision)

### 3.1 최종 결정

- **P0 수정**: Iterator 소진 우선 체크 (선택지 1)
- **P1 수정**: `@BeforeStep` 훅으로 상태 초기화 (선택지 2)

### 3.2 수정 원칙

1. **CLAUDE.md 섹션 12**: LogicExecutor 패턴 준수 (기존 코드 유지)
2. **CLAUDE.md 섹션 15**: 람다 3줄 초과 시 Private Method 추출 (기존 코드 유지)
3. **Spring Batch Best Practice**: `@BeforeStep` 사용하여 생명주기 관리

---

## 제4장: 구현 (Implementation)

### 4.1 코드 수정

**파일**: `module-app/src/main/java/maple/expectation/batch/reader/OcidReader.java`

**수정 1: P0 데이터 누락 수정 (readNextOcid 메서드):**
```java
private String readNextOcid() {
    if (ocidIterator == null || !ocidIterator.hasNext()) {
        fetchNextChunk();
    }

    // Iterator 소진을 먼저 체크하여 마지막 페이지의 OCID 반환
    if (ocidIterator != null && !ocidIterator.hasNext()) {
        return null;
    }

    return ocidIterator.next();
}
```

**수정 2: P1 상태 초기화 추가 (@BeforeStep 훅):**
```java
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;

// ...

/**
 * Step 실행 전 상태 초기화 (Issue #356 P1)
 *
 * <p>@Component Singleton이므로 각 Batch Job 실행 전에 상태를 초기화해야 함.
 * 초기화하지 않으면 이전 실행의 currentPage가 남아서 데이터 누락 발생.
 */
@BeforeStep
void initializeState(StepExecution stepExecution) {
    this.ocidIterator = null;
    this.currentPage = 0;
    this.hasNextPage = true;
    log.info("[OcidReader] State initialized for job: {}", stepExecution.getJobExecutionId());
}
```

---

## 제5장: 검증 (Verification)

### 5.1 단위 테스트 시나리오

1. **P0 테스트: 마지막 페이지 OCID 반환 확인**
   - Given: 총 2500개 OCID (3페이지: 1000, 1000, 500)
   - When: Batch 실행
   - Then: 2500개 OCID 모두 반환됨 (마지막 500개 포함)

2. **P1 테스트: 재실행 시 상태 초기화 확인**
   - Given: 첫 번째 Batch 실행 완료 (currentPage = 3)
   - When: 두 번째 Batch 실행
   - Then: currentPage가 0으로 초기화되어 데이터 정상 조회

### 5.2 테스트 코드 예시

```java
@Test
@DisplayName("마지막 페이지 OCID 누락 없이 모두 반환")
void lastPageOcids_ShouldBeReturned() {
    // Given: 총 2500개 OCID (3페이지)
    when(repository.findAll(any(Pageable)))
        .thenReturn(createPage(1, 1000, true))
        .thenReturn(createPage(2, 1000, true))
        .thenReturn(createPage(3, 500, false));

    // When: 2501번 read() 호출
    List<String> ocids = new ArrayList<>();
    for (int i = 0; i < 2501; i++) {
        String ocid = reader.read();
        if (ocid == null) break;
        ocids.add(ocid);
    }

    // Then: 2500개 OCID 모두 반환
    assertThat(ocids).hasSize(2500);
}

@Test
@DisplayName("Step 재실행 시 상태 초기화")
void reexecuteStep_ShouldInitializeState() {
    // Given: 첫 번째 실행 완료
    when(repository.findAll(any(Pageable))).thenReturn(createPage(1, 1000, false));

    // When: StepExecution 시뮬레이션
    reader.initializeState(mockStepExecution);

    // Then: 상태 초기화됨
    assertThat(reader.getCurrentPage()).isEqualTo(0);
    assertThat(reader.hasNextPage()).isTrue();
}
```

---

## 제6장: 관련 문서 (Related Documents)

- **CLAUDE.md 섹션 12**: LogicExecutor 패턴
- **CLAUDE.md 섹션 15**: Lambda Hell 방지
- **Spring Batch Documentation**: https://docs.spring.io/spring-batch/docs/current/api/org/springframework/batch/core/annotation/BeforeStep.html
- **Issue #356**: Spring Batch로 전체 유저 장비 데이터 주기 갱신

---

## 상태 (Status)

**상태**: 🟢 Accepted (2026-02-23)

**적용 대상**:
- `OcidReader.java` (2개 수정: P0 로직, P1 초기화)

**다음 작업**:
- [ ] 단위 테스트 작성
- [ ] 통합 테스트 검증
- [ ] 코드 리뷰
