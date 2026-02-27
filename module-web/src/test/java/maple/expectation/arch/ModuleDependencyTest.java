package maple.expectation.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ModuleDependencyTest {

  @Test
  void core_should_not_depend_on_web_or_infra() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..web..", "..infra..");

    rule.check(new ClassFileImporter().importPackages("maple.expectation"));
  }

  @Test
  void common_should_not_depend_on_spring() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..common..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..");

    rule.check(new ClassFileImporter().importPackages("maple.expectation"));
  }

  @Test
  void no_circular_dependencies() {
    ArchRule rule = slices().matching("maple.expectation.(*)..").should().beFreeOfCycles();

    rule.check(new ClassFileImporter().importPackages("maple.expectation"));
  }
}
