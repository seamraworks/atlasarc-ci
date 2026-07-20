package io.atlasarc.evaluator

import io.atlasarc.evaluation.GovernanceEvaluationResult
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end regression corpus for module-qualified governance over split packages. */
class MultiModuleGovernanceRegressionTest {
    @TempDir
    lateinit var repository: Path

    private val fixture: Path = Path.of(System.getProperty("atlasarc.testFixtures"))
        .resolve("cycle-governance/multi-module-split-packages")

    @BeforeEach
    fun compileFixture() {
        repository.resolve(".git").createDirectories()
        copyTree(fixture.resolve("modules"), repository.resolve("modules"))
        listOf("orders", "billing", "reporting").forEach(::compileModule)
        val config = repository.resolve(".atlasarc/evaluator.json")
        config.parent.createDirectories()
        Files.copy(fixture.resolve("evaluator.json"), config, StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `empty governance exposes both real cycle groups and every module reference`() {
        val execution = evaluate("empty.json")
        val result = execution.result

        assertEquals(EvaluatorExitCode.PROBLEMS, execution.exitCode)
        assertEquals(GovernanceEvaluationVerdict.PROBLEMS, result.verdict)
        assertEquals(
            setOf(
                setOf("billing:acceptance.shared.left", "billing:acceptance.shared.right"),
                setOf("orders:acceptance.shared.left", "orders:acceptance.shared.right"),
                setOf("reporting:acceptance.reporting.input", "reporting:acceptance.reporting.output"),
            ),
            result.problemGroups.map { group -> group.members.mapTo(sortedSetOf()) { "${it.module}:${it.architectureUnit}" } }.toSet(),
        )
        assertEquals(
            setOf("billing", "orders"),
            result.problemEdges.filter {
                it.source.architectureUnit == "acceptance.shared.left" && it.target.architectureUnit == "acceptance.shared.right"
            }.mapNotNullTo(sortedSetOf()) { it.source.module },
        )
    }

    @Test
    fun `orders governance does not cover billing on the same package edge`() {
        val execution = evaluate("orders-and-reporting.json")
        val result = execution.result

        assertEquals(EvaluatorExitCode.PROBLEMS, execution.exitCode)
        assertEquals(GovernanceEvaluationVerdict.PROBLEMS, result.verdict)
        assertEquals(
            listOf("billing:acceptance.shared.left", "billing:acceptance.shared.right"),
            result.problemGroups.single().members.map { "${it.module}:${it.architectureUnit}" },
        )
        val sharedDirection = result.problemEdges.single {
            it.source.module == "billing" &&
                it.source.architectureUnit == "acceptance.shared.left" &&
                it.target.architectureUnit == "acceptance.shared.right"
        }
        assertTrue(sharedDirection.governedReferenceIds.isEmpty())
        assertEquals(1, sharedDirection.uncoveredReferenceIds.size)
        assertEquals(
            listOf("ref-6dc39001fc074f2bdc401e9e3ba4ef2d"),
            result.records.single { it.recordId == "accept-orders-shared-cycle" }.matchedReferenceIds,
        )
    }

    @Test
    fun `fully governed modules produce a clean deterministic result`() {
        val first = evaluate("fully-governed.json")
        val second = evaluate("fully-governed.json")

        assertEquals(EvaluatorExitCode.CLEAN, first.exitCode)
        assertEquals(GovernanceEvaluationVerdict.CLEAN, first.result.verdict)
        assertEquals(first.result, second.result)
        assertTrue(first.result.problemGroups.isEmpty())
        assertTrue(first.result.problemEdges.isEmpty())
        assertEquals(3, first.result.summary.activeRecordCount)
        assertEquals(
            3,
            first.result.records.flatMap { it.matchedReferenceIds }.toSet().size,
            "each module-qualified decision must match its own concrete reference",
        )
    }

    private fun evaluate(governanceState: String): Execution {
        val governance = repository.resolve(".atlasarc/governance/cycles.json")
        governance.parent.createDirectories()
        Files.copy(
            fixture.resolve("governance/$governanceState"),
            governance,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = EvaluatorApplication(PrintStream(stdout), PrintStream(stderr)).run(
            arrayOf("evaluate", "--config", ".atlasarc/evaluator.json", "--format", "json"),
            repository,
        )
        assertTrue(stderr.toString(Charsets.UTF_8).isBlank())
        return Execution(exitCode, Json.decodeFromString(stdout.toString(Charsets.UTF_8)))
    }

    private fun compileModule(module: String) {
        val sourceRoot = repository.resolve("modules/$module/src/main/java")
        val sources = Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sorted()
                .toList()
        }
        val classes = repository.resolve("modules/$module/target/classes")
        classes.createDirectories()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK compiler is required" }
        val exitCode = compiler.run(
            null,
            null,
            null,
            "-d",
            classes.toString(),
            *sources.map(Path::toString).toTypedArray(),
        )
        assertEquals(0, exitCode, "fixture module $module must compile")
        val old = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
        sources.forEach { Files.setLastModifiedTime(it, old) }
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

    private data class Execution(
        val exitCode: Int,
        val result: GovernanceEvaluationResult,
    )
}
