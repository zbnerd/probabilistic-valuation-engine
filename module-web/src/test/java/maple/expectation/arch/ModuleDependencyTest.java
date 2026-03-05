package maple.expectation.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Module dependency enforcement tests for module-web.
 *
 * <p><strong>Multi-Module Structure (ADR-005, ADR-014):</strong>
 *
 * <pre>
 * module-web      (Presentation layer: DTOs, Controllers, GlobalExceptionHandler)
 *     ↓ depends on
 * module-core     (Domain ports/interfaces)
 *     ↓
 * module-common   (Shared utilities, error handling)
 *
 * module-app      (Application layer: Services, Use Cases)
 *     ↓ depends on
 * module-infra    (Infrastructure: Repositories, External APIs)
 *     ↓
 * module-core
 *     ↓
 * module-common
 * </pre>
 *
 * <p><strong>PHASE 6 CRITICAL VIOLATION:</strong> module-app → module-web (20+ imports)
 *
 * <ul>
 *   <li>CubeCalculationInput, EquipmentExpectationResponseV4
 *   <li>LoginRequest, LoginResponse, TokenResponse
 *   <li>DlqDetailResponse, DlqEntryResponse, DlqReprocessResult
 * </ul>
 *
 * <p><strong>Action Required:</strong> Move DTOs from module-web to module-app or create
 * module-dto.
 */
@DisplayName("Module Dependency Enforcement (Phase 6)")
class ModuleDependencyTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation");

  @Nested
  @DisplayName("Dependency Direction Rules")
  class DependencyDirectionTests {

    /**
     * Rule 1: module-core should not depend on web or infra.
     *
     * <p>Core is the foundation layer and must remain independent.
     */
    @Test
    @DisplayName("core should not depend on web or infra")
    void core_should_not_depend_on_web_or_infra() {
      ArchRule rule =
          noClasses()
              .that()
              .resideInAPackage("..core..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage("..web..", "..infra..", "..infrastructure..", "..application..")
              .because(
                  "Core module must remain independent of application/infrastructure layers (DIP)")
              .allowEmptyShould(true);

      rule.check(classes);
    }

    /**
     * Rule 2: module-common should not depend on Spring Web.
     *
     * <p>Common must be framework-agnostic.
     */
    @Test
    @DisplayName("common should not depend on Spring framework")
    void common_should_not_depend_on_spring() {
      ArchRule rule =
          noClasses()
              .that()
              .resideInAPackage("..common..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "org.springframework.web..",
                  "org.springframework.boot..web..",
                  "org.springframework.data..web..")
              .because("Common module must be framework-agnostic and free of web dependencies")
              .allowEmptyShould(true);

      rule.check(classes);
    }

    /**
     * Rule 3: module-infra should not depend on app services.
     *
     * <p>Infrastructure implements interfaces defined in core (DIP).
     */
    @Test
    @DisplayName("infra should not depend on app services")
    void infra_should_not_depend_on_app_services() {
      ArchRule rule =
          noClasses()
              .that()
              .resideInAPackage("..infra..")
              .or()
              .resideInAPackage("..infrastructure..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "..service.v2..",
                  "..service.v4..",
                  "..service.v5..",
                  "..controller..",
                  "..application.service..")
              .because("Infrastructure must not depend on application layer (DIP violation)")
              .allowEmptyShould(true);

      rule.check(classes);
    }

    /**
     * Rule 4: module-app should NOT depend on module-web (PHASE 6 VIOLATION).
     *
     * <p><strong>CRITICAL ARCHITECTURAL VIOLATION:</strong> module-app has 20+ imports from
     * module-web.dto:
     *
     * <ul>
     *   <li>CubeCalculationInput, EquipmentCalculationInput
     *   <li>EquipmentExpectationResponseV4, PresetExpectation
     *   <li>LoginRequest, LoginResponse, TokenResponse, RefreshRequest
     *   <li>DlqDetailResponse, DlqEntryResponse, DlqReprocessResult
     *   <li>CursorPageRequest, CursorPageResponse
     * </ul>
     *
     * <p><strong>Impact:</strong> This violates the dependency inversion principle and creates a
     * reverse dependency where application layer depends on presentation layer.
     *
     * <p><strong>Refactoring Required:</strong>
     *
     * <ol>
     *   <li>Option A: Move DTOs from module-web.dto to module-app.dto
     *   <li>Option B: Create module-dto for shared DTOs
     *   <li>Option C: Use interfaces in core module, keep DTOs in web
     * </ol>
     */
    @Test
    @DisplayName(
        "app should NOT depend on web (PHASE 6 VIOLATION - 20+ imports from module-web.dto)")
    void app_should_not_depend_on_web() {
      ArchRule rule =
          noClasses()
              .that()
              .resideInAPackage("..application..")
              .or()
              .resideInAPackage("..service..")
              .or()
              .resideInAPackage("..parser..")
              .or()
              .resideInAPackage("..worker..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("..web..")
              .because(
                  """
                  Application layer must not depend on web layer.
                  Web DTOs should be in module-app or separate module-dto.
                  Current: 20+ imports from module-web.dto (PHASE 6 TECHNICAL DEBT)

                  Violating imports:
                  - CubeCalculationInput (used in CubeServiceImpl, Calculator)
                  - EquipmentExpectationResponseV4 (used in V4 services)
                  - LoginRequest/Response (used in AuthService)
                  - Dlq*Response (used in DlqAdminService)
                  """)
              .allowEmptyShould(true);

      rule.check(classes);
    }
  }

  @Nested
  @DisplayName("Circular Dependency Detection")
  class CircularDependencyTests {

    /**
     * Rule 5: No circular dependencies between modules.
     *
     * <p>Uses ArchUnit's slices() API to detect dependency cycles.
     */
    @Test
    @DisplayName("no circular dependencies between modules")
    void no_circular_dependencies() {
      ArchRule rule =
          slices()
              .matching("maple.expectation.(*)..")
              .should()
              .beFreeOfCycles()
              .because(
                  "Circular dependencies violate clean architecture and prevent modular deployment");

      rule.check(classes);
    }

    /** Rule 6: Core module should not create dependency cycles. */
    @Test
    @DisplayName("core should not create dependency cycles")
    void core_should_not_create_cycles() {
      ArchRule rule =
          noClasses()
              .that()
              .resideInAPackage("..core..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("..infra..")
              .orShould()
              .dependOnClassesThat()
              .resideInAPackage("..web..")
              .orShould()
              .dependOnClassesThat()
              .resideInAPackage("..application..")
              .because("Core module is the foundation - any reverse dependency creates a cycle")
              .allowEmptyShould(true);

      rule.check(classes);
    }
  }

  @Nested
  @DisplayName("Cross-Module Boundary Validation")
  class CrossModuleTests {

    /**
     * Rule 7: Web module should not access application service implementations.
     *
     * <p>Controllers should use port interfaces, not service implementations.
     */
    @Test
    @DisplayName("web should not access app service implementations directly")
    void web_should_not_access_app_services_directly() {
      ArchRule rule =
          noClasses()
              .that()
              .resideInAPackage("..web..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("..application.service..")
              .orShould()
              .dependOnClassesThat()
              .resideInAPackage("..service.v2..")
              .orShould()
              .dependOnClassesThat()
              .resideInAPackage("..service.v4..")
              .because(
                  """
                  Web layer should use port interfaces, not service implementations.
                  Controllers depend on UseCase/Port interfaces from core module.
                  Application layer adapters are injected at runtime by Spring DI.
                  """)
              .allowEmptyShould(true);

      rule.check(classes);
    }
  }
}
