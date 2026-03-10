package architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Port signature enforcement tests.
 *
 * <p><strong>Core Principle:</strong> Port interfaces (inbound/outbound) must be
 * framework-agnostic. They should only use domain types, not Spring/Servlet/HTTP framework types.
 *
 * <p><strong>References:</strong>
 *
 * <ul>
 *   <li>docs/adr/ADR-004-port-based-architecture.md - Port-Based Architecture
 *   <li>CLAUDE.md - Section: Clean Architecture & DIP
 *   <li>Issue #500 - Create ArchUnit test to prevent forbidden types in Port interfaces
 * </ul>
 *
 * <p><strong>Forbidden Types:</strong>
 *
 * <ul>
 *   <li>{@code org.springframework.http.ResponseEntity} - Use domain DTOs instead
 *   <li>{@code org.springframework.data.domain.Pageable} - Use domain pagination types
 *   <li>{@code jakarta.servlet.http.HttpServletRequest} - Use domain request objects
 *   <li>{@code jakarta.servlet.http.HttpServletResponse} - Use domain response objects
 * </ul>
 *
 * <p><strong>Rationale:</strong> Port interfaces define the boundary between the core domain and
 * external systems. Using framework types creates tight coupling to specific technologies and
 * violates the Dependency Inversion Principle (DIP). This prevents:
 *
 * <ul>
 *   <li>Framework lock-in (cannot switch from Spring to another framework)
 *   <li>Testability issues (requires mock HTTP infrastructure)
 *   <li>Violation of Clean Architecture (domain depends on infrastructure concerns)
 * </ul>
 */
@DisplayName("Port Signature: Framework-Agnostic Interfaces")
class PortSignatureRuleTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation.core");

  // ========================================
  // Rule 1: No ResponseEntity in Port Methods
  // ========================================

  @Nested
  @DisplayName("ResponseEntity: Not Allowed in Port Interfaces")
  class ResponseEntityTests {

    /**
     * Port interfaces must not use ResponseEntity.
     *
     * <p><strong>Problem:</strong> ResponseEntity is a Spring framework type that couples the port
     * to HTTP concerns.
     *
     * <p><strong>Solution:</strong> Return domain DTOs directly. Let the adapter layer handle HTTP
     * response wrapping.
     */
    @Test
    @DisplayName("Port interfaces should not use ResponseEntity")
    void portsShouldNotUseResponseEntity() {
      noClasses()
          .that()
          .resideInAPackage("..port..")
          .and()
          .areInterfaces()
          .should()
          .dependOnClassesThat()
          .areAssignableTo("org.springframework.http.ResponseEntity")
          .because(
              """
                            Port interfaces must be framework-agnostic.
                            ResponseEntity couples the port to Spring HTTP concerns.
                            Use domain DTOs instead. Let adapters handle HTTP wrapping.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 2: No Pageable in Port Methods
  // ========================================

  @Nested
  @DisplayName("Pageable: Not Allowed in Port Interfaces")
  class PageableTests {

    /**
     * Port interfaces must not use Spring Data Pageable.
     *
     * <p><strong>Problem:</strong> Pageable is a Spring Data type that couples the port to Spring's
     * pagination abstraction.
     *
     * <p><strong>Solution:</strong> Define domain pagination types (e.g., PageRequest, PageResult)
     * in the core module.
     */
    @Test
    @DisplayName("Port interfaces should not use Pageable")
    void portsShouldNotUsePageable() {
      noClasses()
          .that()
          .resideInAPackage("..port..")
          .and()
          .areInterfaces()
          .should()
          .dependOnClassesThat()
          .areAssignableTo("org.springframework.data.domain.Pageable")
          .because(
              """
                            Port interfaces must be framework-agnostic.
                            Pageable couples the port to Spring Data pagination.
                            Define domain pagination types in core module instead.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 3: No HttpServletRequest/Response in Port Methods
  // ========================================

  @Nested
  @DisplayName("Servlet Types: Not Allowed in Port Interfaces")
  class ServletTypesTests {

    /**
     * Port interfaces must not use HttpServletRequest.
     *
     * <p><strong>Problem:</strong> HttpServletRequest is a Servlet API type that couples the port
     * to HTTP request handling.
     *
     * <p><strong>Solution:</strong> Extract required data into domain request objects in the
     * controller/adapter layer.
     */
    @Test
    @DisplayName("Port interfaces should not use HttpServletRequest")
    void portsShouldNotUseHttpServletRequest() {
      noClasses()
          .that()
          .resideInAPackage("..port..")
          .and()
          .areInterfaces()
          .should()
          .dependOnClassesThat()
          .areAssignableTo("jakarta.servlet.http.HttpServletRequest")
          .because(
              """
                            Port interfaces must be framework-agnostic.
                            HttpServletRequest couples the port to Servlet API.
                            Extract required data into domain request objects instead.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Port interfaces must not use HttpServletResponse.
     *
     * <p><strong>Problem:</strong> HttpServletResponse is a Servlet API type that couples the port
     * to HTTP response handling.
     *
     * <p><strong>Solution:</strong> Return domain response objects. Let the controller handle HTTP
     * response building.
     */
    @Test
    @DisplayName("Port interfaces should not use HttpServletResponse")
    void portsShouldNotUseHttpServletResponse() {
      noClasses()
          .that()
          .resideInAPackage("..port..")
          .and()
          .areInterfaces()
          .should()
          .dependOnClassesThat()
          .areAssignableTo("jakarta.servlet.http.HttpServletResponse")
          .because(
              """
                            Port interfaces must be framework-agnostic.
                            HttpServletResponse couples the port to Servlet API.
                            Return domain response objects instead.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 4: No javax.servlet Types (Legacy)
  // ========================================

  @Nested
  @DisplayName("Legacy Servlet Types: Not Allowed in Port Interfaces")
  class LegacyServletTypesTests {

    /**
     * Port interfaces must not use legacy javax.servlet types.
     *
     * <p><strong>Problem:</strong> javax.servlet is the pre-Jakarta EE namespace. While deprecated,
     * some older dependencies may still use it.
     */
    @Test
    @DisplayName("Port interfaces should not use javax.servlet types")
    void portsShouldNotUseJavaxServlet() {
      noClasses()
          .that()
          .resideInAPackage("..port..")
          .and()
          .areInterfaces()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("javax.servlet..")
          .because(
              """
                            Port interfaces must be framework-agnostic.
                            javax.servlet types are legacy Servlet API.
                            Use domain request/response objects instead.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Summary: Combined Check
  // ========================================

  @Nested
  @DisplayName("Summary: All Forbidden Types Check")
  class SummaryTests {

    /**
     * Combined check for all forbidden types in port interfaces.
     *
     * <p>This provides a single test that catches all violations at once, useful for CI/CD
     * pipelines.
     */
    @Test
    @DisplayName("Port interfaces should not depend on any forbidden framework types")
    void portsShouldNotDependOnForbiddenTypes() {
      noClasses()
          .that()
          .resideInAPackage("..port..")
          .and()
          .areInterfaces()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.http..",
              "org.springframework.data.domain..",
              "jakarta.servlet..",
              "javax.servlet..")
          .because(
              """
                            Port interfaces must be framework-agnostic for Clean Architecture compliance.
                            Forbidden: ResponseEntity, Pageable, HttpServletRequest, HttpServletResponse.
                            Use domain types only. Adapters handle framework-specific concerns.
                            """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }
}
