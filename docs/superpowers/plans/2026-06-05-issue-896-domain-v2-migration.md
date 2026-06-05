# Issue #896: 레거시 domain/v2 이관 및 중복 enum 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `module-infra/domain/v2/`의 7개 레거시 JPA 엔티티/포트를 `infrastructure/persistence/`로 이관하고, module-core와 중복되는 `CubeType`/`PotentialGrade` enum을 통합한다. DB 스키마 변경 없음.

**Architecture:** 단순 파일 이동 + 패키지 변경. v2 테이블(`game_character_v2`, `member`, `donation_history`, `equipment_expectation_summary`)은 그대로 유지. v2 엔티티에 남아 있던 비즈니스 로직(`Member.deductPoints()`, `GameCharacter.isActive()` 등) 제거 → port 인터페이스의 원자적 쿼리(`decreasePoint`, `increasePointByUuid`)로 대체. v2/CubeType과 v2/PotentialGrade는 module-core enum 단일 사용.

**Tech Stack:** Kotlin 1.9+, Java 17, Spring Boot 3, JPA/Hibernate, Gradle 8, JUnit 5, AssertJ

---

## File Structure

### 이동할 파일 (7개 엔티티/VO)

| 원본 | 대상 | 비고 |
|------|------|------|
| `module-infra/src/main/kotlin/maple/expectation/domain/v2/Member.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/MemberEntity.kt` | 이름 변경, `deductPoints()`/`hasEnoughPoint()`/`maskUuid()` 제거 |
| `module-infra/src/main/kotlin/maple/expectation/domain/v2/GameCharacter.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/GameCharacterV2Entity.kt` | 이름 변경, `isActive()`/`validateOcid()`/`needsBasicInfoRefresh()`/`like()` 제거 |
| `module-infra/src/main/kotlin/maple/expectation/domain/v2/DonationHistory.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/DonationHistoryEntity.kt` | 이름만 변경 |
| `module-infra/src/main/kotlin/maple/expectation/domain/v2/EquipmentExpectationSummary.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/EquipmentExpectationSummaryEntity.kt` | `updateExpectation()`/`touch()` 제거 (호출자 없음) |
| `module-infra/src/main/kotlin/maple/expectation/domain/v2/CubeProbability.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CubeProbability.kt` | 이름만 변경, `CubeType` import를 core로 |
| `module-infra/src/main/kotlin/maple/expectation/domain/v2/CubeType.kt` | **삭제** | `core.domain.model.CubeType` 사용 |
| `module-infra/src/main/kotlin/maple/expectation/domain/v2/PotentialGrade.kt` | **삭제** | `core.domain.model.PotentialGrade` 사용 (`fromKorean`만 수정) |

### 이동할 port 인터페이스 (5개)

| 원본 | 대상 |
|------|------|
| `module-infra/src/main/kotlin/maple/expectation/domain/repository/MemberRepository.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepository.kt` |
| `module-infra/src/main/kotlin/maple/expectation/domain/repository/GameCharacterRepository.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/GameCharacterRepository.kt` |
| `module-infra/src/main/kotlin/maple/expectation/domain/repository/CharacterEquipmentRepository.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterEquipmentRepository.kt` |
| `module-infra/src/main/kotlin/maple/expectation/domain/repository/CharacterLikeRepository.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterLikeRepository.kt` |
| `module-infra/src/main/kotlin/maple/expectation/domain/repository/CubeProbabilityRepository.kt` | `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CubeProbabilityRepository.kt` |

### 호출자 측 수정 파일 (~50+ 파일)

- `module-app/src/main/java/**`: ~15 파일
- `module-infra/src/main/java/**` 및 `kotlin/**`: ~25 파일 (Impl + JPA + 서비스)
- `module-infra/src/test/kotlin/**`: ~3 파일
- `module-app/src/test/kotlin/**`: ~5 파일

### 삭제 대상 디렉토리

- `module-infra/src/main/kotlin/maple/expectation/domain/v2/` (전체 삭제)
- `module-infra/src/main/kotlin/maple/expectation/domain/repository/` (전체 삭제)

---

## Task 1: `module-core/PotentialGrade.fromKorean()` 예외 정책 통일

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/domain/model/PotentialGrade.kt`

- [ ] **Step 1: `PotentialGrade.kt` 수정**

`module-core/src/main/kotlin/maple/expectation/core/domain/model/PotentialGrade.kt` 전체를 다음으로 교체:

```kotlin
package maple.expectation.core.domain.model

import maple.expectation.error.exception.InvalidPotentialGradeException

/**
 * 잠재능력 등급 Enum
 *
 * <p>큐브 사용 시 입력되는 잠재능력 등급의 유효성을 검증합니다. 잘못된 등급 입력 시 Silent Failure(0원 반환) 대신 명시적 예외를 발생시킵니다.
 *
 * @see InvalidPotentialGradeException
 */
enum class PotentialGrade(val koreanName: String) {
    RARE("레어"),
    EPIC("에픽"),
    UNIQUE("유니크"),
    LEGENDARY("레전드리"),
    ;

    companion object {
        private val KOREAN_MAP = entries.associateBy { it.koreanName }

        /**
         * 한글 등급명으로 PotentialGrade를 조회합니다.
         *
         * @param korean 한글 등급명 (예: "레어", "에픽", "유니크", "레전드리")
         * @return 매칭되는 PotentialGrade
         * @throws InvalidPotentialGradeException 유효하지 않은 등급명인 경우
         */
        @JvmStatic
        fun fromKorean(korean: String?): PotentialGrade {
            if (korean == null) {
                throw InvalidPotentialGradeException("null")
            }
            return KOREAN_MAP[korean.trim()]
                ?: throw InvalidPotentialGradeException(korean)
        }
    }
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :module-core:compileKotlin --continue 2>&1 | grep -E "error|warning" | head -20
```

Expected: 기존 컴파일 결과와 동일 (에러 없음).

- [ ] **Step 3: 커밋**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/domain/model/PotentialGrade.kt
git commit -m "refactor(core): PotentialGrade.fromKorean throw InvalidPotentialGradeException"
```

---

## Task 2: `CubeType.kt` v2 삭제 및 호출자 import 치환

**Files:**
- Delete: `module-infra/src/main/kotlin/maple/expectation/domain/v2/CubeType.kt`
- Modify: 호출자 측 import (약 10+ 파일)

- [ ] **Step 1: 호출자 import 검색**

```bash
grep -rln "maple.expectation.domain.v2.CubeType" --include="*.kt" --include="*.java" 2>&1
```

기대 출력 파일 목록 (대표):
- `module-infra/src/main/java/maple/expectation/application/service/cube/AbstractCubeDecorator.java`
- `module-infra/src/main/java/maple/expectation/application/service/cube/CubeServiceImpl.java`
- `module-infra/src/main/java/maple/expectation/application/service/cube/component/CubeDpCalculator.java`
- `module-infra/src/main/java/maple/expectation/application/service/cube/component/SlotDistributionBuilder.java`
- `module-infra/src/main/java/maple/expectation/application/service/cube/component/CubeSlotCountResolver.java`
- `module-infra/src/main/java/maple/expectation/application/service/cube/AbstractCubeDecoratorV4.java`
- `module-infra/src/main/java/maple/expectation/application/service/cube/policy/CubeCostPolicy.java`
- `module-infra/src/main/java/maple/expectation/application/service/cube/CubeTrialsProvider.java`
- `module-infra/src/main/kotlin/maple/expectation/application/service/calculator/v4/impl/BlackCubeDecoratorV4.kt`
- `module-infra/src/main/kotlin/maple/expectation/application/service/calculator/v4/impl/RedCubeDecoratorV4.kt`
- `module-infra/src/main/kotlin/maple/expectation/application/service/calculator/v4/impl/AdditionalCubeDecoratorV4.kt`
- `module-infra/src/main/kotlin/maple/expectation/application/service/calculator/impl/BlackCubeDecorator.kt`
- `module-app/src/main/java/maple/expectation/config/CorePortAdapterConfig.java` (`legacyType.toCore()` 호출부)

- [ ] **Step 2: v2.CubeType → core.CubeType 일괄 치환**

```bash
find module-infra/src module-app/src -name "*.kt" -o -name "*.java" | xargs sed -i 's|maple\.expectation\.domain\.v2\.CubeType|maple.expectation.core.domain.model.CubeType|g'
```

- [ ] **Step 3: `CubeType.toCore()`/`fromCore()` 호출 제거**

`module-app/src/main/java/maple/expectation/config/CorePortAdapterConfig.java`의 `mapToCoreCubeType` 메서드:

```java
private static maple.expectation.core.domain.model.CubeType mapToCoreCubeType(
    maple.expectation.core.domain.model.CubeType legacyType) {
  return legacyType;
}
```

메서드 호출부에서 `mapToCoreCubeType(...)` 인자에서 `.toCore()` 제거하고, 결과를 직접 사용.

`CubeServiceImpl.java`, `CubeDpCalculator.java` 등에서 `cubeType.toCore()` 호출 패턴 검색:

```bash
grep -rn "\.toCore()" --include="*.kt" --include="*.java" module-infra/src module-app/src 2>&1
```

각 `cubeType.toCore()`를 `cubeType`으로 단순화 (둘 다 같은 `CubeType`이므로).

- [ ] **Step 4: v2/CubeType.kt 삭제**

```bash
git rm module-infra/src/main/kotlin/maple/expectation/domain/v2/CubeType.kt
```

- [ ] **Step 5: 컴파일 검증**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "^e: " | head -20
```

Expected: 에러 없음. 미해결 import 또는 `.toCore()` 잔존 시 에러 발생.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor(infra): remove v2/CubeType, use core.CubeType"
```

---

## Task 3: `PotentialGrade.kt` v2 삭제 및 호출자 import 치환

**Files:**
- Delete: `module-infra/src/main/kotlin/maple/expectation/domain/v2/PotentialGrade.kt`

- [ ] **Step 1: 호출자 import 검색**

```bash
grep -rln "maple.expectation.domain.v2.PotentialGrade\|domain\.v2\.PotentialGrade" --include="*.kt" --include="*.java" 2>&1
```

- [ ] **Step 2: v2.PotentialGrade → core.PotentialGrade 일괄 치환**

```bash
find module-infra/src module-app/src -name "*.kt" -o -name "*.java" | xargs sed -i 's|maple\.expectation\.domain\.v2\.PotentialGrade|maple.expectation.core.domain.model.PotentialGrade|g'
```

- [ ] **Step 3: `IllegalArgumentException` catch 블록 검색**

`PotentialGrade.fromKorean()`이 `InvalidPotentialGradeException`을 던지므로, 기존 `catch (IllegalArgumentException)` 호출자는 변경 필요:

```bash
grep -rn "catch.*IllegalArgumentException" --include="*.kt" --include="*.java" 2>&1 | head -10
```

해당 파일에서 `PotentialGrade` 사용 컨텍스트를 확인하고 `catch (InvalidPotentialGradeException)` 또는 상위 `catch (Exception)`으로 변경.

- [ ] **Step 4: v2/PotentialGrade.kt 삭제**

```bash
git rm module-infra/src/main/kotlin/maple/expectation/domain/v2/PotentialGrade.kt
```

- [ ] **Step 5: 컴파일 검증**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "^e: " | head -20
```

- [ ] **Step 6: 테스트 실행**

```bash
./gradlew :module-core:test --tests "*PotentialGrade*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor(infra): remove v2/PotentialGrade, use core.PotentialGrade"
```

---

## Task 4: 5개 port 인터페이스를 `infrastructure/persistence/repository/`로 이동

**Files:**
- Move: 5개 인터페이스 (`MemberRepository`, `GameCharacterRepository`, `CharacterEquipmentRepository`, `CharacterLikeRepository`, `CubeProbabilityRepository`)

- [ ] **Step 1: `git mv`로 인터페이스 이동**

```bash
cd module-infra/src/main/kotlin/maple/expectation
git mv domain/repository/MemberRepository.kt infrastructure/persistence/repository/MemberRepository.kt
git mv domain/repository/GameCharacterRepository.kt infrastructure/persistence/repository/GameCharacterRepository.kt
git mv domain/repository/CharacterEquipmentRepository.kt infrastructure/persistence/repository/CharacterEquipmentRepository.kt
git mv domain/repository/CharacterLikeRepository.kt infrastructure/persistence/repository/CharacterLikeRepository.kt
git mv domain/repository/CubeProbabilityRepository.kt infrastructure/persistence/repository/CubeProbabilityRepository.kt
```

- [ ] **Step 2: 각 인터페이스의 `package` 선언 수정**

5개 파일 모두 첫 줄을 변경:

- `MemberRepository.kt`: `package maple.expectation.infrastructure.persistence.repository`
- `GameCharacterRepository.kt`: `package maple.expectation.infrastructure.persistence.repository`
- `CharacterEquipmentRepository.kt`: `package maple.expectation.infrastructure.persistence.repository`
- `CharacterLikeRepository.kt`: `package maple.expectation.infrastructure.persistence.repository`
- `CubeProbabilityRepository.kt`: `package maple.expectation.infrastructure.persistence.repository` (단, v2의 `CubeType`/`CubeProbability` import는 Task 5에서 정리)

- [ ] **Step 3: `domain/repository/` 디렉토리 삭제**

```bash
rmdir module-infra/src/main/kotlin/maple/expectation/domain/repository 2>&1
```

(비어 있으면 성공; 안 비면 `ls` 후 잔여 파일 확인)

- [ ] **Step 4: Impl 파일의 `as Domain...` 별칭 제거**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/` 내 5개 Impl 파일:

```bash
grep -rln "as Domain" --include="*.kt" module-infra/src/main 2>&1
```

각 Impl 파일에서:
```kotlin
import maple.expectation.domain.repository.GameCharacterRepository as DomainGameCharacterRepository
```
를
```kotlin
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository
```
로 변경. `DomainGameCharacterRepository` 명시적 사용은 모두 `GameCharacterRepository`로 변경.

- [ ] **Step 5: 호출자 측 import 일괄 치환**

```bash
find . -name "*.kt" -o -name "*.java" | xargs sed -i 's|maple\.expectation\.domain\.repository\.|maple.expectation.infrastructure.persistence.repository.|g'
```

- [ ] **Step 6: 컴파일 검증**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "^e: " | head -20
```

Expected: 에러 없음. 별칭 제거 후 동일 패키지 내 인터페이스/Impl이 공존하므로 `import` 중복 에러 가능 → Impl 파일에 `import` 라인 자체가 불필요해질 수 있음 (같은 패키지).

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor(infra): move port interfaces to infrastructure/persistence/repository/"
```

---

## Task 5: v2 엔티티 5개 이동 (Member, GameCharacter, DonationHistory, EquipmentExpectationSummary, CubeProbability)

**Files:**
- Move: 5개 엔티티 (`Member`, `GameCharacter`, `DonationHistory`, `EquipmentExpectationSummary`, `CubeProbability`)

- [ ] **Step 1: 5개 엔티티 이동 (git mv)**

```bash
cd module-infra/src/main/kotlin/maple/expectation
git mv domain/v2/Member.kt infrastructure/persistence/entity/MemberEntity.kt
git mv domain/v2/GameCharacter.kt infrastructure/persistence/entity/GameCharacterV2Entity.kt
git mv domain/v2/DonationHistory.kt infrastructure/persistence/entity/DonationHistoryEntity.kt
git mv domain/v2/EquipmentExpectationSummary.kt infrastructure/persistence/entity/EquipmentExpectationSummaryEntity.kt
git mv domain/v2/CubeProbability.kt infrastructure/persistence/entity/CubeProbability.kt
```

- [ ] **Step 2: `MemberEntity.kt`에서 비즈니스 로직 제거**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/MemberEntity.kt`에서 다음 메서드와 import 제거:

- `import maple.expectation.error.exception.InsufficientPointException`
- `import java.util.UUID`
- `fun hasEnoughPoint(amount: Long): Boolean`
- `fun deductPoints(amount: Long)`
- `fun validatePositiveAmount(amount: Long)`
- `fun maskUuid(): String`

클래스는 다음 구조만 유지:

```kotlin
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*

/**
 * Member 엔티티 (JPA 매핑 전용)
 *
 * <p>비즈니스 로직(포인트 차감 등)은 port 인터페이스의 원자적 쿼리로 처리.
 *
 * <p>Issue #896: v2 패키지에서 infrastructure/persistence/entity/로 이관.
 */
@Entity
@Table(indexes = [Index(name = "idx_uuid", columnList = "uuid", unique = true)])
class MemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long? = null

    @Column(nullable = false, unique = true, length = 36)
    var uuid: String? = null

    var point: Long = 0L

    private constructor()

    private constructor(uuid: String, initialPoint: Long) {
        this.uuid = uuid
        this.point = initialPoint
    }

    companion object {
        @JvmStatic
        fun createSystemAdmin(uuid: String, initialPoint: Long): MemberEntity = MemberEntity(uuid, initialPoint)

        @JvmStatic
        fun createGuest(initialPoint: Long): MemberEntity = MemberEntity(java.util.UUID.randomUUID().toString(), initialPoint)
    }
}
```

- [ ] **Step 3: `GameCharacterV2Entity.kt`에서 비즈니스 로직 제거**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/GameCharacterV2Entity.kt`에서:

- `import maple.expectation.error.exception.InvalidCharacterStateException`
- `import com.fasterxml.jackson.annotation.JsonIgnore` (equipment 제거 시 불필요)
- `import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity`
- `import org.hibernate.annotations.NotFound`
- `import org.hibernate.annotations.NotFoundAction`
- `@OneToOne` `equipment` 필드 전체
- `fun like()`
- `fun isActive()`
- `fun validateOcid()`
- `fun needsBasicInfoRefresh()`
- `private fun validateOcidInternal(ocidValue: String?)`
- `validateOcidInternal(this.ocid)` 호출이 있던 모든 setter (worldName, characterClass, characterImage, basicInfoUpdatedAt, equipment)

클래스 최종 구조:

```kotlin
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * GameCharacter v2 엔티티 (JPA 매핑 전용)
 *
 * <p>비즈니스 로직 제거. table=game_character_v2 유지.
 *
 * <p>Issue #896: v2 패키지에서 infrastructure/persistence/entity/로 이관.
 */
@Entity
@Table(name = "game_character_v2")
class GameCharacterV2Entity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, unique = true)
    var userIgn: String? = null

    @Column(nullable = false, unique = true)
    var ocid: String? = null

    @Column(length = 50)
    var worldName: String? = null

    @Column(length = 50)
    var characterClass: String? = null

    @Column(length = 2048)
    var characterImage: String? = null

    var basicInfoUpdatedAt: LocalDateTime? = null

    @Version
    var version: Long? = null

    var likeCount: Long = 0L

    var updatedAt: LocalDateTime? = null

    private constructor()

    constructor(userIgn: String, ocid: String) {
        this.userIgn = userIgn
        this.ocid = ocid
        this.likeCount = 0L
        this.updatedAt = LocalDateTime.now()
    }
}
```

- [ ] **Step 4: `EquipmentExpectationSummaryEntity.kt` 정리**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/EquipmentExpectationSummaryEntity.kt`에서:

- `fun updateExpectation(...)` 제거
- `fun touch()` 제거

그 외 JPA 매핑은 유지.

- [ ] **Step 5: `CubeProbability.kt` import 정리**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CubeProbability.kt`의 `package`를 `maple.expectation.infrastructure.persistence.entity`로 변경. import에 v2 enum 없음 (core `CubeType`은 별도 import 추가 불필요, `CubeProbability` 클래스의 `cubeType: CubeType`은 동일한 `maple.expectation.core.domain.model.CubeType`을 참조).

확인: 새 위치로 이동한 후, `CubeProbability`가 참조하는 `CubeType`은 이제 core enum이므로 import 라인 불필요 (같은 패키지가 아니므로 추가):

```kotlin
package maple.expectation.infrastructure.persistence.entity

import com.fasterxml.jackson.annotation.JsonProperty
import maple.expectation.core.domain.model.CubeType

data class CubeProbability(
    @JsonProperty("cube_type")
    val cubeType: CubeType,
    // ... 나머지 필드 동일
)
```

- [ ] **Step 6: `domain/v2/` 디렉토리 삭제**

```bash
rmdir module-infra/src/main/kotlin/maple/expectation/domain/v2 2>&1
ls module-infra/src/main/kotlin/maple/expectation/domain 2>&1
```

`domain/` 디렉토리가 비면 `rmdir domain`도 수행.

- [ ] **Step 7: `domain/` 디렉토리가 완전히 비었으면 삭제**

```bash
[ -z "$(ls -A module-infra/src/main/kotlin/maple/expectation/domain 2>/dev/null)" ] && rmdir module-infra/src/main/kotlin/maple/expectation/domain
```

- [ ] **Step 8: 커밋 (파일 이동 + 비즈니스 로직 제거 단일 커밋)**

```bash
git add -A
git commit -m "refactor(infra): move v2 entities to infrastructure/persistence/entity/, remove business logic"
```

---

## Task 6: 호출자 측 import 일괄 치환

**Files:** ~50+ 파일 (grep 결과 기반)

- [ ] **Step 1: 5개 엔티티 클래스 import 일괄 치환**

```bash
find . -name "*.kt" -o -name "*.java" | xargs sed -i \
  -e 's|maple\.expectation\.domain\.v2\.Member|maple.expectation.infrastructure.persistence.entity.MemberEntity|g' \
  -e 's|maple\.expectation\.domain\.v2\.GameCharacter|maple.expectation.infrastructure.persistence.entity.GameCharacterV2Entity|g' \
  -e 's|maple\.expectation\.domain\.v2\.DonationHistory|maple.expectation.infrastructure.persistence.entity.DonationHistoryEntity|g' \
  -e 's|maple\.expectation\.domain\.v2\.EquipmentExpectationSummary|maple.expectation.infrastructure.persistence.entity.EquipmentExpectationSummaryEntity|g' \
  -e 's|maple\.expectation\.domain\.v2\.CubeProbability|maple.expectation.infrastructure.persistence.entity.CubeProbability|g'
```

- [ ] **Step 2: 잔여 v2 import 검색**

```bash
grep -rln "maple\.expectation\.domain\.v2\." --include="*.kt" --include="*.java" 2>&1
```

Expected: 결과 없음. 잔존 시 1단계 패턴 확인 후 추가 sed.

- [ ] **Step 3: 컴파일 검증 (에러만 표시)**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "^e: " | head -30
```

에러가 남아 있으면 파일별로 수정.

- [ ] **Step 4: 커밋**

```bash
git add -A
git commit -m "refactor: update imports for v2 entity relocation"
```

---

## Task 7: `Member.deductPoints()` 호출자 리팩터 (atomic query로)

**Files:**
- Modify: `module-app/src/main/java/maple/expectation/application/service/donation/InternalPointPaymentStrategy.java`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepositoryImpl.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepository.kt`

- [ ] **Step 1: `MemberRepository` port에 `decreasePoint` 메서드 추가**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepository.kt`에 `MemberRepository` 인터페이스에 새 메서드 추가:

```kotlin
/**
 * Atomically decrease point balance by UUID.
 *
 * <p>Uses {@code WHERE point >= amount} to prevent overdraw in high-concurrency scenarios.
 *
 * @param uuid the member's UUID
 * @param amount the amount to decrease (must be positive)
 * @return 1 if successful, 0 if member not found or insufficient balance
 */
fun decreasePointByUuid(uuid: String, amount: Long): Int
```

- [ ] **Step 2: `MemberRepositoryImpl`에 구현 추가**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepositoryImpl.kt`에:

```kotlin
override fun decreasePointByUuid(uuid: String, amount: Long): Int = jpaRepository.decreasePoint(uuid, amount)
```

- [ ] **Step 3: `InternalPointPaymentStrategy.java` 리팩터**

`module-app/src/main/java/maple/expectation/application/service/donation/InternalPointPaymentStrategy.java`의 `processPayment` 메서드를 다음으로 변경:

```java
@Override
@Transactional("transactionManager")
public void processPayment(String senderUuid, String receiverFingerprint, Long amount) {
  log.debug(
      "[Payment] Processing internal point transfer: sender={}, amount={}",
      maskUuid(senderUuid),
      amount);

  // 1. 발신자: 원자적 쿼리 (Issue #896: Rich Domain → Port로 이관)
  //    - WHERE point >= :amount 조건으로 잔액 부족 시 업데이트 0건 반환
  //    - Lost Update 방지 (Optimistic Lock과 동등한 효과)
  if (memberRepository.decreasePointByUuid(senderUuid, amount) == 0) {
    throw new SenderMemberNotFoundException(maskUuid(senderUuid));
  }

  // 2. 수신자(Admin): 원자적 쿼리 (Hot Key 보호)
  if (memberRepository.increasePointByUuid(receiverFingerprint, amount) == 0) {
    throw new AdminMemberNotFoundException(receiverFingerprint);
  }

  log.info("[Payment] Internal point transfer completed: amount={}", amount);
}
```

`Member sender = memberRepository.findByUuid(senderUuid); if (sender == null) ...` 블록 제거. `SenderMemberNotFoundException` import는 그대로 유지 (잔액 부족 또는 sender 없음 시 던짐).

- [ ] **Step 4: 컴파일 검증**

```bash
./gradlew :module-app:compileJava :module-infra:compileKotlin --continue 2>&1 | grep -E "^e: " | head -20
```

- [ ] **Step 5: 단위 테스트 실행**

```bash
./gradlew :module-app:test --tests "*InternalPointPaymentStrategy*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. 테스트가 없으면 skip.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor(app): replace Member.deductPoints with atomic decreasePointByUuid"
```

---

## Task 8: `MemberRepositoryImpl.findOrCreateGuest` 리팩터 (private constructor 직접 호출)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepositoryImpl.kt`

- [ ] **Step 1: `findOrCreateGuest` 메서드 수정**

`MemberEntity`로 클래스명이 바뀌었고, `Member` import 라인이 같은 패키지이므로 제거:

```kotlin
override fun findOrCreateGuest(uuid: String, initialPoint: Long): MemberEntity = findByUuid(uuid) ?: run {
    val constructor = MemberEntity::class.java.getDeclaredConstructor(String::class.java, Long::class.java)
    constructor.isAccessible = true
    val guest = constructor.newInstance(uuid, initialPoint)
    save(guest)
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :module-infra:compileKotlin 2>&1 | grep -E "^e: " | head -10
```

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "refactor(infra): update MemberRepositoryImpl to use MemberEntity"
```

---

## Task 9: 테스트 코드 import 업데이트

**Files:**
- `module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/JpaNPlusOneRegressionTest.kt`
- `module-app/src/test/kotlin/maple/expectation/testfixtures/DomainFixtures.kt`
- `module-app/src/test/kotlin/maple/expectation/integration/repository/MemberRepositoryIntegrationTest.kt`
- 기타 v2 import 보유 테스트 파일

- [ ] **Step 1: 잔여 v2 import 검색 (테스트)**

```bash
grep -rln "maple\.expectation\.domain\.v2\." --include="*.kt" --include="*.java" 2>&1
```

잔여 0건이어야 함. Task 6에서 이미 처리됨.

- [ ] **Step 2: 테스트 fixture 사용 클래스명 업데이트**

`module-app/src/test/kotlin/maple/expectation/testfixtures/DomainFixtures.kt`에서:

```kotlin
import maple.expectation.infrastructure.persistence.entity.MemberEntity
```

`MemberFixture` 객체의 `createGuest()` 등은 `MemberEntity.createGuest(...)` 호출로 변경 (이미 1단계 sed가 적용된 상태).

- [ ] **Step 3: MemberRepositoryIntegrationTest 검증**

`module-app/src/test/kotlin/maple/expectation/integration/repository/MemberRepositoryIntegrationTest.kt`의 `createTestMember` 헬퍼가 fixture를 사용하면 자동 업데이트됨. 직접 `Member` 인스턴스를 만들면 변경 필요.

확인:

```bash
grep -n "Member\|createTestMember" module-app/src/test/kotlin/maple/expectation/integration/repository/MemberRepositoryIntegrationTest.kt
```

`Member` 클래스명이 `MemberEntity`로 바뀌었으므로 `member.point`, `member.uuid` 등 인스턴스 변수 접근은 동일하게 작동 (속성 이름 그대로).

- [ ] **Step 4: 테스트 실행 (에러만 표시)**

```bash
./gradlew test 2>&1 | grep -E "^e: |FAILED|tests completed" | head -30
```

- [ ] **Step 5: 실패한 테스트 수정**

실패가 식별되면 해당 테스트의 import/update를 추가 sed로 처리. 일반적인 패턴:

```bash
find . -name "*Test.kt" -o -name "*Test.java" | xargs sed -i 's|maple\.expectation\.domain\.v2\.|maple.expectation.infrastructure.persistence.entity.|g'
```

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor(test): update test imports for v2 entity relocation"
```

---

## Task 10: ArchUnit 테스트 업데이트

**Files:**
- Modify: `module-app/src/test/java/maple/expectation/archunit/KotlinJpaEntityTest.java`

- [ ] **Step 1: archunit 테스트에서 v2 참조 검색**

```bash
grep -n "domain.v2" module-app/src/test/java/maple/expectation/archunit/KotlinJpaEntityTest.java
```

문서 주석의 `@see maple.expectation.domain.v2.EventOutbox` 등은 javadoc이므로 컴파일 영향 없음. 단순 sed로 새 경로로 변경:

```bash
sed -i 's|maple\.expectation\.domain\.v2\.|maple.expectation.infrastructure.persistence.entity.|g' module-app/src/test/java/maple/expectation/archunit/KotlinJpaEntityTest.java
```

- [ ] **Step 2: archunit 테스트 실행**

```bash
./gradlew :module-app:test --tests "*KotlinJpaEntity*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "test(archunit): update v2 references in KotlinJpaEntityTest"
```

---

## Task 11: 전체 컴파일 + 테스트 통과 검증

**Files:** none (verification only)

- [ ] **Step 1: 전체 컴파일**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "^e: |error:" | head -30
```

Expected: 에러 없음.

- [ ] **Step 2: 전체 테스트 실행**

```bash
./gradlew test 2>&1 | tail -50
```

Expected: BUILD SUCCESSFUL. 실패 시 해당 테스트 분석 후 수정.

- [ ] **Step 3: `./gradlew check` (full verification)**

```bash
./gradlew check 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋 (없으면 skip)**

수정사항 없으면 skip. 있으면:

```bash
git add -A
git commit -m "chore: pass full gradle check after v2 relocation"
```

---

## Task 12: PR 생성

**Files:** none (PR description only)

- [ ] **Step 1: develop base로 PR 생성**

```bash
git push origin develop 2>&1
gh pr create --base develop --title "refactor: 레거시 domain/v2 이관 및 중복 enum 제거 (#896)" --body "## Summary
- \`module-infra/domain/v2/\` 7개 엔티티를 \`infrastructure/persistence/entity/\`로 이동
- \`domain/repository/\` 5개 port 인터페이스를 \`infrastructure/persistence/repository/\`로 이동
- \`CubeType\`/\`PotentialGrade\` v2 중복 제거 → \`module-core\` enum 단일 사용
- \`Member\`/\`GameCharacter\` v2 엔티티에서 비즈니스 로직 제거 → port의 원자적 쿼리로 대체

## Acceptance Criteria
- [x] \`domain/v2/\` 패키지 제거
- [x] \`domain/repository/\` → \`infrastructure/persistence/repository/\` 이동
- [x] \`CubeType\`/\`PotentialGrade\` Core enum 통합
- [x] \`GameCharacter\` 엔티티에서 비즈니스 로직 제거
- [x] 전체 import 업데이트
- [x] \`./gradlew compileKotlin compileJava --continue\` 통과
- [x] \`./gradlew test\` 통과

## Test
\`\`\`bash
./gradlew compileKotlin compileJava --continue
./gradlew test
\`\`\`"
```

- [ ] **Step 2: PR URL 사용자 보고**

PR URL을 사용자에게 보고하고 코드리뷰 요청.

---

## Self-Review Checklist (실행자가 직접 확인)

- [ ] 모든 v2 import 치환 완료 (`grep` 결과 0건)
- [ ] `domain/v2/`, `domain/repository/` 디렉토리 삭제 완료
- [ ] `module-core` enum 통합 후 IllegalArgumentException 잔존 catch 없음
- [ ] `Member.deductPoints()` 직접 호출 0건 (InternalPointPaymentStrategy 제외, 이는 Task 7에서 리팩터)
- [ ] DB 스키마 변경 없음 (`game_character_v2`, `member`, `donation_history`, `equipment_expectation_summary` 그대로)
- [ ] 12개 Task 모두 커밋 완료
- [ ] develop base PR 생성

---

## Notes

### 1. 기존 entity와 새 v2 entity의 분리
- `GameCharacterJpaEntity` (table=`game_character`) vs `GameCharacterV2Entity` (table=`game_character_v2`) — 의도적 분리 유지
- `MemberEntity` (v2에서 이동, table=`member`) — 새 infra에 동명의 `Member` 엔티티 없음
- `CubeProbability` (table 없음, in-memory CSV 캐시) — 위치만 변경

### 2. 비즈니스 로직 제거 매핑
| v2 메서드 | 대체 |
|-----------|------|
| `Member.deductPoints()` | `MemberRepository.decreasePointByUuid()` (atomic UPDATE) |
| `Member.hasEnoughPoint()` | 제거 (atomic query의 WHERE 조건이 처리) |
| `GameCharacter.isActive()` | 제거 (호출자 없음) |
| `GameCharacter.validateOcid()` | 제거 (JPA `@Column(nullable=false)`가 DB 레벨 검증) |
| `GameCharacter.needsBasicInfoRefresh()` | 제거 (호출자 없음) |
| `GameCharacter.like()` | 제거 (호출자 없음, 대신 port `incrementLikeCount`) |
| `EquipmentExpectationSummary.updateExpectation()` | 제거 (호출자 없음) |
| `EquipmentExpectationSummary.touch()` | 제거 (호출자 없음) |

### 3. PR 분할 결정
- 단일 PR로 진행. 의존성 강하게 결합되어 있어 분리 시 중간 상태가 컴파일 안 됨.
- 12개 Task 순서가 선형: enum 통합 → port 이동 → entity 이동 → 호출자 정리 → 검증.
