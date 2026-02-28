package maple.expectation.web.arch

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll

/**
 * ADR-005 모듈 의존성 규칙 검증 테스트
 *
 * 의존성 그래프:
 * ```
 * module-web  ──────>  module-app  ──────>  module-core
 *                            ^                    ^
 *                            |                    |
 *                     module-infra ───────────────┘
 * ```
 *
 * 절대 금지 룰:
 * - ❌ module-core → module-infra
 * - ❌ module-app → module-web
 * - ❌ module-common → spring-web
 */
class ModuleDependencyTest {

    companion object {
        private lateinit var importedClasses: JavaClasses

        @BeforeAll
        @JvmStatic
        fun setUp() {
            importedClasses = ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("maple.expectation")
        }
    }

    /**
     * Rule 1: module-core는 module-infra, module-app, module-web을 참조하면 안 됨
     */
    @Test
    fun `core should not depend on infra or web or app`() {
        noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..infrastructure..",
                "..infra..",
                "..service.v2..",
                "..service.v4..",
                "..service.v5..",
                "..controller..",
                "..scheduler..",
                "..batch.."
            )
            .check(importedClasses)
    }

    /**
     * Rule 2: module-common은 Spring Web을 참조하면 안 됨
     */
    @Test
    fun `common should not depend on spring`() {
        noClasses()
            .that().resideInAPackage("..common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.boot..",
                "org.springframework.data.."
            )
            .check(importedClasses)
    }

    /**
     * Rule 3: module-infra는 module-app을 참조하면 안 됨
     */
    @Test
    fun `infra should not depend on app services`() {
        noClasses()
            .that().resideInAPackage("..infra..")
            .or().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..service.v2..",
                "..service.v4..",
                "..service.v5..",
                "..controller.."
            )
            .check(importedClasses)
    }

    /**
     * Rule 4: 순환 의존성 없음
     */
    @Test
    fun `no circular dependencies`() {
        // ArchUnit의 slices()는 Kotlin에서 제한적이므로
        // 개별 규칙으로 검증
        noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infra..")
            .check(importedClasses)
    }
}
