package io.atlasarc.archunit

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Locks the public ArchUnit promise to the same multi-module corpus as the evaluator. */
class MultiModuleGovernanceRuleRegressionTest {
    @TempDir
    lateinit var repository: Path

    private val fixture: Path = Path.of(System.getProperty("atlasarc.testFixtures"))
        .resolve("cycle-governance/multi-module-split-packages")
    private lateinit var classes: JavaClasses

    @BeforeEach
    fun compileFixture() {
        repository.resolve(".git").createDirectories()
        copyTree(fixture.resolve("modules"), repository.resolve("modules"))
        val classRoots = listOf("orders", "billing", "reporting").map(::compileModule)
        classes = ClassFileImporter().importPaths(classRoots)
    }

    @Test
    fun `empty partial and complete governance retain the ArchUnit fail then pass contract`() {
        useGovernance("empty.json")
        val empty = rule().evaluate(classes)
        assertTrue(empty.hasViolation())
        assertEquals(3, empty.failureReport.details.size)

        useGovernance("orders-and-reporting.json")
        val partial = rule().evaluate(classes)
        assertTrue(partial.hasViolation())
        assertEquals(1, partial.failureReport.details.size)
        assertTrue(partial.failureReport.details.single().contains("acceptance.shared.left"))

        useGovernance("fully-governed.json")
        val complete = rule().evaluate(classes)
        assertFalse(complete.hasViolation())
        assertTrue(complete.failureReport.details.isEmpty())
    }

    @Test
    fun `module-qualified repository scope leaves the equal package in another module`() {
        useGovernance("empty.json")
        useScope("billing-left.json")

        val result = rule().evaluate(classes)

        assertTrue(result.hasViolation())
        assertEquals(2, result.failureReport.details.size)
        assertTrue(result.failureReport.details.any { it.contains("orders:acceptance.shared.left") })
        assertFalse(result.failureReport.details.any { it.contains("billing:acceptance.shared.left") })
    }

    private fun rule() = AtlasArcGovernanceRules.governedCycles()
        .fromRepository(repository)
        .forAnalysisSource("jvm:whole-project")
        .withModuleSourceRoot("orders", repository.resolve("modules/orders/src/main/java"))
        .withModuleSourceRoot("billing", repository.resolve("modules/billing/src/main/java"))
        .withModuleSourceRoot("reporting", repository.resolve("modules/reporting/src/main/java"))
        .withModuleClassRoot("orders", repository.resolve("modules/orders/target/classes"))
        .withModuleClassRoot("billing", repository.resolve("modules/billing/target/classes"))
        .withModuleClassRoot("reporting", repository.resolve("modules/reporting/target/classes"))
        .build()

    private fun useGovernance(state: String) {
        val target = repository.resolve(".atlasarc/governance/cycles.json")
        target.parent.createDirectories()
        Files.copy(fixture.resolve("governance/$state"), target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun useScope(state: String) {
        val target = repository.resolve(".atlasarc/governance/scope.json")
        target.parent.createDirectories()
        Files.copy(fixture.resolve("scope/$state"), target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun compileModule(module: String): Path {
        val sourceRoot = repository.resolve("modules/$module/src/main/java")
        val sources = Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sorted()
                .toList()
        }
        val classesRoot = repository.resolve("modules/$module/target/classes")
        classesRoot.createDirectories()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK compiler is required" }
        val exitCode = compiler.run(
            null,
            null,
            null,
            "-d",
            classesRoot.toString(),
            *sources.map(Path::toString).toTypedArray(),
        )
        assertEquals(0, exitCode, "fixture module $module must compile")
        val old = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
        sources.forEach { Files.setLastModifiedTime(it, old) }
        return classesRoot
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) destination.createDirectories()
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
