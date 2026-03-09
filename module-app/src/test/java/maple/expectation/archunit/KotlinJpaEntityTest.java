package maple.expectation.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kotlin JPA Entity Architecture Rules (P1-8)
 *
 * <h3>Rule: JPA Entities Must Not Be Data Classes</h3>
 *
 * <p>Kotlin data classes break Hibernate proxy generation and lazy loading because:
 *
 * <ul>
 *   <li>Data classes are final by default (cannot be proxied)
 *   <li>equals/hashCode based on all properties (breaks lazy loading checks)
 *   <li>copy() method can bypass entity lifecycle
 * </ul>
 *
 * <h3>Correct Pattern (see EventOutbox.kt, EquipmentExpectationSummary.kt)</h3>
 *
 * <pre>
 * &#64;Entity
 * class MyEntity {  // Regular class, NOT data class
 *     &#64;Id var id: Long? = null
 *     var field: String? = null
 *         private set  // Private setters for encapsulation
 *
 *     private constructor()
 *
 *     companion object {
 *         fun create(...): MyEntity { ... }
 *     }
 * }
 * </pre>
 *
 * @see maple.expectation.domain.v2.EventOutbox
 * @see maple.expectation.domain.v2.EquipmentExpectationSummary
 */
@DisplayName("Kotlin JPA Entity Rules (P1-8)")
class KotlinJpaEntityTest {

  private final com.tngtech.archunit.core.domain.JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
          .importPackages("maple.expectation");

  /**
   * JPA entities should not be Kotlin data classes.
   *
   * <p>This test checks for patterns that indicate data class usage:
   *
   * <ul>
   *   <li>Classes ending with "Data" suffix (common data class naming)
   *   <li>Java records (also problematic for JPA)
   * </ul>
   *
   * <p>Note: Direct data class detection via ArchUnit is limited, so this test uses naming
   * conventions as a heuristic. Manual code review is still required.
   */
  @Test
  @DisplayName("JPA entities should not use data class patterns")
  void jpaEntitiesShouldNotBeDataClasses() {
    noClasses()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .haveSimpleNameEndingWith("Data")
        .because(
            """
                JPA entities must not be Kotlin data classes or have 'Data' suffix.
                Data classes break Hibernate proxy generation and lazy loading.
                Use regular classes with private setters instead (see EventOutbox.kt pattern).
                """)
        .allowEmptyShould(true)
        .check(classes);
  }

  /**
   * JPA entities should not be Java records.
   *
   * <p>Java records have the same issues as Kotlin data classes: final by default, equals/hashCode
   * based on all fields.
   */
  @Test
  @DisplayName("JPA entities should not be Java records")
  void jpaEntitiesShouldNotBeRecords() {
    noClasses()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .beRecords()
        .because(
            """
                JPA entities must not be Java records.
                Records are final and cannot be proxied by Hibernate.
                Use regular classes with @Id fields instead.
                """)
        .allowEmptyShould(true)
        .check(classes);
  }
}
