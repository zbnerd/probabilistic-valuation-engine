package maple.expectation.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Core module purity enforcement tests.
 *
 * <p><strong>Purpose:</strong> Ensure module-core remains framework-agnostic and contains only pure
 * domain logic, free from Spring, JPA, or infrastructure dependencies.
 *
 * <p><strong>ADR Reference:</strong>
 *
 * <ul>
 *   <li>ADR-017: Pure Domain Model
 *   <li>ADR-014: Multi-module cross-cutting concerns
 *   <li>Issue #489: Core module purity verification
 * </ul>
 *
 * <p><strong>Core Principles:</strong>
 *
 * <ul>
 *   <li>Core module must be Spring-free (no @Component, @Service, etc.)
 *   <li>Core module must not depend on persistence frameworks (JPA, Hibernate)
 *   <li>Core module must not depend on infrastructure utilities (LogicExecutor)
 *   <li>Core module contains pure business logic and domain models
 * </ul>
 *
 * <p><strong>Dependency Direction:</strong>
 *
 * <pre>
 * module-app → module-infra → module-core → module-common
 * </pre>
 *
 * Core module should only depend on module-common.
 *
 * @see <a href="https://www.archunit.org/">ArchUnit Documentation</a>
 */
@DisplayName("Core Module Purity Rules (Issue #489)")
public class CoreDependencyRuleTest {

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation.core");

  // ========================================
  // Rule 1: Spring-Free Core Module
  // ========================================

  @Nested
  @DisplayName("Core module must not depend on Spring annotations")
  class SpringAnnotationRules {

    /**
     * Core module should not depend on Spring stereotype annotations.
     *
     * <p><strong>Rationale:</strong> Core domain logic must be framework-agnostic. Spring
     * annotations (@Component, @Service, @Repository, @Controller) create tight coupling to the
     * Spring framework and make core logic difficult to test without Spring context.
     *
     * <p><strong>Allowed:</strong> Pure Java/Kotlin classes, interfaces, records.
     *
     * <p><strong>Forbidden:</strong> @Component, @Service, @Repository, @Controller, @Configuration.
     */
    @Test
    @DisplayName("Core should not depend on Spring stereotype annotations")
    void coreShouldNotDependOnSpringStereotypeAnnotations() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.stereotype..")
          .because(
              """
              Core module must be Spring-free.
              Spring stereotype annotations (@Component, @Service, @Repository, @Controller)
              create framework coupling and make core logic difficult to test without Spring context.
              Use dependency injection in module-app or module-infra instead.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on Spring Web annotations.
     *
     * <p><strong>Rationale:</strong> HTTP concerns belong in the web/application layer. Core domain
     * logic should not know about REST, controllers, or HTTP endpoints.
     *
     * <p><strong>Allowed:</strong> Pure domain models, ports/interfaces.
     *
     * <p><strong>Forbidden:</strong> @RestController, @RequestMapping, @GetMapping, @PostMapping.
     */
    @Test
    @DisplayName("Core should not depend on Spring Web annotations")
    void coreShouldNotDependOnSpringWebAnnotations() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.web.bind.annotation..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.web..")
          .because(
              """
              Core module must be protocol-agnostic.
              Spring Web annotations (@RestController, @RequestMapping, @GetMapping, @PostMapping)
              belong in module-web or module-app controllers.
              Core should define ports/interfaces, not HTTP endpoints.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on Spring framework classes.
     *
     * <p><strong>Rationale:</strong> Core domain logic should be reusable in different contexts
     * (CLI, batch, different frameworks). Spring dependencies bind core logic to Spring ecosystem.
     *
     * <p><strong>Allowed:</strong> Java/Kotlin standard library, Jackson (JSON serialization).
     *
     * <p><strong>Forbidden:</strong> ApplicationContext, BeanFactory, SpringApplication, etc.
     */
    @Test
    @DisplayName("Core should not depend on Spring framework classes")
    void coreShouldNotDependOnSpringFramework() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..")
          .because(
              """
              Core module must be framework-agnostic.
              Spring framework dependencies (ApplicationContext, BeanFactory, etc.)
              make core logic difficult to reuse outside Spring applications.
              Spring dependencies belong in module-app or module-infra.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 2: Persistence-Free Core Module
  // ========================================

  @Nested
  @DisplayName("Core module must not depend on persistence frameworks")
  class PersistenceFreeRules {

    /**
     * Core module should not depend on JPA annotations.
     *
     * <p><strong>Rationale:</strong> Domain models should be persistence-agnostic. JPA annotations
     * (@Entity, @Table, @Column, @OneToMany, etc.) mix persistence concerns with domain logic.
     *
     * <p><strong>Allowed:</strong> Pure domain models (POJOs, records, Kotlin data classes).
     *
     * <p><strong>Forbidden:</strong> @Entity, @Table, @Column, @OneToMany, @ManyToOne, @JoinColumn.
     */
    @Test
    @DisplayName("Core should not depend on JPA annotations")
    void coreShouldNotDependOnJpaAnnotations() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("jakarta.persistence..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("javax.persistence..")
          .because(
              """
              Core domain models must be persistence-agnostic.
              JPA annotations (@Entity, @Table, @Column, @OneToMany, @ManyToOne)
              create coupling to database schema and make domain models difficult to reuse.
              JPA entities belong in module-infra persistence layer.
              Core should define pure domain models (POJOs, records, data classes).
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on Hibernate.
     *
     * <p><strong>Rationale:</strong> Hibernate is a specific JPA implementation. Core domain logic
     * should not depend on any persistence implementation details.
     *
     * <p><strong>Allowed:</strong> Pure domain logic.
     *
     * <p><strong>Forbidden:</strong> Session, EntityManager, Hibernate-specific types.
     */
    @Test
    @DisplayName("Core should not depend on Hibernate")
    void coreShouldNotDependOnHibernate() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.hibernate..")
          .because(
              """
              Core module must be persistence-agnostic.
              Hibernate is a specific JPA implementation.
              Core domain logic should not depend on persistence implementation details.
              Hibernate dependencies belong in module-infra persistence layer.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on JDBC or SQL.
     *
     * <p><strong>Rationale:</strong> Database access concerns belong in infrastructure layer. Core
     * domain logic should not know about JDBC, Connection, ResultSet, or SQL.
     *
     * <p><strong>Allowed:</strong> Repository interfaces (ports).
     *
     * <p><strong>Forbidden:</strong> Connection, Statement, ResultSet, DataSource.
     */
    @Test
    @DisplayName("Core should not depend on JDBC or SQL")
    void coreShouldNotDependOnJdbcOrSql() {
      noClasses()
          .that()
          .resideInAPackage("..core.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.sql..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("javax.sql..")
          .because(
              """
              Core domain logic must not depend on database access APIs.
              JDBC (Connection, Statement, ResultSet) belongs in infrastructure layer.
              Core should define repository interfaces (ports), not JDBC implementations.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 3: Infrastructure-Free Core Module
  // ========================================

  @Nested
  @DisplayName("Core module must not depend on infrastructure utilities")
  class InfrastructureFreeRules {

    /**
     * Core module should not depend on LogicExecutor.
     *
     * <p><strong>Rationale:</strong> LogicExecutor is an infrastructure utility for error handling
     * and execution flow. Core domain logic should not depend on infrastructure execution patterns.
     *
     * <p><strong>Exception:</strong> None. Core should contain pure business logic only.
     *
     * <p><strong>ADR-039 Note:</strong> application.service temporarily depends on
     * infrastructure.executor.LogicExecutor as Phase 2-3 technical debt. However, core.domain must
     * remain completely free of LogicExecutor dependency.
     */
    @Test
    @DisplayName("Core should not depend on LogicExecutor")
    void coreShouldNotDependOnLogicExecutor() {
      noClasses()
          .that()
          .resideInAPackage("..core.domain..")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("LogicExecutor")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("TaskContext")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("CheckedLogicExecutor")
          .because(
              """
              Core domain logic must not depend on infrastructure execution utilities.
              LogicExecutor, TaskContext, and CheckedLogicExecutor are infrastructure utilities
              for error handling and execution flow control.
              Core should contain pure business logic only, without infrastructure dependencies.
              Infrastructure dependencies belong in module-app or module-infra.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on Redis or caching frameworks.
     *
     * <p><strong>Rationale:</strong> Caching is a cross-cutting concern that belongs in
     * infrastructure layer. Core domain logic should not know about cache implementation.
     *
     * <p><strong>Allowed:</strong> Pure domain logic with cache-agnostic interfaces.
     *
     * <p><strong>Forbidden:</strong> Redisson, RedisTemplate, @Cacheable, CacheManager.
     */
    @Test
    @DisplayName("Core should not depend on Redis or caching frameworks")
    void coreShouldNotDependOnRedisOrCaching() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.redisson..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.data.redis..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.cache..")
          .because(
              """
              Core module must be cache-agnostic.
              Caching is an infrastructure concern that belongs in module-app or module-infra.
              Core domain logic should not depend on Redis, Redisson, or Spring Cache abstractions.
              Use cache-agnostic interfaces in core, cache implementations in infrastructure.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on external API clients.
     *
     * <p><strong>Rationale:</strong> External API integration is an infrastructure concern. Core
     * should define port interfaces, not concrete client implementations.
     *
     * <p><strong>Allowed:</strong> Port interfaces (e.g., NexonApiOutboxProcessorPort).
     *
     * <p><strong>Forbidden:</strong> WebClient, RestTemplate, HttpClient, external client impls.
     */
    @Test
    @DisplayName("Core should not depend on external API clients")
    void coreShouldNotDependOnExternalApiClients() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.web.client..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("java.net.http..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("okhttp3..")
          .because(
              """
              Core module must not depend on external API clients.
              HTTP client dependencies (WebClient, RestTemplate, HttpClient)
              belong in infrastructure layer.
              Core should define port interfaces (e.g., NexonApiOutboxProcessorPort).
              Infrastructure implements these ports with concrete HTTP clients.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 4: Annotation Usage Restrictions
  // ========================================

  @Nested
  @DisplayName("Core module should not use framework annotations")
  class AnnotationUsageRules {

    /**
     * Core module should not use @Component annotation.
     *
     * <p><strong>Rationale:</strong> @Component is a Spring stereotype annotation that marks
     * classes for Spring's component scanning. Core classes should not be Spring beans.
     *
     * <p><strong>Allowed:</strong> Plain Java/Kotlin classes without Spring annotations.
     *
     * <p><strong>Forbidden:</strong> @Component, @Service, @Repository, @Controller.
     */
    @Test
    @DisplayName("Core should not use @Component annotation")
    void coreShouldNotUseComponentAnnotation() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .beMetaAnnotatedWith("org.springframework.stereotype.Component")
          .because(
              """
              Core module must not use Spring @Component annotation.
              @Component marks classes for Spring's component scanning and dependency injection.
              Core classes should be plain Java/Kotlin objects, not Spring beans.
              Spring annotations belong in module-app or module-infra.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not use @Cacheable annotation.
     *
     * <p><strong>Rationale:</strong> @Cacheable is a Spring caching annotation. Caching
     * configuration belongs in infrastructure layer, not core domain logic.
     *
     * <p><strong>Allowed:</strong> Plain methods without caching annotations.
     *
     * <p><strong>Forbidden:</strong> @Cacheable, @CacheEvict, @CachePut.
     */
    @Test
    @DisplayName("Core should not use @Cacheable annotation")
    void coreShouldNotUseCacheableAnnotation() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .beMetaAnnotatedWith("org.springframework.cache.annotation.Cacheable")
          .orShould()
          .beMetaAnnotatedWith("org.springframework.cache.annotation.CacheEvict")
          .orShould()
          .beMetaAnnotatedWith("org.springframework.cache.annotation.CachePut")
          .because(
              """
              Core module must not use Spring caching annotations.
              @Cacheable, @CacheEvict, @CachePut are Spring-specific caching concerns.
              Caching configuration belongs in infrastructure layer.
              Core methods should be pure functions without caching concerns.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not use @Repository annotation.
     *
     * <p><strong>Rationale:</strong> @Repository is a Spring stereotype for persistence components.
     * Core module should not contain Spring-managed repository beans.
     *
     * <p><strong>Allowed:</strong> Repository interfaces (ports) without Spring annotations.
     *
     * <p><strong>Forbidden:</strong> @Repository on implementation classes.
     */
    @Test
    @DisplayName("Core should not use @Repository annotation")
    void coreShouldNotUseRepositoryAnnotation() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .beMetaAnnotatedWith("org.springframework.stereotype.Repository")
          .because(
              """
              Core module must not use Spring @Repository annotation.
              @Repository marks Spring-managed persistence components.
              Core should define repository interfaces (ports) without Spring annotations.
              Repository implementations with @Repository belong in module-infra.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not use @Service annotation.
     *
     * <p><strong>Rationale:</strong> @Service is a Spring stereotype for service layer components.
     * Core domain services should be plain classes/functions, not Spring beans.
     *
     * <p><strong>Allowed:</strong> Plain domain services (pure functions).
     *
     * <p><strong>Forbidden:</strong> @Service annotation on core classes.
     */
    @Test
    @DisplayName("Core should not use @Service annotation")
    void coreShouldNotUseServiceAnnotation() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .beMetaAnnotatedWith("org.springframework.stereotype.Service")
          .because(
              """
              Core module must not use Spring @Service annotation.
              @Service marks Spring-managed service layer components.
              Core domain services should be plain classes or pure functions.
              Spring service implementations belong in module-app or module-infra.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }

  // ========================================
  // Rule 5: Dependency Direction Enforcement
  // ========================================

  @Nested
  @DisplayName("Core module must only depend on module-common")
  class DependencyDirectionRules {

    /**
     * Core module should not depend on application layer.
     *
     * <p><strong>Rationale:</strong> Application layer orchestrates business logic. Core domain
     * logic should not depend on application services.
     *
     * <p><strong>Dependency Direction:</strong> app → core (correct), core → app (forbidden).
     */
    @Test
    @DisplayName("Core should not depend on application layer")
    void coreShouldNotDependOnApplicationLayer() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..application..")
          .because(
              """
              Core module must not depend on application layer.
              Application layer orchestrates business logic and depends on core.
              Reverse dependency (core → application) violates layered architecture.
              Dependency direction must be: application → core → common.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on infrastructure layer.
     *
     * <p><strong>Rationale:</strong> Infrastructure layer implements core interfaces. Core should
     * not depend on infrastructure implementations (Dependency Inversion Principle).
     *
     * <p><strong>Dependency Direction:</strong> infra → core (correct), core → infra (forbidden).
     */
    @Test
    @DisplayName("Core should not depend on infrastructure layer")
    void coreShouldNotDependOnInfrastructureLayer() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..")
          .because(
              """
              Core module must not depend on infrastructure layer.
              Infrastructure implements core interfaces (Dependency Inversion Principle).
              Core defines ports/interfaces, infrastructure provides implementations.
              Dependency direction must be: infrastructure → core → common.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }

    /**
     * Core module should not depend on web layer.
     *
     * <p><strong>Rationale:</strong> Web layer handles HTTP concerns. Core domain logic should be
     * completely unaware of web protocols or endpoints.
     *
     * <p><strong>Dependency Direction:</strong> web → core (correct), core → web (forbidden).
     */
    @Test
    @DisplayName("Core should not depend on web layer")
    void coreShouldNotDependOnWebLayer() {
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..web..")
          .because(
              """
              Core module must not depend on web layer.
              Web layer handles HTTP concerns and should depend on core ports.
              Core domain logic must be completely unaware of web protocols.
              Dependency direction must be: web → core → common.
              """)
          .allowEmptyShould(true)
          .check(classes);
    }
  }
}
