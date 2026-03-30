# Module Separation + Kotlin Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate 347 Java files from module-app to 4 modules (common, core, infra, web) with **simultaneous Kotlin conversion**.

**Architecture:** Sequential migration with Kotlin conversion at each step. DTO → data class, models → Kotlin, business logic preserved.

**Tech Stack:** Java 21 + Kotlin 2.x, Spring Boot 3.5.4, Gradle (Kotlin DSL optional), ArchUnit, Testcontainers

---

## Phase 0: Foundation (Java + Kotlin Setup)

### Task 0.1: Write ADR Document

**Files:**
- Create: `docs/adr/002-module-separation-kotlin.md`

**Step 1: Create ADR file**

```markdown
# ADR-002: 4-Module Separation + Kotlin Migration

## Status
Accepted (2026-02-27)

## Context
- module-app contains 347 Java files (all code)
- Kotlin migration is planned anyway
- Doing both together reduces total refactoring cycles

## Decision
1. Split into 4 modules:
   - module-common: Pure utilities (Kotlin)
   - module-core: Business rules, ports (Kotlin + Java)
   - module-infra: External adapters (Java first, Kotlin later)
   - module-web: HTTP layer, DTOs (Kotlin data class)

2. Migration order per module:
   - DTO → Kotlin data class (immediate)
   - Models → Kotlin data class (immediate)
   - Business logic → Kotlin (gradual)
   - Infra adapters → Java (keep, convert later)

## Consequences
- Faster path to Kotlin codebase
- Higher risk per commit (rollback with git)
- More complex debugging

## Related
- Issues: #409-#443
```

**Step 2: Commit**

```bash
git add docs/adr/002-module-separation-kotlin.md
git commit -m "docs: ADR-002 Module Separation + Kotlin Migration"
```

---

### Task 0.2: Configure Kotlin + Java Mixed Build

**Files:**
- Modify: `build.gradle` (root)
- Modify: `module-common/build.gradle`
- Modify: `module-core/build.gradle`
- Modify: `module-infra/build.gradle`
- Modify: `module-web/build.gradle`

**Step 1: Add Kotlin to root build.gradle**

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.0.0' apply false
    id 'org.jetbrains.kotlin.plugin.spring' version '2.0.0' apply false
    id 'org.jetbrains.kotlin.plugin.jpa' version '2.0.0' apply false
}

ext {
    kotlinVersion = '2.0.0'
}
```

**Step 2: Configure module-web/build.gradle for Kotlin**

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm'
    id 'org.jetbrains.kotlin.plugin.spring'
}

dependencies {
    implementation project(':module-common')
    implementation project(':module-core')
    implementation project(':module-infra')

    // Kotlin
    implementation 'org.jetbrains.kotlin:kotlin-stdlib'
    implementation 'org.jetbrains.kotlin:kotlin-reflect'
    implementation 'com.fasterxml.jackson.module:jackson-module-kotlin'

    // Spring Web
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.2.1'
}

kotlin {
    jvmToolchain(21)
}

compileKotlin {
    kotlinOptions {
        freeCompilerArgs += '-Xjsr305=strict'
    }
}
```

**Step 3: Configure module-core/build.gradle for Kotlin**

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm'
}

dependencies {
    implementation project(':module-common')

    // Kotlin (no Spring)
    implementation 'org.jetbrains.kotlin:kotlin-stdlib'

    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core'
    testImplementation 'org.mockito:mockito-core'
    testImplementation 'org.jetbrains.kotlin:kotlin-test-junit5'
}

kotlin {
    jvmToolchain(21)
}
```

**Step 4: Configure module-common/build.gradle for Kotlin**

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm'
}

dependencies {
    // Pure Kotlin, no Spring
    implementation 'org.jetbrains.kotlin:kotlin-stdlib'

    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core'
}

kotlin {
    jvmToolchain(21)
}
```

**Step 5: Verify mixed compilation**

```bash
# Create a test Kotlin file
mkdir -p module-web/src/main/kotlin/maple/expectation/web
echo 'package maple.expectation.web

data class TestDto(val name: String)' > module-web/src/main/kotlin/maple/expectation/web/TestDto.kt

./gradlew clean build -x test
```

Expected: BUILD SUCCESSFUL

**Step 6: Remove test file and commit**

```bash
rm -rf module-web/src/main/kotlin/

git add build.gradle module-*/build.gradle settings.gradle
git commit -m "chore: configure Java + Kotlin mixed build for all modules"
```

---

### Task 0.3: Add ktlint Configuration

**Files:**
- Create: `.editorconfig`
- Modify: `build.gradle`

**Step 1: Create .editorconfig**

```ini
[*.{kt,kts}]
indent_size = 4
max_line_length = 120
ktlint_code_style = intellij_idea
ktlint_disabled_rules = no-wildcard-imports
```

**Step 2: Add ktlint to build.gradle**

```groovy
plugins {
    id 'org.jlleitschuh.ktlint' version '12.1.0'
}

subprojects {
    apply plugin: 'org.jlleitschuh.ktlint'

    ktlint {
        android.set(false)
        outputColorName.set('RED')
    }
}
```

**Step 3: Verify ktlint**

```bash
./gradlew ktlintCheck
```

**Step 4: Commit**

```bash
git add .editorconfig build.gradle
git commit -m "chore: add ktlint configuration for Kotlin"
```

---

### Task 0.4: Add ArchUnit Tests

**Files:**
- Create: `module-web/src/test/java/maple/expectation/arch/ModuleDependencyTest.java`

**Step 1: Write ArchUnit test**

```java
package maple.expectation.arch;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModuleDependencyTest {

    @Test
    void core_should_not_depend_on_web_or_infra() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..web..", "..infra..");

        rule.check(new ClassFileImporter().importPackages("maple.expectation"));
    }

    @Test
    void common_should_not_depend_on_spring() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..common..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.springframework..");

        rule.check(new ClassFileImporter().importPackages("maple.expectation"));
    }

    @Test
    void no_circular_dependencies() {
        ArchRule rule = slices()
            .matching("maple.expectation.(*)..")
            .should().beFreeOfCycles();

        rule.check(new ClassFileImporter().importPackages("maple.expectation"));
    }
}
```

**Step 2: Commit**

```bash
git add module-web/src/test/java/maple/expectation/arch/
git commit -m "test: add ArchUnit module dependency tests"
```

---

## Phase 1: module-web Migration (DTO → Kotlin data class)

### Task 1.1: Create Package Structure

```bash
mkdir -p module-web/src/main/java/maple/expectation/web/{controller,dto,filter,config}
mkdir -p module-web/src/main/kotlin/maple/expectation/web/{dto,model}
mkdir -p module-web/src/test/java/maple/expectation/web
mkdir -p module-web/src/test/kotlin/maple/expectation/web
```

```bash
git add module-web/src/
git commit -m "chore: create module-web package structure"
```

---

### Task 1.2: Migrate DTOs to Kotlin (Example: LoginRequest)

**Files:**
- Create: `module-web/src/main/kotlin/maple/expectation/web/dto/auth/LoginRequest.kt`
- Delete: `module-app/src/main/java/maple/expectation/controller/dto/auth/LoginRequest.java`

**Step 1: Read original Java DTO**

```bash
cat module-app/src/main/java/maple/expectation/controller/dto/auth/LoginRequest.java
```

**Step 2: Create Kotlin data class**

```kotlin
package maple.expectation.web.dto.auth

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank(message = "IGN은 필수입니다")
    val ign: String,

    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String
)
```

**Step 3: Update imports in controller**

```bash
# Find files using old import
grep -r "import maple.expectation.controller.dto.auth.LoginRequest" module-app/src --include="*.java" -l

# Update to new import
sed -i 's/import maple.expectation.controller.dto.auth.LoginRequest/import maple.expectation.web.dto.auth.LoginRequest/' \
    module-app/src/main/java/maple/expectation/controller/AuthController.java
```

**Step 4: Run tests**

```bash
./gradlew test
```

**Step 5: Delete original and commit**

```bash
rm module-app/src/main/java/maple/expectation/controller/dto/auth/LoginRequest.java
git add module-app/src module-web/src
git commit -m "feat: migrate LoginRequest to Kotlin data class"
```

---

### Task 1.3: Migrate All Auth DTOs to Kotlin

**Files:**
- Create: `module-web/src/main/kotlin/maple/expectation/web/dto/auth/*.kt`

**Pattern for all DTOs:**

```kotlin
// RefreshRequest.kt
package maple.expectation.web.dto.auth

import jakarta.validation.constraints.NotBlank

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String
)

// AddAdminRequest.kt
package maple.expectation.web.dto.auth

import jakarta.validation.constraints.NotBlank

data class AddAdminRequest(
    @field:NotBlank
    val username: String,

    @field:NotBlank
    val password: String
)
```

**Step 1: Convert all auth DTOs**
**Step 2: Update imports**
**Step 3: Run tests**
**Step 4: Delete originals**
**Step 5: Commit**

```bash
git add module-app/src module-web/src
git commit -m "feat: migrate auth DTOs to Kotlin data classes"
```

---

### Task 1.4: Migrate V4/V5 Response DTOs to Kotlin

**Files:**
- Create: `module-web/src/main/kotlin/maple/expectation/web/dto/v4/EquipmentExpectationResponseV4.kt`
- Create: `module-web/src/main/kotlin/maple/expectation/web/dto/v5/EquipmentExpectationResponseV5.kt`

**Example:**

```kotlin
package maple.expectation.web.dto.v4

import com.fasterxml.jackson.annotation.JsonProperty

data class EquipmentExpectationResponseV4(
    val characterName: String,
    val worldName: String,
    val expectations: List<PresetExpectation>
) {
    data class PresetExpectation(
        val presetName: String,
        val totalCost: Long,
        val items: List<ItemExpectation>
    ) {
        data class ItemExpectation(
            val equipmentName: String,
            val expectedCost: Long,
            val successRate: Double
        )
    }
}
```

---

### Task 1.5: Migrate Controllers (Keep Java initially)

**Files:**
- Move: `controller/*.java` → `module-web/src/main/java/maple/expectation/web/controller/`

**Pattern:**
1. Move Java controller as-is
2. Update package declaration
3. Update imports to use new Kotlin DTOs
4. Run tests
5. Commit

---

## Phase 2: module-core Migration (Business Logic)

### Task 2.1: Create Package Structure

```bash
mkdir -p module-core/src/main/java/maple/expectation/core/{application,calculator,cube,flame,starforce,policy,facade,v4,v5,monitoring,port}
mkdir -p module-core/src/main/kotlin/maple/expectation/core/{model,dto,port}
mkdir -p module-core/src/test/java/maple/expectation/core
mkdir -p module-core/src/test/kotlin/maple/expectation/core
```

---

### Task 2.2: Migrate Calculator Domain (Keep Java)

**Rationale:** Calculator logic is complex, keep Java initially.

**Files:**
- Move: `service/calculator/*` → `module-core/src/main/java/maple/expectation/core/calculator/`

**Step 1: Move and update package**
**Step 2: Update imports**
**Step 3: Run tests**
**Step 4: Commit**

---

### Task 2.3: Create Port Interfaces in Kotlin

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/*.kt`

**TokenPort.kt:**
```kotlin
package maple.expectation.core.port

interface TokenPort {
    fun generateToken(userId: Long): String
    fun validateToken(token: String): Long?
}
```

**LikeEventPublisher.kt:**
```kotlin
package maple.expectation.core.port

interface LikeEventPublisher {
    fun publish(event: LikeEvent)
}
```

---

### Task 2.4: Migrate Application DTOs to Kotlin

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/application/dto/*.kt`

**Example:**
```kotlin
package maple.expectation.core.application.dto

data class CharacterEquipmentDto(
    val equipmentName: String,
    val upgradeLevel: Int,
    val potentialGrade: String?
)
```

---

## Phase 3: module-infra Migration (Keep Java mostly)

### Task 3.1: Create Package Structure

```bash
mkdir -p module-infra/src/main/java/maple/expectation/infra/{batch,scheduler,cache,redis,mongo,nexon,discord,openai,prometheus,outbox,shutdown,auth,config}
mkdir -p module-infra/src/main/kotlin/maple/expectation/infra/adapter
```

---

### Task 3.2: Migrate Infra Components (Keep Java)

**Rationale:** Spring Batch, Redis, Mongo adapters have complex Spring integration. Keep Java, convert later.

**Pattern:**
1. Move Java files as-is
2. Update to implement Kotlin interfaces from core
3. Run tests
4. Commit

---

## Phase 4: Integration & Verification

### Task 4.1: Run Full Test Suite

```bash
./gradlew clean test
```

Expected: All tests pass

### Task 4.2: Run ArchUnit Tests

```bash
./gradlew :module-web:test --tests "maple.expectation.arch.*"
```

### Task 4.3: Run ktlint

```bash
./gradlew ktlintCheck
```

### Task 4.4: Run Chaos Tests

```bash
./gradlew test --tests "maple.expectation.chaos.nightmare.*"
```

---

## Kotlin Conversion Priority Summary

| Priority | Module | What to Convert |
|----------|--------|-----------------|
| 1 | module-web | All DTOs → data class |
| 2 | module-core | Models, Ports → Kotlin interfaces |
| 3 | module-common | All → Kotlin (small) |
| 4 | module-core | Simple services → Kotlin |
| 5 | module-infra | Keep Java (complex Spring) |

---

## Execution Summary

| Phase | Tasks | Kotlin Conversion |
|-------|-------|-------------------|
| Phase 0 | 4 | Build setup |
| Phase 1 | 5 | DTOs → data class |
| Phase 2 | 4 | Ports, Models → Kotlin |
| Phase 3 | 2 | Keep Java |
| Phase 4 | 4 | Verification |
| **Total** | **19** | ~30% Kotlin |

---

*Plan Version: 2.0.0 (Kotlin Integrated)*
*Created: 2026-02-27*
*Related Design: docs/09_Plans/2026-02-27-module-separation-design.md*
