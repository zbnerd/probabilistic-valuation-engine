# ADR-014 분석 및 현재 구조 개선 제안

## 1. ADR-014 제안 모듈 구조 요약

### 권장 4-모듈 구조
```
maple-expectation/
├── maple-common/     ← [경량] POJO, DTO, 함수형 인터페이스 (의존성 없음)
├── maple-core/       ← [중량] Spring Infrastructure, AutoConfiguration
├── maple-domain/     ← [도메인] JPA Entity, Repository
└── maple-app/        ← [애플리케이션] Controller, Service
```

### 의존성 흐름 (단방향)
```
maple-app
  ├── maple-core
  │     └── maple-common
  └── maple-domain
        └── maple-common
```

**핵심 원칙:**
- **maple-common**: 어떤 모듈에도 의존하지 않음 (Leaf 모듈)
- **maple-core** ↔ **maple-domain**: 서로 의존하지 않음 (병렬 빌드 가능)
- **DIP 준수**: 상위 → 하위 방향으로만 의존

---

## 2. 현재 구조와의 차이 비교

### 현재 4-모듈 구조 (ADR-017)
```
maple-expectation/
├── module-common/   ← POJO, DTO, Exception (Spring 의존성 O)
├── module-core/     ← 순수 도메인 모델 (Entity, VO)
├── module-infra/    ← Spring Infrastructure, JPA, Redis, 외부 API
└── module-app/      ← Spring Boot Application, Controller, Service
```

### 현재 의존성 흐름
```
module-app
  ├── module-infra
  │     ├── module-core
  │     │     └── module-common
  │     └── module-common
  ├── module-core
  └── module-common
```

### 주요 차이점 분석

| 항목 | ADR-014 (권장) | 현재 구조 (ADR-017) | 문제 여부 |
|------|----------------|-------------------|----------|
| **모듈 명명** | `maple-*` prefix | `module-*` prefix | ❌ 문제 없으나 일관성 필요 |
| **공통 모듈 의존성** | Spring 의존 없음 (POJO만) | `spring-boot-starter-web` 의존 | ⚠️ 순수 POJO 위배 |
| **인프라 모듈** | `maple-core`에 포함 | `module-infra`로 분리 | ✅ 더 명확한 분리 |
| **도메인 모듈** | `maple-domain` (JPA Entity) | `module-core` (순수 도메인) | ⚠️ 명명 혼동 |
| **순환 의존** | 없음 (단방향) | 없음 (단방향) | ✅ 양호 |
| **병렬 빌드** | core ↔ domain 병렬 가능 | core ← infra (순차적) | ⚠️ 최적화 미흡 |

---

## 3. ADR-014 권장사항 중 놓친 것

### 3.1 [P0] **module-common의 Spring 의존성 제거**

**현재 문제:**
```groovy
// module-common/build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'  // ❌ 위배
}
```

**ADR-014 원칙:**
> maple-common은 **POJO, DTO, 함수형 인터페이스**만 포함하며, 어떤 모듈에도 의존하지 않는 Leaf 모듈이어야 한다.

**놓친 이유:**
- `HttpStatus` 사용을 위해 `spring-boot-starter-web`을 추가
- `CircuitBreakerIgnoreMarker`, `CircuitBreakerRecordMarker` 인터페이스가 common에 있음
- 하지만 이 인터페이스는 Spring의 Marker Interface 패턴이나, Spring 의존성은 불필요

**해결 방안:**
```groovy
// module-common/build.gradle
dependencies {
    // Spring 제거, HttpStatus는 직접 정의하거나 Jakarta EE만 사용
    compileOnly 'jakarta.servlet:jakarta.servlet-api:6.0.0'  // ✅ 컴파일 전용
}
```

### 3.2 [P1] **Marker Interface 위치 재검토**

**현재 문제:**
```java
// module-common에 있지만 Resilience4j와 관련된 인터페이스
package maple.expectation.global.error.exception.marker;

public interface CircuitBreakerIgnoreMarker {}  // Resilience4j 전용
public interface CircuitBreakerRecordMarker {}  // Resilience4j 전용
```

**ADR-014 원칙:**
> 횡단 관심사 인터페이스는 사용처(모듈)에 가까운 곳에 배치한다.

**해결 방안 (2가지 옵션):**

**Option A (권장): marker 패키지를 module-infra로 이동**
```
module-infra/
└── infrastructure/
    └── resilience/
        └── marker/
            ├── CircuitBreakerIgnoreMarker.java
            └── CircuitBreakerRecordMarker.java
```

**Option B: maple-common에 유지하되, Spring 의존성만 제거**
```java
// module-common (Marker Interface는 순수 인터페이스)
package maple.expectation.common.resilience.marker;

public interface CircuitBreakerIgnoreMarker {}
public interface CircuitBreakerRecordMarker {}
```

### 3.3 [P1] **AutoConfiguration 누락**

**ADR-014 권장:**
```java
// maple-core/AutoConfiguration
@AutoConfiguration
@Import({
    ExecutorAutoConfiguration.class,
    CacheAutoConfiguration.class,
    LockAutoConfiguration.class,
    // ...
})
public class MapleCoreAutoConfiguration {}
```

**현재 상황:**
- AutoConfiguration 클래스 없음
- 각 Config 클래스가 `@Configuration`으로 개별 등록
- 모듈 사용 시 모든 Config를 직접 import 해야 함

**해결 방안:**
```java
// module-infra/src/main/java/.../autoconfiguration/
@AutoConfiguration
@ConditionalOnProperty(prefix = "maple.infra", name = "enabled", havingValue = "true")
@Import({
    CacheAutoConfiguration.class,
    LockAutoConfiguration.class,
    ExecutorAutoConfiguration.class,
    ResilienceAutoConfiguration.class,
    ShutdownAutoConfiguration.class
})
public class MapleInfraAutoConfiguration {
    // META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    // → maple.expectation.infra.autoconfiguration.MapleInfraAutoConfiguration
}
```

### 3.4 [P2] **병렬 빌드 최적화 미흡**

**ADR-014 권장:**
> maple-common과 maple-domain은 서로 의존하지 않으므로 **병렬 컴파일 가능**

**현재 구조:**
```
module-infra → module-core → module-common
```
- 모든 모듈이 `module-common`에 의존
- 병렬 빌드 불가능

**해결 방안:**
```
module-core (순수 도메인)
  └── module-common (POJO, DTO)

module-infra (Spring Infrastructure)
  ├── module-core
  └── module-common
```

---

## 4. 순환 의존 해결을 위한 ADR-014 가이드라인

### 4.1 순환 의존 감지 명령어

```bash
# [F1] 순환 의존 검증
./gradlew dependencies
# 예상: module-common이 어떤 모듈에도 의존하지 않음

# 전체 의존성 그래프 시각화
./gradlew dependencies | grep -A 20 "module-"
```

### 4.2 Gradle `api` vs `implementation` 구분

**ADR-014 원칙:**
> 의존성 전파 범위를 제한하여 순환 의존 방지

```groovy
// 잘못된 예 (module-core가 module-common의 의존성까지 노출)
dependencies {
    api project(':module-common')  // ❌ module-common의 Jackson까지 노출
}

// 올바른 예
dependencies {
    implementation project(':module-common')  // ✅ 내부에서만 사용
}
```

### 4.3 현재 빌드 실패 원인 분석

**컴파일 에러:**
```
error: package maple.expectation.global.error.exception.marker does not exist
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
```

**원인:**
1. `module-common`의 Exception 클래스가 `marker` 패키지를 참조
2. 하지만 `marker` 패키지가 아직 이관되지 않음
3. 또는 `marker` 패키지가 `module-common` 내부에 있지만 패키지 경로 불일치

**해결 방안:**

**Step 1:** marker 패키지 확인
```bash
find /home/maple/probabilistic-valuation-engine -name "CircuitBreakerIgnoreMarker.java" -o -name "CircuitBreakerRecordMarker.java"
```

**Step 2:** marker 패키지를 module-common으로 이동 (또는 생성)
```bash
# module-common/src/main/java/maple/expectation/common/resilience/marker/
mkdir -p module-common/src/main/java/maple/expectation/common/resilience/marker
```

**Step 3:** Exception 클래스의 import 경로 수정
```java
// 이전
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

// 수정 후
import maple.expectation.common.resilience.marker.CircuitBreakerIgnoreMarker;
```

---

## 5. 권장 개선 안 (3가지 옵션)

### Option A: ADR-014 완전 준수 (최적 확장성)

**모듈 구조:**
```
maple-common      ← POJO, DTO, 함수형 인터페이스 (의존성 없음)
maple-core        ← Spring Infrastructure, AutoConfiguration
maple-domain      ← JPA Entity, Repository
maple-app         ← Controller, Service
```

**장점:**
- CQRS 전환 시 모듈 재조합만으로 서버 분리
- ADR-014의 모든 권장사항 준수
- 병렬 빌드 최적화

**단점:**
- 현재 `module-core` (순수 도메인)를 `maple-domain`으로 명명 변경 필요
- `module-infra`를 `maple-core`로 통합/명명 변경 필요

### Option B: 현재 구조 유지 + 최소 수정 (빠른 해결)

**유지할 것:**
- `module-common`, `module-core`, `module-infra`, `module-app` 명명
- 현재 패키지 구조

**수정할 것:**
1. `module-common`에서 Spring 의존성 제거 (`compileOnly`만 유지)
2. `marker` 패키지를 `module-common` 내부로 이동 및 import 경로 수정
3. AutoConfiguration 추가 (선택사항)

**장점:**
- 최소한의 변경으로 빌드 복구
- 현재 구조와의 호환성 유지

**단점:**
- ADR-014와 명명 규칙 불일치
- 향후 CQRS 전환 시 추가 작업 필요

### Option C: 혼합형 (현실적 타협)

**모듈 구조:**
```
module-common     ← POJO, DTO, Exception (의존성 최소화)
module-domain     ← 순수 도메인 + JPA Entity
module-infra      ← Spring Infrastructure, AutoConfiguration
module-app        ← Application, Controller, Service
```

**의존성 흐름:**
```
module-app
  ├── module-infra
  ├── module-domain
  └── module-common

module-infra
  ├── module-domain
  └── module-common

module-domain
  └── module-common (의존성 없음에 가깝게)
```

**장점:**
- 현재 구조와 최대한 유사
- 도메인과 인프라의 분리 명확
- CQRS 전환 시 module-infra만 재사용

**단점:**
- module-common이 여전히 Jackson 등에 의존 가능성

---

## 6. 즉시 실행 가능한 해결 방안 (현재 빌드 복구)

### 6.1 marker 패키지 생성 및 이동

```bash
# 1. marker 패키지가 있는지 확인
find /home/maple/probabilistic-valuation-engine -name "*Marker.java"

# 2. module-common에 marker 패키지 생성
mkdir -p module-common/src/main/java/maple/expectation/common/resilience/marker

# 3. marker 인터페이스 생성 (없는 경우)
cat > module-common/src/main/java/maple/expectation/common/resilience/marker/CircuitBreakerIgnoreMarker.java << 'EOF'
package maple.expectation.common.resilience.marker;

public interface CircuitBreakerIgnoreMarker {
}
EOF

cat > module-common/src/main/java/maple/expectation/common/resilience/marker/CircuitBreakerRecordMarker.java << 'EOF'
package maple.expectation.common.resilience.marker;

public interface CircuitBreakerRecordMarker {
}
EOF

# 4. Exception 클래스의 import 경로 수정 (일괄替换)
find module-common/src/main/java -name "*.java" -exec sed -i 's|maple.expectation.global.error.exception.marker|maple.expectation.common.resilience.marker|g' {} \;
```

### 6.2 module-common 의존성 최소화

```groovy
// module-common/build.gradle
dependencies {
    // Spring 제거
    // implementation 'org.springframework.boot:spring-boot-starter-web'  // ❌ 삭제

    // 대신 compileOnly로 HttpServlet API만 참조
    compileOnly 'jakarta.servlet:jakarta.servlet-api:6.0.0'

    // Jackson (JSON 직렬화용)
    implementation 'com.fasterxml.jackson.core:jackson-annotations:2.17.0'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.3'
}
```

### 6.3 빌드 검증

```bash
# 의존성 확인
./gradlew dependencies

# 빌드 테스트
./gradlew clean build -x test

# 전체 테스트
./gradlew test
```

---

## 7. 장기적 로드맵 (ADR-014 완전 준수)

### Phase 1: 빌드 복구 (즉시)
- marker 패키지 이관 및 import 수정
- module-common 의존성 최소화
- 빌드 통과 확인

### Phase 2: AutoConfiguration 추가 (1주일)
- `module-infra`에 `MapleInfraAutoConfiguration` 생성
- `@ConditionalOnProperty`로 기능별 ON/OFF 지원
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 생성

### Phase 3: 모듈 명명 정리 (선택사항, 2-3일)
- `module-core` → `module-domain` (순수 도메인)
- `module-infra` → `maple-core` (Spring Infrastructure)
- `module-common` → `maple-common` (POJO)

### Phase 4: CQRS 준비 (#126)
- 현재 `module-app`을 `maple-api`, `maple-worker`로 분리
- 공통 모듈(`maple-common`, `maple-core`, `module-domain`) 재사용

---

## 8. 결론 및 우선순위

### 즉시 실행 (P0)
1. ✅ marker 패키지를 `module-common`으로 이관
2. ✅ Exception import 경로 수정
3. ✅ module-common에서 Spring 의존성 제거
4. ✅ 빌드 통과 확인

### 단기 개선 (P1, 1주일 내)
1. ⚠️ AutoConfiguration 추가
2. ⚠️ `@ConditionalOnProperty`로 기능별 ON/OFF
3. ⚠️ 병렬 빌드 최적화 검토

### 장기 계획 (P2, 1-2주일)
1. 📋 모듈 명명을 ADR-014와 통일 (선택사항)
2. 📋 CQRS 전환을 위한 모듈 구조 검증
3. 📋 문서 업데이트 (ADR-017 보완)

---

## 9. 참조 문서

- **ADR-014**: `/home/maple/probabilistic-valuation-engine/docs/01_Adr/ADR-014-multi-module-cross-cutting-concerns.md`
- **ADR-017**: Equipment Domain Extraction (현재 진행 중)
- **#126 Pragmatic CQRS**: https://github.com/zbnerd/probabilistic-valuation-engine/issues/126
- **CLAUDE.md**: `/home/maple/probabilistic-valuation-engine/CLAUDE.md`

---

*분석 완료일: 2026-02-10*
*상태: 제안 (Proposed)*
