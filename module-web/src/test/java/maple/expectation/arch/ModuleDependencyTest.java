package maple.expectation.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Disabled;
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

  /**
   * TODO: 마이그레이션 완료 후 활성화
   *
   * <p>모듈 분리 작업 중에는 임시로 순환 의존성이 발생할 수 있음. Phase 4 완료 후 다시 활성화하여 아키텍처 무결성 검증.
   */
  @Disabled("모듈 분리 마이그레이션 중 - Phase 4 완료 후 활성화")
  @Test
  void no_circular_dependencies() {
    ArchRule rule = slices().matching("maple.expectation.(*)..").should().beFreeOfCycles();

    rule.check(new ClassFileImporter().importPackages("maple.expectation"));
  }
}
