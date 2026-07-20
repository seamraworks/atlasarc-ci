package io.atlasarc.archunit

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.EvaluationResult
import com.tngtech.archunit.lang.Priority
import com.tngtech.archunit.lang.SimpleConditionEvent
import io.atlasarc.evaluation.CycleGovernanceEvaluator
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import io.atlasarc.governance.CycleGovernanceRepository
import io.atlasarc.governance.GovernanceReadResult
import java.nio.file.Path

/** Java-friendly entry point for AtlasArc.io's repository-backed ArchUnit rule. */
object AtlasArcGovernanceRules {
    @JvmStatic
    fun governedCycles(): AtlasArcGovernedCyclesRuleBuilder = AtlasArcGovernedCyclesRuleBuilder()
}

class AtlasArcGovernedCyclesRuleBuilder internal constructor() {
    private var repositoryStart: Path = Path.of(".")
    private var analysisSourceId: String = "jvm:whole-project"
    private val sourceRoots = mutableListOf<ConfiguredSourceRoot>()
    private val classRoots = mutableListOf<ConfiguredSourceRoot>()

    fun fromRepository(path: Path): AtlasArcGovernedCyclesRuleBuilder = apply {
        repositoryStart = path
    }

    fun forAnalysisSource(id: String): AtlasArcGovernedCyclesRuleBuilder = apply {
        require(id.isNotBlank()) { "The AtlasArc.io analysis source ID must not be blank." }
        analysisSourceId = id
    }

    fun withModuleSourceRoot(module: String, path: Path): AtlasArcGovernedCyclesRuleBuilder = apply {
        require(module.isNotBlank()) { "The module name must not be blank." }
        sourceRoots += ConfiguredSourceRoot(path, module)
    }

    /** Adds compiled output for a named module, making split-package attribution unambiguous. */
    fun withModuleClassRoot(module: String, path: Path): AtlasArcGovernedCyclesRuleBuilder = apply {
        require(module.isNotBlank()) { "The module name must not be blank." }
        classRoots += ConfiguredSourceRoot(path, module)
    }

    /** Adds a source root that belongs to one genuinely module-less JVM universe. */
    fun withSourceRoot(path: Path): AtlasArcGovernedCyclesRuleBuilder = apply {
        sourceRoots += ConfiguredSourceRoot(path, "")
    }

    /** Adds compiled output that belongs to one genuinely module-less JVM universe. */
    fun withClassRoot(path: Path): AtlasArcGovernedCyclesRuleBuilder = apply {
        classRoots += ConfiguredSourceRoot(path, "")
    }

    fun build(): ArchRule {
        require(sourceRoots.isNotEmpty()) {
            "Declare at least one Java/Kotlin source root so the ArchUnit rule can reproduce AtlasArc.io evidence identities."
        }
        val allRoots = sourceRoots + classRoots
        val hasNamedRoots = allRoots.any { it.module.isNotBlank() }
        val hasModulelessRoots = allRoots.any { it.module.isBlank() }
        require(!(hasNamedRoots && hasModulelessRoots)) {
            "Do not mix named and module-less AtlasArc.io source roots. Use withModuleSourceRoot for every root in a " +
                "modular project, or withSourceRoot for every root in one genuinely module-less JVM universe."
        }
        return AtlasArcGovernedCyclesRule(
            repositoryStart = repositoryStart,
            analysisSourceId = analysisSourceId,
            sourceRoots = sourceRoots.toList(),
            classRoots = classRoots.toList(),
        )
    }
}

private data class ConfiguredSourceRoot(
    val path: Path,
    val module: String,
)

private class AtlasArcGovernedCyclesRule(
    private val repositoryStart: Path,
    private val analysisSourceId: String,
    private val sourceRoots: List<ConfiguredSourceRoot>,
    private val classRoots: List<ConfiguredSourceRoot>,
    private val description: String = "be free of cycles not accepted by AtlasArc.io repository governance",
    private val allowEmpty: Boolean = false,
) : ArchRule {
    override fun check(classes: JavaClasses) {
        ArchRule.Assertions.assertNoViolation(evaluate(classes))
    }

    override fun evaluate(classes: JavaClasses): EvaluationResult {
        if (classes.toList().isEmpty()) {
            return if (allowEmpty) success() else failure(classes, "AtlasArc.io cannot evaluate an empty ArchUnit class set.")
        }

        val loaded = when (val read = CycleGovernanceRepository().read(repositoryStart)) {
            is GovernanceReadResult.Loaded -> read.value
            is GovernanceReadResult.Invalid -> return failure(
                classes,
                "AtlasArc.io governance is invalid: " + read.issues.joinToString("; ") { it.message },
            )
            is GovernanceReadResult.MissingVcsRoot -> return failure(
                classes,
                "AtlasArc.io could not find the Git repository that owns .atlasarc/governance/cycles.json.",
            )
            is GovernanceReadResult.IoError -> return failure(
                classes,
                "AtlasArc.io could not read repository governance: ${read.message}",
            )
        }
        val repositoryRoot = loaded.repositoryRoot
        val result = try {
            val input = ArchUnitGovernanceEvidence().build(
                classes = classes,
                sourceRoots = sourceRoots.map { configured ->
                    JvmEvidenceRoot(
                        path = if (configured.path.isAbsolute) configured.path else repositoryRoot.resolve(configured.path),
                        module = configured.module.ifBlank { null },
                    )
                },
                analysisSourceId = analysisSourceId,
                repositoryRoot = repositoryRoot,
                classRoots = classRoots.map { configured ->
                    JvmEvidenceRoot(
                        path = if (configured.path.isAbsolute) configured.path else repositoryRoot.resolve(configured.path),
                        module = configured.module.ifBlank { null },
                    )
                },
            )
            CycleGovernanceEvaluator().evaluate(
                document = loaded.document,
                inputs = listOf(input),
                evaluatorVersion = "atlasarc-archunit/${implementationVersion()}",
            )
        } catch (exception: Exception) {
            return failure(
                classes,
                "AtlasArc.io could not evaluate the ArchUnit class set: ${exception.message ?: exception.javaClass.simpleName}",
            )
        }

        val events = ConditionEvents.Factory.create()
        when (result.verdict) {
            GovernanceEvaluationVerdict.CLEAN -> Unit
            GovernanceEvaluationVerdict.PROBLEMS -> result.problemGroups.forEach { group ->
                events.add(
                    SimpleConditionEvent.violated(
                        group,
                        "AtlasArc.io found an ungoverned cycle in '${group.analysisSourceId}': " +
                            group.members.joinToString(" -> ") { member ->
                                member.module?.let { "$it:${member.architectureUnit}" } ?: member.architectureUnit
                            },
                    ),
                )
            }
            GovernanceEvaluationVerdict.INVALID -> {
                if (result.issues.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(result, "AtlasArc.io governance evaluation is invalid."))
                } else {
                    result.issues.forEach { issue ->
                        events.add(SimpleConditionEvent.violated(issue, "AtlasArc.io governance is invalid: ${issue.message}"))
                    }
                }
            }
        }
        return EvaluationResult(this, events, Priority.MEDIUM)
    }

    override fun getDescription(): String = description

    override fun because(reason: String): ArchRule = AtlasArcGovernedCyclesRule(
        repositoryStart,
        analysisSourceId,
        sourceRoots,
        classRoots,
        "$description, because $reason",
        allowEmpty,
    )

    override fun `as`(newDescription: String): ArchRule = AtlasArcGovernedCyclesRule(
        repositoryStart,
        analysisSourceId,
        sourceRoots,
        classRoots,
        newDescription,
        allowEmpty,
    )

    override fun allowEmptyShould(allowEmptyShould: Boolean): ArchRule = AtlasArcGovernedCyclesRule(
        repositoryStart,
        analysisSourceId,
        sourceRoots,
        classRoots,
        description,
        allowEmptyShould,
    )

    private fun success(): EvaluationResult =
        EvaluationResult(this, ConditionEvents.Factory.create(), Priority.MEDIUM)

    private fun failure(subject: Any, message: String): EvaluationResult {
        val events = ConditionEvents.Factory.create()
        events.add(SimpleConditionEvent.violated(subject, message))
        return EvaluationResult(this, events, Priority.MEDIUM)
    }

    private fun implementationVersion(): String =
        AtlasArcGovernanceRules::class.java.`package`.implementationVersion ?: "development"
}
