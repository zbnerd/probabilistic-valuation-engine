package maple.expectation.archunit;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * ArchUnit test for enforcing explicit transaction manager binding in multi-datasource environment.
 *
 * <p><strong>ADR-013:</strong> Multi-DataSource Transaction Strategy
 *
 * <p>When multiple transaction managers exist (MySQL + MongoDB), Spring requires explicit
 * qualifiers to resolve ambiguity. All @Transactional annotations MUST specify "transactionManager"
 * for JPA operations.
 *
 * <p><strong>Rule:</strong> All @Transactional methods/classes must use explicit qualifier:
 *
 * <pre>
 * &#64;Transactional("transactionManager")  // GOOD
 * &#64;Transactional                        // BAD - will fail in multi-datasource setup
 * </pre>
 *
 * @see <a href="../../../../docs/adr/013-multi-datasource-transaction-strategy.md">ADR-013:
 *     Multi-DataSource Transaction Strategy</a>
 * @see <a
 *     href="../../../../module-infra/src/main/kotlin/maple/expectation/infrastructure/config/TransactionConfig.kt">TransactionConfig</a>
 */
class TransactionManagerBindingTest {

  private static final String TRANSACTION_MANAGER_BEAN_NAME = "transactionManager";

  /**
   * Enforces that all @Transactional annotations have explicit transaction manager qualifier.
   *
   * <p>This prevents NoUniqueBeanDefinitionException when multiple TransactionManager beans exist.
   *
   * <p><strong>Scope:</strong> Checks service and infrastructure packages (excludes test code).
   */
  @Test
  void all_transactional_annotations_must_have_explicit_transaction_manager() {
    var classes =
        new ClassFileImporter()
            .importPackages("maple.expectation.application", "maple.expectation.infrastructure");

    var violationCollector = new ViolationCollector();

    for (var javaClass : classes) {
      // Check class-level @Transactional
      checkClassLevelTransactional(javaClass, violationCollector);

      // Check method-level @Transactional
      checkMethodLevelTransactional(javaClass, violationCollector);
    }

    if (!violationCollector.violations.isEmpty()) {
      var message =
          new StringBuilder()
              .append("Transactional annotations must specify explicit transaction manager: \"")
              .append(TRANSACTION_MANAGER_BEAN_NAME)
              .append("\"\n\n")
              .append("Violations:\n");

      for (var violation : violationCollector.violations) {
        message.append("  - ").append(violation).append("\n");
      }

      message
          .append("\n")
          .append("Fix: Change @Transactional to @Transactional(\"")
          .append(TRANSACTION_MANAGER_BEAN_NAME)
          .append("\")\n")
          .append("\n")
          .append(
              "See ADR-013: https://github.com/.../docs/adr/013-multi-datasource-transaction-strategy.md");

      org.junit.jupiter.api.Assertions.fail(message.toString());
    }
  }

  private void checkClassLevelTransactional(
      com.tngtech.archunit.core.domain.JavaClass javaClass, ViolationCollector collector) {
    try {
      var transactional = javaClass.getAnnotationOfType(Transactional.class);
      if (transactional != null) {
        var value = getTransactionalValue(transactional);
        if (!TRANSACTION_MANAGER_BEAN_NAME.equals(value)) {
          collector.addViolation(
              "Class " + javaClass.getName() + " has @Transactional without explicit qualifier");
        }
      }
    } catch (Exception e) {
      // Skip if annotation cannot be processed
    }
  }

  private void checkMethodLevelTransactional(
      com.tngtech.archunit.core.domain.JavaClass javaClass, ViolationCollector collector) {
    for (var method : javaClass.getMethods()) {
      try {
        var transactional = method.getAnnotationOfType(Transactional.class);
        if (transactional != null) {
          var value = getTransactionalValue(transactional);
          if (!TRANSACTION_MANAGER_BEAN_NAME.equals(value)) {
            collector.addViolation(
                "Method "
                    + javaClass.getName()
                    + "."
                    + method.getName()
                    + " has @Transactional without explicit qualifier");
          }
        }
      } catch (Exception e) {
        // Skip if annotation cannot be processed
      }
    }
  }

  private String getTransactionalValue(JavaAnnotation<?> annotation) {
    // Try to get the "value" attribute from @Transactional
    try {
      var value = annotation.get("value").getValue();
      if (value instanceof String str && !str.isEmpty()) {
        return str;
      }
    } catch (Exception e) {
      // Value attribute not set or not accessible
    }
    return null;
  }

  private static class ViolationCollector {
    private final java.util.List<String> violations = new java.util.ArrayList<>();

    void addViolation(String violation) {
      violations.add(violation);
    }
  }
}
