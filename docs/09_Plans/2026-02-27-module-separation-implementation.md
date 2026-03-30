# Module Separation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate 347 Java files from module-app to 4 well-defined modules (common, core, infra, web) following sequential approach.

**Architecture:** Sequential migration (ADR → Gradle → Web → Core → Infra → Integration). Each phase must pass all tests before proceeding. Port/Adapter pattern for core-infra boundary.

**Tech Stack:** Java 21, Spring Boot 3.5.4, Gradle, ArchUnit, Testcontainers

---

## Phase 0: Foundation

### Task 0.1: Write ADR Document

**Files:**
- Create: `docs/adr/002-module-separation.md`

**Step 1: Create ADR file**

```markdown
# ADR-002: 4-Module Separation Architecture

## Status
Accepted (2026-02-27)

## Context
- module-app contains 347 Java files (all code)
- module-core/infra/web/common are empty or nearly empty
- Business logic, infra, and web layers are mixed

## Decision
Split into 4 modules:
- module-common: Pure utilities, exceptions (≤5%)
- module-core: Business rules, use cases, ports (45-55%)
- module-infra: External adapters, cache, batch (30-40%)
- module-web: HTTP layer, controllers, DTOs (10-15%)

## Consequences
- Clear dependency direction: web/infra → core → common
- Core cannot reference web or infra
- Common cannot depend on Spring
- Enables future Kotlin migration

## Related
- Issues: #409-#443
- Design: docs/09_Plans/2026-02-27-module-separation-design.md
```

**Step 2: Commit ADR**

```bash
git add docs/adr/002-module-separation.md
git commit -m "docs: ADR-002 4-Module Separation Architecture"
```

---

### Task 0.2: Configure Gradle Multi-Module Dependencies

**Files:**
- Modify: `module-common/build.gradle`
- Modify: `module-core/build.gradle`
- Modify: `module-infra/build.gradle`
- Modify: `module-web/build.gradle`
- Verify: `settings.gradle`

**Step 1: Configure module-common/build.gradle**

```groovy
dependencies {
    // Minimal dependencies only
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core'
}
```

**Step 2: Configure module-core/build.gradle**

```groovy
dependencies {
    implementation project(':module-common')

    // Pure Java dependencies only - no Spring
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.assertj:assertj-core'
    testImplementation 'org.mockito:mockito-core'
}
```

**Step 3: Configure module-infra/build.gradle**

```groovy
dependencies {
    implementation project(':module-common')
    implementation project(':module-core')

    // Spring & Infrastructure
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-batch'
    implementation 'org.redisson:redisson-spring-boot-starter:3.27.0'
    implementation 'io.github.resilience4j:resilience4j-spring-boot3'

    // External APIs
    implementation 'org.springframework.kafka:spring-kafka'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:testcontainers'
    testImplementation 'org.testcontainers:junit-jupiter'
}
```

**Step 4: Configure module-web/build.gradle**

```groovy
dependencies {
    implementation project(':module-common')
    implementation project(':module-core')
    implementation project(':module-infra')

    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
}
```

**Step 5: Verify build**

```bash
./gradlew clean build -x test
```

Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add module-common/build.gradle module-core/build.gradle module-infra/build.gradle module-web/build.gradle
git commit -m "chore: configure multi-module Gradle dependencies"
```

---

### Task 0.3: Add ArchUnit Dependency and Tests

**Files:**
- Create: `module-web/src/test/java/maple/expectation/arch/ModuleDependencyTest.java`
- Modify: `module-web/build.gradle`

**Step 1: Add ArchUnit dependency to module-web/build.gradle**

```groovy
dependencies {
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.2.1'
}
```

**Step 2: Write ArchUnit test**

```java
package maple.expectation.arch;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModuleDependencyTest {

    private static final String CORE = "..core..";
    private static final String INFRA = "..infra..";
    private static final String WEB = "..web..";
    private static final String COMMON = "..common..";
    private static final String SPRING = "org.springframework..";

    @Test
    void core_should_not_depend_on_web_or_infra() {
        ArchRule rule = noClasses()
            .that().resideInAPackage(CORE)
            .should().dependOnClassesThat()
            .resideInAnyPackage(WEB, INFRA);

        rule.check(new ClassFileImporter().importPackages("maple.expectation"));
    }

    @Test
    void common_should_not_depend_on_spring() {
        ArchRule rule = noClasses()
            .that().resideInAPackage(COMMON)
            .should().dependOnClassesThat()
            .resideInAPackage(SPRING);

        rule.check(new ClassFileImporter().importPackages("maple.expectation"));
    }

    @Test
    void no_circular_dependencies_between_modules() {
        ArchRule rule = slices()
            .matching("maple.expectation.(*)..")
            .should().beFreeOfCycles();

        rule.check(new ClassFileImporter().importPackages("maple.expectation"));
    }
}
```

**Step 3: Run test (will fail initially - no modules yet)**

```bash
./gradlew :module-web:test --tests "maple.expectation.arch.ModuleDependencyTest"
```

Expected: Test exists but may pass/fail based on current state

**Step 4: Commit**

```bash
git add module-web/build.gradle module-web/src/test/java/maple/expectation/arch/ModuleDependencyTest.java
git commit -m "test: add ArchUnit module dependency tests"
```

---

## Phase 1: module-web Migration

### Task 1.1: Create module-web Package Structure

**Files:**
- Create directories under `module-web/src/main/java/maple/expectation/web/`

**Step 1: Create package structure**

```bash
mkdir -p module-web/src/main/java/maple/expectation/web/{controller,dto,filter,config}
mkdir -p module-web/src/test/java/maple/expectation/web
```

**Step 2: Commit**

```bash
git add module-web/src/
git commit -m "chore: create module-web package structure"
```

---

### Task 1.2: Migrate Controller Classes

**Files:**
- Move: `module-app/src/main/java/maple/expectation/controller/*` → `module-web/src/main/java/maple/expectation/web/controller/`
- Update: All import statements

**Step 1: Move AdminController**

```bash
# Move file
mv module-app/src/main/java/maple/expectation/controller/AdminController.java \
   module-web/src/main/java/maple/expectation/web/controller/

# Update package declaration in file
sed -i 's/package maple.expectation.controller/package maple.expectation.web.controller/' \
   module-web/src/main/java/maple/expectation/web/controller/AdminController.java
```

**Step 2: Update imports in module-app files that reference AdminController**

```bash
# Find and update imports
grep -r "import maple.expectation.controller.AdminController" module-app/src --include="*.java" -l | \
xargs sed -i 's/import maple.expectation.controller.AdminController/import maple.expectation.web.controller.AdminController/'
```

**Step 3: Run tests**

```bash
./gradlew test
```

Expected: All tests pass

**Step 4: Repeat for remaining controllers**

Repeat Steps 1-3 for:
- AlertTestController.java
- AuthController.java
- DlqAdminController.java
- DonationController.java
- GameCharacterControllerV1.java
- GameCharacterControllerV4.java
- GameCharacterControllerV5.java

**Step 5: Commit**

```bash
git add module-app/src module-web/src
git commit -m "feat: migrate controllers to module-web"
```

---

### Task 1.3: Migrate Controller DTOs

**Files:**
- Move: `module-app/src/main/java/maple/expectation/controller/dto/*` → `module-web/src/main/java/maple/expectation/web/dto/`

**Step 1: Move DTO directories**

```bash
cp -r module-app/src/main/java/maple/expectation/controller/dto/* \
      module-web/src/main/java/maple/expectation/web/dto/
```

**Step 2: Update package declarations**

```bash
find module-web/src/main/java/maple/expectation/web/dto -name "*.java" -exec \
sed -i 's/package maple.expectation.controller.dto/package maple.expectation.web.dto/' {} \;
```

**Step 3: Update imports across all files**

```bash
grep -r "import maple.expectation.controller.dto" module-app/src --include="*.java" -l | \
xargs sed -i 's/import maple.expectation.controller.dto/import maple.expectation.web.dto/'
```

**Step 4: Run tests**

```bash
./gradlew test
```

Expected: All tests pass

**Step 5: Remove original files**

```bash
rm -rf module-app/src/main/java/maple/expectation/controller/dto/
```

**Step 6: Commit**

```bash
git add module-app/src module-web/src
git commit -m "feat: migrate controller DTOs to module-web"
```

---

### Task 1.4: Migrate Web Config Classes

**Files:**
- Move: `config/CorsProperties.java` → `module-web`
- Move: `config/OpenApiConfig.java` → `module-web`
- Move: `config/WebConfig.java` → `module-web`

**Step 1: Move and update WebConfig**

```bash
mv module-app/src/main/java/maple/expectation/config/WebConfig.java \
   module-web/src/main/java/maple/expectation/web/config/

sed -i 's/package maple.expectation.config/package maple.expectation.web.config/' \
   module-web/src/main/java/maple/expectation/web/config/WebConfig.java
```

**Step 2: Move and update CorsProperties**

```bash
mv module-app/src/main/java/maple/expectation/config/CorsProperties.java \
   module-web/src/main/java/maple/expectation/web/config/

sed -i 's/package maple.expectation.config/package maple.expectation.web.config/' \
   module-web/src/main/java/maple/expectation/web/config/CorsProperties.java
```

**Step 3: Move and update OpenApiConfig**

```bash
mv module-app/src/main/java/maple/expectation/config/OpenApiConfig.java \
   module-web/src/main/java/maple/expectation/web/config/

sed -i 's/package maple.expectation.config/package maple.expectation.web.config/' \
   module-web/src/main/java/maple/expectation/web/config/OpenApiConfig.java
```

**Step 4: Update imports**

```bash
grep -r "import maple.expectation.config" module-app/src --include="*.java" -l | \
xargs sed -i 's/import maple.expectation.config.WebConfig/import maple.expectation.web.config.WebConfig/; \
s/import maple.expectation.config.CorsProperties/import maple.expectation.web.config.CorsProperties/; \
s/import maple.expectation.config.OpenApiConfig/import maple.expectation.web.config.OpenApiConfig/'
```

**Step 5: Run tests**

```bash
./gradlew test
```

Expected: All tests pass

**Step 6: Commit**

```bash
git add module-app/src module-web/src
git commit -m "feat: migrate web config to module-web"
```

---

## Phase 2: module-core Migration

### Task 2.1: Create module-core Package Structure

**Files:**
- Create directories under `module-core/src/main/java/maple/expectation/core/`

**Step 1: Create package structure**

```bash
mkdir -p module-core/src/main/java/maple/expectation/core/{application,calculator,cube,flame,starforce,policy,facade,v4,v5,monitoring,port}
mkdir -p module-core/src/test/java/maple/expectation/core
```

**Step 2: Commit**

```bash
git add module-core/src/
git commit -m "chore: create module-core package structure"
```

---

### Task 2.2: Migrate Application Layer

**Files:**
- Move: `application/dto/*` → `module-core/src/main/java/maple/expectation/core/application/dto/`
- Move: `application/service/*` → `module-core/src/main/java/maple/expectation/core/application/service/`

**Step 1: Move application DTOs**

```bash
cp -r module-app/src/main/java/maple/expectation/application/dto/* \
      module-core/src/main/java/maple/expectation/core/application/dto/

find module-core/src/main/java/maple/expectation/core/application/dto -name "*.java" -exec \
sed -i 's/package maple.expectation.application.dto/package maple.expectation.core.application.dto/' {} \;
```

**Step 2: Move application services**

```bash
cp -r module-app/src/main/java/maple/expectation/application/service/* \
      module-core/src/main/java/maple/expectation/core/application/service/

find module-core/src/main/java/maple/expectation/core/application/service -name "*.java" -exec \
sed -i 's/package maple.expectation.application.service/package maple.expectation.core.application.service/' {} \;
```

**Step 3: Update imports across all modules**

```bash
grep -r "import maple.expectation.application" module-app/src module-web/src --include="*.java" -l | \
xargs sed -i 's/import maple.expectation.application.dto/import maple.expectation.core.application.dto/; \
s/import maple.expectation.application.service/import maple.expectation.core.application.service/'
```

**Step 4: Run tests**

```bash
./gradlew test
```

Expected: All tests pass

**Step 5: Remove originals**

```bash
rm -rf module-app/src/main/java/maple/expectation/application/
```

**Step 6: Commit**

```bash
git add module-app/src module-core/src
git commit -m "feat: migrate application layer to module-core"
```

---

### Task 2.3: Migrate Calculator Domain

**Files:**
- Move: `service/calculator/*` → `module-core/src/main/java/maple/expectation/core/calculator/`

**Step 1: Move calculator package**

```bash
cp -r module-app/src/main/java/maple/expectation/service/calculator/* \
      module-core/src/main/java/maple/expectation/core/calculator/

find module-core/src/main/java/maple/expectation/core/calculator -name "*.java" -exec \
sed -i 's/package maple.expectation.service.calculator/package maple.expectation.core.calculator/' {} \;
```

**Step 2: Update imports**

```bash
grep -r "import maple.expectation.service.calculator" module-app/src module-web/src module-core/src --include="*.java" -l | \
xargs sed -i 's/import maple.expectation.service.calculator/import maple.expectation.core.calculator/'
```

**Step 3: Run tests**

```bash
./gradlew test
```

Expected: All tests pass

**Step 4: Remove originals**

```bash
rm -rf module-app/src/main/java/maple/expectation/service/calculator/
```

**Step 5: Commit**

```bash
git add module-app/src module-core/src
git commit -m "feat: migrate calculator domain to module-core"
```

---

### Task 2.4-2.8: Migrate Remaining Core Domains

Repeat the pattern from Task 2.3 for:

- **Task 2.4**: `service/cube/*` → `module-core/src/main/java/maple/expectation/core/cube/`
- **Task 2.5**: `service/flame/*` → `module-core/src/main/java/maple/expectation/core/flame/`
- **Task 2.6**: `service/starforce/*` → `module-core/src/main/java/maple/expectation/core/starforce/`
- **Task 2.7**: `service/policy/*` → `module-core/src/main/java/maple/expectation/core/policy/`
- **Task 2.8**: `service/facade/*` → `module-core/src/main/java/maple/expectation/core/facade/`

Each task follows the same steps:
1. Copy files to new location
2. Update package declarations
3. Update imports across all modules
4. Run tests
5. Remove originals
6. Commit

---

## Phase 3: module-infra Migration

### Task 3.1: Create module-infra Package Structure

**Files:**
- Create directories under `module-infra/src/main/java/maple/expectation/infra/`

**Step 1: Create package structure**

```bash
mkdir -p module-infra/src/main/java/maple/expectation/infra/{batch,scheduler,cache,redis,mongo,nexon,discord,openai,prometheus,outbox,shutdown,auth,config}
mkdir -p module-infra/src/test/java/maple/expectation/infra
```

**Step 2: Commit**

```bash
git add module-infra/src/
git commit -m "chore: create module-infra package structure"
```

---

### Task 3.2: Migrate Batch/Scheduler

**Files:**
- Move: `batch/*` → `module-infra/src/main/java/maple/expectation/infra/batch/`
- Move: `scheduler/*` → `module-infra/src/main/java/maple/expectation/infra/scheduler/`
- Move: `BatchScheduler.java` → `module-infra`
- Move: `MonitoringReportJob.java` → `module-infra`

**Step 1: Move batch package**

```bash
cp -r module-app/src/main/java/maple/expectation/batch/* \
      module-infra/src/main/java/maple/expectation/infra/batch/

find module-infra/src/main/java/maple/expectation/infra/batch -name "*.java" -exec \
sed -i 's/package maple.expectation.batch/package maple.expectation.infra.batch/' {} \;
```

**Step 2: Move scheduler package**

```bash
cp -r module-app/src/main/java/maple/expectation/scheduler/* \
      module-infra/src/main/java/maple/expectation/infra/scheduler/

find module-infra/src/main/java/maple/expectation/infra/scheduler -name "*.java" -exec \
sed -i 's/package maple.expectation.scheduler/package maple.expectation.infra.scheduler/' {} \;
```

**Step 3: Update imports**

```bash
grep -r "import maple.expectation.batch" module-app/src module-web/src module-core/src module-infra/src --include="*.java" -l | \
xargs sed -i 's/import maple.expectation.batch/import maple.expectation.infra.batch/'

grep -r "import maple.expectation.scheduler" module-app/src module-web/src module-core/src module-infra/src --include="*.java" -l | \
xargs sed -i 's/import maple.expectation.scheduler/import maple.expectation.infra.scheduler/'
```

**Step 4: Run tests**

```bash
./gradlew test
```

Expected: All tests pass

**Step 5: Remove originals**

```bash
rm -rf module-app/src/main/java/maple/expectation/batch/
rm -rf module-app/src/main/java/maple/expectation/scheduler/
```

**Step 6: Commit**

```bash
git add module-app/src module-infra/src
git commit -m "feat: migrate batch/scheduler to module-infra"
```

---

### Task 3.3-3.7: Migrate Remaining Infra Components

Repeat the pattern for:

- **Task 3.3**: Cache implementations (`service/cache/*` impls)
- **Task 3.4**: Redis adapters (`service/like/realtime/impl/*`, `service/like/strategy/*` impls)
- **Task 3.5**: Outbox/DLQ (`service/outbox/impl/*`)
- **Task 3.6**: External API clients (Nexon, Discord, OpenAI, Prometheus)
- **Task 3.7**: Infra configs (`config/AppProperties.java`, `config/CacheConfig.java`, etc.)

---

## Phase 4: Port/Adapter Refactoring

### Task 4.1: Create Port Interfaces in module-core

**Files:**
- Create: `module-core/src/main/java/maple/expectation/core/auth/port/TokenPort.java`
- Create: `module-core/src/main/java/maple/expectation/core/auth/port/SessionPort.java`
- Create: `module-core/src/main/java/maple/expectation/core/like/port/LikeEventPublisher.java`
- Create: `module-core/src/main/java/maple/expectation/core/like/port/LikeEventSubscriber.java`

**Step 1: Create TokenPort interface**

```java
package maple.expectation.core.auth.port;

public interface TokenPort {
    String generateToken(Long userId);
    Long validateToken(String token);
}
```

**Step 2: Create SessionPort interface**

```java
package maple.expectation.core.auth.port;

public interface SessionPort {
    void createSession(Long userId, String token);
    Long validateSession(String token);
    void invalidateSession(String token);
}
```

**Step 3: Create LikeEventPublisher interface**

```java
package maple.expectation.core.like.port;

import maple.expectation.core.like.dto.LikeEvent;

public interface LikeEventPublisher {
    void publish(LikeEvent event);
}
```

**Step 4: Create LikeEventSubscriber interface**

```java
package maple.expectation.core.like.port;

import maple.expectation.core.like.dto.LikeEvent;
import java.util.function.Consumer;

public interface LikeEventSubscriber {
    void subscribe(Consumer<LikeEvent> handler);
}
```

**Step 5: Commit**

```bash
git add module-core/src/main/java/maple/expectation/core/*/port/
git commit -m "feat: create port interfaces in module-core"
```

---

### Task 4.2: Update Infra Adapters to Implement Ports

**Files:**
- Modify: `module-infra/src/main/java/maple/expectation/infra/auth/adapter/JwtTokenService.java`
- Modify: `module-infra/src/main/java/maple/expectation/infra/like/adapter/RedisLikeEventPublisher.java`

**Step 1: Update JwtTokenService**

```java
package maple.expectation.infra.auth.adapter;

import maple.expectation.core.auth.port.TokenPort;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService implements TokenPort {
    // existing implementation
    @Override
    public String generateToken(Long userId) { ... }

    @Override
    public Long validateToken(String token) { ... }
}
```

**Step 2: Update RedisLikeEventPublisher**

```java
package maple.expectation.infra.like.adapter;

import maple.expectation.core.like.port.LikeEventPublisher;
import maple.expectation.core.like.dto.LikeEvent;
import org.springframework.stereotype.Component;

@Component
public class RedisLikeEventPublisher implements LikeEventPublisher {
    @Override
    public void publish(LikeEvent event) { ... }
}
```

**Step 3: Run tests**

```bash
./gradlew test
```

Expected: All tests pass

**Step 4: Commit**

```bash
git add module-infra/src/main/java/maple/expectation/infra/
git commit -m "refactor: infra adapters implement core ports"
```

---

## Phase 5: Integration & Verification

### Task 5.1: Run Full Test Suite

**Step 1: Run all tests**

```bash
./gradlew clean test
```

Expected: All 479+ tests pass

**Step 2: Run ArchUnit tests**

```bash
./gradlew :module-web:test --tests "maple.expectation.arch.*"
```

Expected: All architecture tests pass

---

### Task 5.2: Run Chaos Tests

**Step 1: Run Nightmare scenarios**

```bash
./gradlew test --tests "maple.expectation.chaos.nightmare.*"
```

Expected: N01-N18 all pass

---

### Task 5.3: Update Documentation

**Files:**
- Modify: `CLAUDE.md` (add module structure section)
- Modify: `README.md` (add module description)
- Modify: `docs/00_Start_Here/architecture.md` (update diagrams)

**Step 1: Update CLAUDE.md**

Add section after Tech Stack:
```markdown
## Module Structure

| Module | Responsibility | Dependencies |
|--------|---------------|--------------|
| module-common | Pure utilities, exceptions | None |
| module-core | Business rules, ports | common |
| module-infra | External adapters | core, common |
| module-web | HTTP layer | core, infra, common |

**Dependency Rule:** core cannot reference web or infra.
```

**Step 2: Commit**

```bash
git add CLAUDE.md README.md docs/
git commit -m "docs: update module structure documentation"
```

---

### Task 5.4: Final Cleanup

**Step 1: Verify module-app is empty or minimal**

```bash
find module-app/src/main/java -name "*.java" | wc -l
```

Expected: Only ExpectationApplication.java remains

**Step 2: Run final verification**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL

**Step 3: Final commit**

```bash
git add -A
git commit -m "feat: complete module separation

- Migrated 347 files from module-app to 4 modules
- module-web: Controllers, DTOs, Filters, Web Config
- module-core: Business logic, Calculators, Ports
- module-infra: Cache, Redis, Mongo, External APIs
- module-common: Pure utilities, exceptions

All 479+ tests pass
ArchUnit verification pass
Chaos tests pass

Closes #409
"
```

---

## Execution Summary

| Phase | Tasks | Estimated PRs |
|-------|-------|---------------|
| Phase 0 | 3 | 3 |
| Phase 1 | 4 | 4 |
| Phase 2 | 8 | 8 |
| Phase 3 | 7 | 7 |
| Phase 4 | 2 | 2 |
| Phase 5 | 4 | 4 |
| **Total** | **28** | **28** |

---

*Plan Version: 1.0.0*
*Created: 2026-02-27*
*Related Design: docs/09_Plans/2026-02-27-module-separation-design.md*
