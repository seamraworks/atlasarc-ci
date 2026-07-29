package io.atlasarc.parity

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.atlasarc.archunit.ArchUnitGovernanceEvidence
import io.atlasarc.archunit.JvmEvidenceRoot
import io.atlasarc.evaluation.CycleGovernanceEvaluator
import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.evaluator.EvaluatorAcquisitionException
import io.atlasarc.evaluator.TypeScriptGovernanceEvidence
import io.atlasarc.governance.CycleGovernanceDocument
import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.CycleGovernanceRecord
import io.atlasarc.governance.GovernanceAnalysisSource
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceOwnerSide
import io.atlasarc.governance.GovernanceRecordStatus
import io.atlasarc.governance.GovernanceScope
import io.atlasarc.parity.fixtures.jvm.java.left.JavaCaller
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicAcquisitionParityCorpusTest {
    @TempDir
    lateinit var repository: Path

    @Test
    fun `public authority corpus matches its reviewed manifest`() {
        AcquisitionParityContract.verifyCorpusManifest(moduleRoot())
    }

    @Test
    fun `public JVM and TypeScript acquisition matches the reviewed mixed snapshot`() {
        val jvm = acquireJvm(repository)
        val typescript = acquireTypeScript(repository, "active.json")
        val inputs = listOf(jvm, typescript)
        val evaluation = CycleGovernanceEvaluator().evaluate(mixedDocument(), inputs, "parity-corpus")

        assertGolden("mixed.expected.json", AcquisitionParityContract.snapshot(inputs, evaluation))
    }

    @Test
    fun `public JVM acquisition keeps TypeScript governance neutral in a partial run`() {
        val jvm = acquireJvm(repository)
        val evaluation = CycleGovernanceEvaluator().evaluate(mixedDocument(), listOf(jvm), "parity-corpus")

        assertEquals(
            GovernanceRecordStatus.NOT_IN_ANALYSIS,
            evaluation.records.single { it.recordId == "typescript-a-to-b" }.status,
        )
        assertGolden(
            "jvm-partial.expected.json",
            AcquisitionParityContract.snapshot(listOf(jvm), evaluation),
        )
    }

    @Test
    fun `public acquisition keeps equal package names separate across modules`() {
        val input = acquireModules(repository)
        val evaluation = CycleGovernanceEvaluator().evaluate(
            CycleGovernanceDocument(),
            listOf(input),
            "parity-corpus",
        )

        assertGolden("modules.expected.json", AcquisitionParityContract.snapshot(listOf(input), evaluation))
    }

    @Test
    fun `TypeScript exact reference resolves and reactivates while stale and missing artifacts fail closed`() {
        val active = acquireTypeScript(repository, "active.json")
        val reference = active.evidence.references.single {
            it.source.architectureUnit == "src/a" &&
                it.target.architectureUnit == "src/b" &&
                it.dependencyKind?.name == "RUNTIME_IMPORT"
        }
        val document = exactTypeScriptDocument(reference.id, reference.source, reference.target)
        val activeEvaluation = CycleGovernanceEvaluator().evaluate(document, listOf(active), "parity-corpus")
        val activeSnapshot = AcquisitionParityContract.snapshot(listOf(active), activeEvaluation)
        assertEquals(GovernanceRecordStatus.ACTIVE, activeEvaluation.records.single().status)
        assertGolden("typescript-active.expected.json", activeSnapshot)

        val resolved = acquireTypeScript(repository, "resolved.json")
        val resolvedEvaluation = CycleGovernanceEvaluator().evaluate(document, listOf(resolved), "parity-corpus")
        assertEquals(GovernanceRecordStatus.RESOLVED, resolvedEvaluation.records.single().status)
        assertGolden(
            "typescript-resolved.expected.json",
            AcquisitionParityContract.snapshot(listOf(resolved), resolvedEvaluation),
        )

        val reactivated = acquireTypeScript(repository, "active.json")
        val reactivatedEvaluation = CycleGovernanceEvaluator().evaluate(document, listOf(reactivated), "parity-corpus")
        AcquisitionParityContract.requireEquivalent(
            activeSnapshot,
            AcquisitionParityContract.snapshot(listOf(reactivated), reactivatedEvaluation),
        )

        val stale = acquireTypeScript(repository, "active.json", stale = true)
        val staleEvaluation = CycleGovernanceEvaluator().evaluate(document, listOf(stale), "parity-corpus")
        assertTrue(stale.evidence.sources.single().fresh.not())
        assertGolden(
            "typescript-stale.expected.json",
            AcquisitionParityContract.snapshot(listOf(stale), staleEvaluation),
        )

        val missingRoot = prepareTypeScriptProject(repository, "active.json")
        missingRoot.resolve(".atlasarc/depgraph.json").deleteIfExists()
        val failure = assertFailsWith<EvaluatorAcquisitionException> {
            TypeScriptGovernanceEvidence().build(
                analysisSourceId = TYPESCRIPT_SOURCE,
                repositoryRoot = missingRoot,
                sourceRoot = missingRoot,
                dependencyCruiserJson = missingRoot.resolve(".atlasarc/depgraph.json"),
            )
        }
        assertEquals(TYPESCRIPT_SOURCE, failure.analysisSourceId)
        assertTrue(failure.message.orEmpty().contains("missing"))
    }

    @Test
    fun `contract points to descriptor and tuple-correlation mutations`() {
        val jvm = acquireJvm(repository)
        val snapshot = AcquisitionParityContract.snapshot(listOf(jvm))
        val descriptorIndex = snapshot.references.indexOfFirst { it.target.memberDescriptor != null }
        assertTrue(descriptorIndex >= 0)
        val descriptorMutation = snapshot.copy(
            references = snapshot.references.toMutableList().also { references ->
                val reference = references[descriptorIndex]
                references[descriptorIndex] = reference.copy(
                    target = reference.target.copy(memberDescriptor = null),
                )
            },
        )
        val descriptorDifference = requireNotNull(
            AcquisitionParityContract.firstDifference(snapshot, descriptorMutation),
        )
        assertTrue(descriptorDifference.path.contains("memberDescriptor"), descriptorDifference.toString())

        val correlationMutation = snapshot.copy(references = snapshot.references.dropLast(1))
        val correlationDifference = requireNotNull(
            AcquisitionParityContract.firstDifference(snapshot, correlationMutation),
        )
        assertEquals("$.references.length", correlationDifference.path)
    }

    private fun acquireJvm(root: Path): GovernanceEvaluationInput {
        val sourceRoot = root.resolve("jvm-src")
        copyJvmFixtureSources(sourceRoot)
        val classRoot = Path.of(JavaCaller::class.java.protectionDomain.codeSource.location.toURI())
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages(JAVA_FIXTURE_PACKAGE, KOTLIN_FIXTURE_PACKAGE)
        return ArchUnitGovernanceEvidence().build(
            classes = classes,
            sourceRoots = listOf(JvmEvidenceRoot(sourceRoot)),
            analysisSourceId = JVM_SOURCE,
            repositoryRoot = root,
            classRoots = listOf(JvmEvidenceRoot(classRoot)),
        )
    }

    private fun acquireModules(root: Path): GovernanceEvaluationInput {
        val modulesRoot = root.resolve("modules")
        copyTree(corpusRoot().resolve("modules"), modulesRoot)
        val modules = listOf("billing", "orders")
        val classRoots = modules.associateWith { module -> compileModule(modulesRoot, module) }
        val classes = ClassFileImporter().importPaths(classRoots.values)
        return ArchUnitGovernanceEvidence().build(
            classes = classes,
            sourceRoots = modules.map { module ->
                JvmEvidenceRoot(modulesRoot.resolve("$module/src/main/java"), module)
            },
            analysisSourceId = MODULE_SOURCE,
            repositoryRoot = root,
            classRoots = classRoots.map { (module, path) -> JvmEvidenceRoot(path, module) },
        )
    }

    private fun acquireTypeScript(root: Path, state: String, stale: Boolean = false): GovernanceEvaluationInput {
        val project = prepareTypeScriptProject(root, state)
        if (stale) {
            Files.setLastModifiedTime(
                project.resolve("src/a/index.ts"),
                FileTime.from(Instant.parse("2035-01-01T00:00:00Z")),
            )
            Files.setLastModifiedTime(
                project.resolve(".atlasarc/depgraph.json"),
                FileTime.from(Instant.parse("2025-01-01T00:00:00Z")),
            )
        }
        return TypeScriptGovernanceEvidence().build(
            analysisSourceId = TYPESCRIPT_SOURCE,
            repositoryRoot = project,
            sourceRoot = project,
            dependencyCruiserJson = project.resolve(".atlasarc/depgraph.json"),
        )
    }

    private fun prepareTypeScriptProject(root: Path, state: String): Path {
        val project = root.resolve("typescript-project")
        if (project.exists()) deleteTree(project)
        copyTree(corpusRoot().resolve("typescript/project"), project)
        val artifact = project.resolve(".atlasarc/depgraph.json")
        artifact.parent.createDirectories()
        Files.copy(
            corpusRoot().resolve("typescript/$state"),
            artifact,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val old = FileTime.from(Instant.parse("2025-01-01T00:00:00Z"))
        Files.walk(project.resolve("src")).use { paths ->
            paths.filter(Files::isRegularFile).forEach { Files.setLastModifiedTime(it, old) }
        }
        Files.setLastModifiedTime(artifact, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")))
        return project
    }

    private fun mixedDocument() = CycleGovernanceDocument(
        records = linkedMapOf(
            "java-left-to-right" to CycleGovernanceRecord(
                analysisSource = GovernanceAnalysisSource(JVM_SOURCE, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA),
                scope = GovernanceScope.PACKAGE,
                ownerSide = GovernanceOwnerSide.SOURCE,
                source = GovernanceIdentity("$JAVA_FIXTURE_PACKAGE.left"),
                target = GovernanceIdentity("$JAVA_FIXTURE_PACKAGE.right"),
                kind = CycleGovernanceKind.INTENTIONAL,
                reason = "Parity corpus Java boundary.",
            ),
            "typescript-a-to-b" to CycleGovernanceRecord(
                analysisSource = GovernanceAnalysisSource(
                    TYPESCRIPT_SOURCE,
                    GovernanceBackend.TYPESCRIPT_ARTIFACT,
                    GovernanceLanguage.TYPESCRIPT,
                ),
                scope = GovernanceScope.SOURCE_FOLDER,
                ownerSide = GovernanceOwnerSide.SOURCE,
                source = GovernanceIdentity("src/a"),
                target = GovernanceIdentity("src/b"),
                kind = CycleGovernanceKind.DEBT,
                reason = "Parity corpus TypeScript boundary.",
            ),
        ),
    )

    private fun exactTypeScriptDocument(
        referenceId: String,
        source: GovernanceIdentity,
        target: GovernanceIdentity,
    ) = CycleGovernanceDocument(
        records = mapOf(
            "typescript-exact" to CycleGovernanceRecord(
                analysisSource = GovernanceAnalysisSource(
                    TYPESCRIPT_SOURCE,
                    GovernanceBackend.TYPESCRIPT_ARTIFACT,
                    GovernanceLanguage.TYPESCRIPT,
                ),
                scope = GovernanceScope.REFERENCE,
                ownerSide = GovernanceOwnerSide.SOURCE,
                source = source,
                target = target,
                referenceIds = setOf(referenceId),
                kind = CycleGovernanceKind.DEBT,
                reason = "Parity corpus exact lifecycle.",
            ),
        ),
    )

    private fun assertGolden(name: String, actual: AcquisitionParitySnapshot) {
        val expected = corpusRoot().resolve("expected/$name")
        if (System.getProperty("atlasarc.updateParityCorpus") == "true") {
            expected.parent.createDirectories()
            Files.writeString(expected, AcquisitionParityContract.encode(actual))
            return
        }
        assertTrue(
            expected.exists(),
            "Missing reviewed parity snapshot $expected. Regenerate explicitly with " +
                "-Datlasarc.updateParityCorpus=true and review the semantic diff.",
        )
        AcquisitionParityContract.requireEquivalent(Files.readString(expected), actual)
    }

    private fun copyJvmFixtureSources(target: Path) {
        val module = moduleRoot()
        listOf(
            module.resolve("src/test/java/io/atlasarc/parity/fixtures/jvm/java/left/JavaCaller.java") to
                target.resolve("io/atlasarc/parity/fixtures/jvm/java/left/JavaCaller.java"),
            module.resolve("src/test/java/io/atlasarc/parity/fixtures/jvm/java/right/OverloadedTarget.java") to
                target.resolve("io/atlasarc/parity/fixtures/jvm/java/right/OverloadedTarget.java"),
            module.resolve("src/test/kotlin/io/atlasarc/parity/fixtures/jvm/kotlin/left/KotlinLeft.kt") to
                target.resolve("io/atlasarc/parity/fixtures/jvm/kotlin/left/KotlinLeft.kt"),
            module.resolve("src/test/kotlin/io/atlasarc/parity/fixtures/jvm/kotlin/right/KotlinRight.kt") to
                target.resolve("io/atlasarc/parity/fixtures/jvm/kotlin/right/KotlinRight.kt"),
        ).forEach { (source, destination) ->
            destination.parent.createDirectories()
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun compileModule(modulesRoot: Path, module: String): Path {
        val sourceRoot = modulesRoot.resolve("$module/src/main/java")
        val sources = Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sorted()
                .toList()
        }
        val classes = modulesRoot.resolve("$module/target/classes")
        classes.createDirectories()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK compiler is required" }
        assertEquals(
            0,
            compiler.run(null, null, null, "-d", classes.toString(), *sources.map(Path::toString).toTypedArray()),
            "fixture module $module must compile",
        )
        return classes
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) destination.createDirectories()
                else {
                    destination.parent.createDirectories()
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun moduleRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("src/test/resources/acquisition-parity").exists() }

    private fun corpusRoot(): Path = moduleRoot().resolve("src/test/resources/acquisition-parity")

    private companion object {
        const val JVM_SOURCE = "jvm:parity"
        const val MODULE_SOURCE = "jvm:modules"
        const val TYPESCRIPT_SOURCE = "typescript:parity"
        const val JAVA_FIXTURE_PACKAGE = "io.atlasarc.parity.fixtures.jvm.java"
        const val KOTLIN_FIXTURE_PACKAGE = "io.atlasarc.parity.fixtures.jvm.kotlin"
    }
}
