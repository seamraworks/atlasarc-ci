package io.atlasarc.evaluator

import io.atlasarc.evaluation.GovernanceEvaluationResult
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import io.atlasarc.governance.CYCLE_GOVERNANCE_RELATIVE_PATH
import io.atlasarc.governance.CycleGovernanceCodec
import io.atlasarc.governance.CycleGovernanceDocument
import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.CycleGovernanceRecord
import io.atlasarc.governance.CycleGovernanceRepository
import io.atlasarc.governance.GovernanceAnalysisSource
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceEncodeResult
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceOwnerSide
import io.atlasarc.governance.GovernanceScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvaluatorApplicationTest {
    @TempDir
    lateinit var root: Path

    private lateinit var sourceRoot: Path
    private lateinit var classesRoot: Path

    @BeforeEach
    fun prepareJvmCycle() {
        assertEquals(0, ProcessBuilder("git", "init", "--quiet", root.toString()).start().waitFor())
        sourceRoot = root.resolve("src/main/java")
        classesRoot = root.resolve("build/classes")
        val a = sourceRoot.resolve("a/A.java")
        val b = sourceRoot.resolve("b/B.java")
        a.parent.createDirectories()
        b.parent.createDirectories()
        Files.writeString(
            a,
            "package a; import b.B; public class A { public void call(B b) { b.touch(); } public void touch() {} }\n",
        )
        Files.writeString(
            b,
            "package b; import a.A; public class B { public void call(A a) { a.touch(); } public void touch() {} }\n",
        )
        classesRoot.createDirectories()
        val compiler = ToolProvider.getSystemJavaCompiler()
        val exit = compiler.run(null, null, null, "-d", classesRoot.toString(), a.toString(), b.toString())
        assertEquals(0, exit)
        val old = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
        Files.setLastModifiedTime(a, old)
        Files.setLastModifiedTime(b, old)
    }

    @Test
    fun `direct JVM evaluation returns exit one and portable deterministic JSON`() {
        val first = runJson()
        val second = runJson()

        assertEquals(EvaluatorExitCode.PROBLEMS, first.exitCode)
        assertEquals(first.stdout, second.stdout)
        val result = Json.decodeFromString<GovernanceEvaluationResult>(first.stdout)
        assertEquals(GovernanceEvaluationVerdict.PROBLEMS, result.verdict)
        assertEquals(listOf("a", "b"), result.problemGroups.single().members.map { it.architectureUnit })
        assertFalse(first.stdout.contains(root.toString(), ignoreCase = true))
        assertFalse(first.stdout.contains("Reviewed package boundary"))
    }

    @Test
    fun `repository package record produces clean exit without exposing reason`() {
        writeGovernance(
            CycleGovernanceDocument(
                records = mapOf(
                    "govern-a-b" to CycleGovernanceRecord(
                        analysisSource = GovernanceAnalysisSource(
                            "jvm:main",
                            GovernanceBackend.JVM_BYTECODE,
                            GovernanceLanguage.JAVA,
                        ),
                        scope = GovernanceScope.PACKAGE,
                        ownerSide = GovernanceOwnerSide.SOURCE,
                        source = GovernanceIdentity("a"),
                        target = GovernanceIdentity("b"),
                        kind = CycleGovernanceKind.INTENTIONAL,
                        reason = "Reviewed package boundary",
                    ),
                ),
            ),
        )

        val execution = runJson()
        val result = Json.decodeFromString<GovernanceEvaluationResult>(execution.stdout)

        assertEquals(EvaluatorExitCode.CLEAN, execution.exitCode)
        assertEquals(GovernanceEvaluationVerdict.CLEAN, result.verdict)
        assertTrue(result.problemGroups.isEmpty())
        assertFalse(execution.stdout.contains("Reviewed package boundary"))
    }

    @Test
    fun `source newer than classes returns machine-readable invalid result`() {
        val future = FileTime.fromMillis(System.currentTimeMillis() + 10_000)
        Files.setLastModifiedTime(sourceRoot.resolve("a/A.java"), future)

        val execution = runJson()
        val result = Json.decodeFromString<GovernanceEvaluationResult>(execution.stdout)

        assertEquals(EvaluatorExitCode.INVALID, execution.exitCode)
        assertEquals(GovernanceEvaluationVerdict.INVALID, result.verdict)
        assertTrue(result.issues.any { it.code == "stale-analysis-evidence" })
        assertFalse(execution.stdout.contains(root.toString(), ignoreCase = true))
    }

    @Test
    fun `SARIF uses portable governance location and stable problem rule`() {
        val execution = execute(directArguments("sarif"))

        assertEquals(EvaluatorExitCode.PROBLEMS, execution.exitCode)
        assertTrue(execution.stdout.contains("\"version\": \"2.1.0\""))
        assertTrue(execution.stdout.contains("ATLASARC_UNGOVERNED_CYCLE"))
        assertFalse(execution.stdout.contains(root.toString(), ignoreCase = true))
    }

    @Test
    fun `config file supports the same stable invocation contract`() {
        val config = EvaluatorConfig(
            sources = listOf(
                EvaluatorSourceConfig(
                    id = "jvm:main",
                    backend = GovernanceBackend.JVM_BYTECODE,
                    classDirectories = listOf(EvaluatorPathSpec("build/classes", "main")),
                    sourceRoots = listOf(EvaluatorPathSpec("src/main/java", "main")),
                ),
            ),
        )
        val configPath = root.resolve(".atlasarc/evaluator.json")
        configPath.parent.createDirectories()
        Files.writeString(configPath, EvaluatorConfigCodec.encode(config))

        val execution = execute(
            arrayOf("evaluate", "--config", configPath.toString(), "--format", "json"),
        )

        assertEquals(EvaluatorExitCode.PROBLEMS, execution.exitCode)
        assertEquals(
            GovernanceEvaluationVerdict.PROBLEMS,
            Json.decodeFromString<GovernanceEvaluationResult>(execution.stdout).verdict,
        )
    }

    @Test
    fun `real TypeScript artifact acquisition reports folder cycle without invoking Node tooling`() {
        prepareTypeScriptCycle()

        val execution = execute(
            arrayOf(
                "evaluate",
                "--backend", "typescript-artifact",
                "--source-id", "typescript:frontend",
                "--root", ".",
                "--dependency-cruiser", ".atlasarc/depgraph.json",
                "--repository-root", ".",
                "--format", "json",
            ),
        )
        val result = Json.decodeFromString<GovernanceEvaluationResult>(execution.stdout)

        assertEquals(EvaluatorExitCode.PROBLEMS, execution.exitCode)
        assertEquals(listOf("src/app", "src/domain"), result.problemGroups.single().members.map { it.architectureUnit })
        assertFalse(execution.stdout.contains(root.toString(), ignoreCase = true))
    }

    @Test
    fun `TypeScript baseline writes exact debt and makes ordinary evaluation clean`() {
        val configPath = root.resolve(".atlasarc/typescript-evaluator.json")
        Files.writeString(configPath, EvaluatorConfigCodec.encode(prepareTypeScriptCycle()))

        val baseline = execute(arrayOf("baseline", "--config", configPath.toString(), "--write", "--format", "json"))
        val baselineResult = Json.decodeFromString<CycleDebtBaselineCommandResult>(baseline.stdout)
        val evaluation = execute(arrayOf("evaluate", "--config", configPath.toString(), "--format", "json"))

        assertEquals(EvaluatorExitCode.CLEAN, baseline.exitCode, baseline.stdout + baseline.stderr)
        assertTrue(baselineResult.written)
        assertEquals(2, baselineResult.summary.ungovernedCycleReferences)
        assertEquals(1, baselineResult.summary.selectedCycleBreakingEdges)
        assertEquals(1, baselineResult.summary.recordsToAdd)
        assertEquals(EvaluatorExitCode.CLEAN, evaluation.exitCode)
        val document = CycleGovernanceRepository().read(root) as io.atlasarc.governance.GovernanceReadResult.Loaded
        assertTrue(document.value.document.records.values.all { it.scope == GovernanceScope.REFERENCE })
    }

    @Test
    fun `invalid config emits exit two JSON rather than a false clean result`() {
        val configPath = root.resolve("invalid-evaluator.json")
        Files.writeString(configPath, """{"configVersion":1,"sources":[]}""")

        val execution = execute(
            arrayOf("evaluate", "--config", configPath.toString(), "--format", "json"),
        )
        val result = Json.decodeFromString<GovernanceEvaluationResult>(execution.stdout)

        assertEquals(EvaluatorExitCode.INVALID, execution.exitCode)
        assertEquals(GovernanceEvaluationVerdict.INVALID, result.verdict)
        assertTrue(result.issues.any { it.code == "invalid-evaluator-config" })
    }

    @Test
    fun `baseline preview is deterministic and does not write`() {
        val configPath = writeEvaluatorConfig()

        val first = execute(arrayOf("baseline", "--config", configPath.toString(), "--format", "json"))
        val second = execute(arrayOf("baseline", "--config", configPath.toString(), "--format", "json"))

        assertEquals(EvaluatorExitCode.CLEAN, first.exitCode, first.stdout + first.stderr)
        assertEquals(first.stdout, second.stdout)
        val result = Json.decodeFromString<CycleDebtBaselineCommandResult>(first.stdout)
        assertTrue(result.safe)
        assertFalse(result.writeRequested)
        assertFalse(result.written)
        assertTrue(result.summary.selectedCycleBreakingEdges > 0)
        assertTrue(result.summary.recordsToAdd > 0)
        assertTrue(result.summary.recordsToAdd < result.summary.ungovernedCycleReferences)
        assertEquals("clean", result.resultingVerdict)
        assertFalse(Files.exists(root.resolve(CYCLE_GOVERNANCE_RELATIVE_PATH)))
    }

    @Test
    fun `baseline write is atomic and an unchanged rerun preserves bytes`() {
        val configPath = writeEvaluatorConfig()
        val arguments = arrayOf(
            "baseline", "--config", configPath.toString(), "--write", "--format", "json",
            "--reason", "Existing debt at CI adoption.", "--ticket", "ARCH-42",
        )

        val first = execute(arguments)
        val path = root.resolve(CYCLE_GOVERNANCE_RELATIVE_PATH)
        assertEquals(EvaluatorExitCode.CLEAN, first.exitCode, first.stdout + first.stderr)
        val bytes = Files.readAllBytes(path)
        val second = execute(arguments)

        assertEquals(EvaluatorExitCode.CLEAN, first.exitCode)
        assertTrue(Json.decodeFromString<CycleDebtBaselineCommandResult>(first.stdout).written)
        val rerun = Json.decodeFromString<CycleDebtBaselineCommandResult>(second.stdout)
        assertEquals(EvaluatorExitCode.CLEAN, second.exitCode)
        assertEquals(0, rerun.summary.recordsToAdd)
        assertFalse(rerun.written)
        assertTrue(rerun.noChange)
        assertTrue(bytes.contentEquals(Files.readAllBytes(path)))
        val evaluation = execute(arrayOf("evaluate", "--config", configPath.toString(), "--format", "json"))
        assertEquals(EvaluatorExitCode.CLEAN, evaluation.exitCode)
    }

    @Test
    fun `baseline refuses stale evidence and ignored governance without writing`() {
        val configPath = writeEvaluatorConfig()
        Files.setLastModifiedTime(sourceRoot.resolve("a/A.java"), FileTime.fromMillis(System.currentTimeMillis() + 10_000))
        val stale = execute(arrayOf("baseline", "--config", configPath.toString(), "--write", "--format", "json"))

        assertEquals(EvaluatorExitCode.INVALID, stale.exitCode)
        assertFalse(Files.exists(root.resolve(CYCLE_GOVERNANCE_RELATIVE_PATH)))

        val old = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
        Files.setLastModifiedTime(sourceRoot.resolve("a/A.java"), old)
        Files.writeString(root.resolve(".gitignore"), ".atlasarc/\n")
        val ignored = execute(arrayOf("baseline", "--config", configPath.toString(), "--format", "json"))

        assertEquals(EvaluatorExitCode.INVALID, ignored.exitCode)
        assertTrue(Json.decodeFromString<CycleDebtBaselineCommandResult>(ignored.stdout).diagnostics.any {
            it.code == "ignored-governance-path"
        })
        assertFalse(Files.exists(root.resolve(CYCLE_GOVERNANCE_RELATIVE_PATH)))
    }

    private fun runJson(): Execution = execute(directArguments("json"))

    private fun writeEvaluatorConfig(): Path {
        val path = root.resolve(".atlasarc/evaluator.json")
        path.parent.createDirectories()
        Files.writeString(
            path,
            EvaluatorConfigCodec.encode(
                EvaluatorConfig(
                    sources = listOf(
                        EvaluatorSourceConfig(
                            id = "jvm:main",
                            backend = GovernanceBackend.JVM_BYTECODE,
                            classDirectories = listOf(EvaluatorPathSpec("build/classes", "main")),
                            sourceRoots = listOf(EvaluatorPathSpec("src/main/java", "main")),
                        ),
                    ),
                ),
            ),
        )
        return path
    }

    private fun prepareTypeScriptCycle(): EvaluatorConfig {
        val appFile = root.resolve("src/app/index.ts")
        val domainFile = root.resolve("src/domain/model.ts")
        appFile.parent.createDirectories()
        domainFile.parent.createDirectories()
        Files.writeString(appFile, "import { model } from '../domain/model';\nexport const app = model;\n")
        Files.writeString(domainFile, "import { app } from '../app/index';\nexport const model = app;\n")
        val depgraph = root.resolve(".atlasarc/depgraph.json")
        depgraph.parent.createDirectories()
        Files.writeString(
            depgraph,
            """
            {
              "modules": [
                {
                  "source": "src/app/index.ts",
                  "dependencies": [
                    {"module":"../domain/model","resolved":"src/domain/model.ts","dependencyTypes":["local","import"],"circular":true,"valid":true}
                  ]
                },
                {
                  "source": "src/domain/model.ts",
                  "dependencies": [
                    {"module":"../app/index","resolved":"src/app/index.ts","dependencyTypes":["local","import"],"circular":true,"valid":true}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        return EvaluatorConfig(
            sources = listOf(
                EvaluatorSourceConfig(
                    id = "typescript:frontend",
                    backend = GovernanceBackend.TYPESCRIPT_ARTIFACT,
                    root = ".",
                    dependencyCruiserJson = ".atlasarc/depgraph.json",
                ),
            ),
        )
    }

    private fun directArguments(format: String) = arrayOf(
        "evaluate",
        "--backend", "jvm-bytecode",
        "--source-id", "jvm:main",
        "--classes", "main=build/classes",
        "--source-root", "main=src/main/java",
        "--repository-root", ".",
        "--format", format,
    )

    private fun execute(arguments: Array<String>): Execution {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exit = EvaluatorApplication(PrintStream(stdout), PrintStream(stderr)).run(arguments, root)
        return Execution(
            exit,
            stdout.toString(Charsets.UTF_8).replace("\r\n", "\n"),
            stderr.toString(Charsets.UTF_8).replace("\r\n", "\n"),
        )
    }

    private fun writeGovernance(document: CycleGovernanceDocument) {
        val encoded = CycleGovernanceCodec().encode(document) as GovernanceEncodeResult.Success
        val path = root.resolve(CYCLE_GOVERNANCE_RELATIVE_PATH)
        path.parent.createDirectories()
        Files.writeString(path, encoded.text)
    }

    private data class Execution(val exitCode: Int, val stdout: String, val stderr: String)
}
