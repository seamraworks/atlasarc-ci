package io.atlasarc.governance

data class GovernanceEvidenceSource(
    val id: String,
    val backend: GovernanceBackend,
    val languages: Set<GovernanceLanguage>,
    val supportedScopes: Set<GovernanceScope>,
    val fresh: Boolean = true,
    val freshnessDiagnostic: String? = null,
    /** True when absence from this evidence proves absence from the repository. */
    val repositoryComplete: Boolean = true,
    /** JVM modules whose absence can be evaluated even when the repository evidence is partial. */
    val includedJvmModules: Set<String> = emptySet(),
)

data class GovernanceEvidenceNode(
    val analysisSourceId: String,
    val backend: GovernanceBackend,
    val language: GovernanceLanguage,
    val identity: GovernanceIdentity,
)

data class GovernanceEvidenceReference(
    val id: String,
    val analysisSourceId: String,
    val backend: GovernanceBackend,
    val sourceLanguage: GovernanceLanguage,
    val targetLanguage: GovernanceLanguage,
    val source: GovernanceIdentity,
    val target: GovernanceIdentity,
    val dependencyKind: GovernanceDependencyKind? = null,
)

data class GovernanceEvidenceSnapshot(
    val sources: List<GovernanceEvidenceSource>,
    val nodes: List<GovernanceEvidenceNode>,
    val references: List<GovernanceEvidenceReference>,
    val caseSensitive: Boolean = true,
    /** True for repository-wide CI evaluation; false for one IDE Analysis Source. */
    val evaluationComplete: Boolean = true,
)

data class GovernanceRetargetCandidate(
    val side: GovernanceOwnerSide,
    val identity: GovernanceIdentity,
    val reason: String,
)

data class GovernanceModulePairCandidate(
    val sourceModule: String,
    val targetModule: String,
    val matchingReferenceCount: Int,
)

data class GovernanceRecordMatch(
    val recordId: String,
    val status: GovernanceRecordStatus,
    val matchedReferenceIds: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val retargetCandidates: List<GovernanceRetargetCandidate> = emptyList(),
    val moduleCandidates: List<GovernanceModulePairCandidate> = emptyList(),
)

data class GovernanceReferenceCoverage(
    val referenceId: String,
    val recordIds: List<String>,
    val effectiveKind: CycleGovernanceKind,
)

data class GovernanceMatchResult(
    val records: Map<String, GovernanceRecordMatch>,
    val coverage: Map<String, GovernanceReferenceCoverage>,
) {
    val activeRecordIds: Set<String>
        get() = records.values.filter { it.status == GovernanceRecordStatus.ACTIVE }.mapTo(sortedSetOf()) { it.recordId }

    val validationCount: Int
        get() = records.values.count {
            it.status != GovernanceRecordStatus.ACTIVE &&
                it.status != GovernanceRecordStatus.RESOLVED &&
                it.status != GovernanceRecordStatus.NOT_IN_ANALYSIS
        }
}

class CycleGovernanceMatcher(
    private val validator: CycleGovernanceValidator = CycleGovernanceValidator(),
) {
    fun match(
        document: CycleGovernanceDocument,
        evidence: GovernanceEvidenceSnapshot,
    ): GovernanceMatchResult {
        val recordMatches = linkedMapOf<String, GovernanceRecordMatch>()
        val activeRecords = linkedMapOf<String, CycleGovernanceRecord>()

        document.records.toSortedMap().forEach { (recordId, record) ->
            val result = matchRecord(recordId, record, evidence)
            recordMatches[recordId] = result
            if (result.status == GovernanceRecordStatus.ACTIVE) activeRecords[recordId] = record
        }
        document.records.toSortedMap().forEach { (recordId, record) ->
            val result = recordMatches.getValue(recordId)
            if (result.retargetCandidates.isEmpty() && result.moduleCandidates.isEmpty()) return@forEach
            recordMatches[recordId] = result.copy(
                retargetCandidates = result.retargetCandidates.filter { candidate ->
                    val updated = if (candidate.side == GovernanceOwnerSide.SOURCE) {
                        record.copy(source = candidate.identity)
                    } else {
                        record.copy(target = candidate.identity)
                    }
                    repairIsActionable(document, recordId, updated, evidence)
                },
                moduleCandidates = result.moduleCandidates.filter { candidate ->
                    repairIsActionable(
                        document,
                        recordId,
                        record.copy(
                            source = record.source.copy(module = candidate.sourceModule),
                            target = record.target.copy(module = candidate.targetModule),
                        ),
                        evidence,
                    )
                },
            )
        }

        val referenceKinds = linkedMapOf<String, MutableList<Pair<String, CycleGovernanceKind>>>()
        recordMatches.values.forEach { recordMatch ->
            if (recordMatch.status != GovernanceRecordStatus.ACTIVE) return@forEach
            val record = activeRecords.getValue(recordMatch.recordId)
            recordMatch.matchedReferenceIds.forEach { referenceId ->
                referenceKinds.getOrPut(referenceId) { mutableListOf() } += recordMatch.recordId to record.kind
            }
        }
        val coverage = referenceKinds.toSortedMap().mapValuesTo(linkedMapOf()) { (referenceId, entries) ->
            val sorted = entries.sortedBy { it.first }
            GovernanceReferenceCoverage(
                referenceId = referenceId,
                recordIds = sorted.map { it.first },
                effectiveKind = if (sorted.any { it.second == CycleGovernanceKind.DEBT }) {
                    CycleGovernanceKind.DEBT
                } else {
                    CycleGovernanceKind.INTENTIONAL
                },
            )
        }
        return GovernanceMatchResult(recordMatches, coverage)
    }

    private fun repairIsActionable(
        document: CycleGovernanceDocument,
        recordId: String,
        updated: CycleGovernanceRecord,
        evidence: GovernanceEvidenceSnapshot,
    ): Boolean {
        val candidateDocument = document.copy(records = document.records.toMutableMap().apply {
            put(recordId, updated)
        }.toSortedMap())
        if (validator.validate(candidateDocument).any {
                it.recordId == recordId && it.severity == GovernanceIssueSeverity.ERROR
            }
        ) return false
        return matchRecord(recordId, updated, evidence).status == GovernanceRecordStatus.ACTIVE
    }

    private fun matchRecord(
        recordId: String,
        record: CycleGovernanceRecord,
        evidence: GovernanceEvidenceSnapshot,
    ): GovernanceRecordMatch {
        val validationIssues = validator.validateRecord(recordId, record)
        if (validationIssues.any { it.severity == GovernanceIssueSeverity.ERROR }) {
            return GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.INVALID,
                diagnostics = validationIssues.map { "${it.code}: ${it.message}" },
            )
        }

        val backendSources = evidence.sources.filter { it.backend == record.analysisSource.backend }
        if (backendSources.isEmpty()) return GovernanceRecordMatch(
            recordId,
            if (evidence.evaluationComplete) GovernanceRecordStatus.UNSUPPORTED else GovernanceRecordStatus.NOT_IN_ANALYSIS,
            diagnostics = if (evidence.evaluationComplete) {
                listOf("No ${record.analysisSource.backend} evidence is available in the repository evaluation.")
            } else {
                listOf("This repository record belongs to a backend outside the current Analysis Source and was not evaluated.")
            },
        )
        val languageSources = backendSources.filter { record.analysisSource.language in it.languages }
        if (languageSources.isEmpty()) {
            return GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.UNSUPPORTED,
                diagnostics = listOf("The current analysis does not provide ${record.analysisSource.language} evidence."),
            )
        }
        val scopeSources = languageSources.filter { record.scope in it.supportedScopes }
        if (scopeSources.isEmpty()) {
            return GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.UNSUPPORTED,
                diagnostics = listOf("The current analysis does not support ${record.scope} governance."),
            )
        }
        val usableSources = scopeSources.filter { it.fresh }
        if (usableSources.isEmpty()) {
            return GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.UNSUPPORTED,
                diagnostics = listOf(
                    scopeSources.firstNotNullOfOrNull { it.freshnessDiagnostic }
                        ?: "Analysis evidence is stale; governance fails closed.",
                ),
            )
        }

        val sourceNodes = matchingNodes(record, GovernanceOwnerSide.SOURCE, evidence, usableSources)
        if (sourceNodes.isEmpty()) {
            if (!evidenceCovers(record, record.source, usableSources, evidence.caseSensitive)) {
                return notInAnalysis(recordId)
            }
            return GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.MISSING_SOURCE,
                diagnostics = listOf("The source identity no longer exists in the evaluated repository evidence."),
                retargetCandidates = candidates(record, GovernanceOwnerSide.SOURCE, evidence, usableSources),
            )
        }
        val targetNodes = matchingNodes(record, GovernanceOwnerSide.TARGET, evidence, usableSources)
        if (targetNodes.isEmpty()) {
            if (!evidenceCovers(record, record.target, usableSources, evidence.caseSensitive)) {
                return notInAnalysis(recordId)
            }
            return GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.MISSING_TARGET,
                diagnostics = listOf("The target identity no longer exists in the evaluated repository evidence."),
                retargetCandidates = candidates(record, GovernanceOwnerSide.TARGET, evidence, usableSources),
            )
        }
        val detectedAmbiguity = listOfNotNull(
            ambiguityDiagnostic(record, GovernanceOwnerSide.SOURCE, sourceNodes, evidence.caseSensitive),
            ambiguityDiagnostic(record, GovernanceOwnerSide.TARGET, targetNodes, evidence.caseSensitive),
        )
        val hasModuleAmbiguity =
            isModuleAmbiguous(record.source, sourceNodes, evidence.caseSensitive) ||
                isModuleAmbiguous(record.target, targetNodes, evidence.caseSensitive)
        // A concrete reference ID is the final disambiguator for member/declaration evidence.
        // Module ambiguity remains blocking because module identity is part of that stable ID.
        val ambiguityDiagnostics = if (record.scope == GovernanceScope.REFERENCE && !hasModuleAmbiguity) {
            emptyList()
        } else {
            detectedAmbiguity
        }
        if (ambiguityDiagnostics.isNotEmpty()) {
            return GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.AMBIGUOUS,
                diagnostics = ambiguityDiagnostics,
                moduleCandidates = if (hasModuleAmbiguity) {
                    modulePairCandidates(record, evidence, usableSources)
                } else {
                    emptyList()
                },
            )
        }

        val matchingReferences = evidence.references.asSequence()
            .filter { reference -> sourceIsUsable(reference.analysisSourceId, usableSources, evidence.caseSensitive) }
            .filter { referenceMatches(record, it, evidence.caseSensitive) }
            .toList()
        val matched = matchingReferences.asSequence()
            .map { reference -> reference.id }
            .distinct()
            .sorted()
            .toList()

        if (record.scope == GovernanceScope.REFERENCE) {
            val expected = record.referenceIds.toSortedSet()
            val presentExpected = sortedSetOf<String>()
            val presentActual = sortedSetOf<String>()
            matchingReferences.forEach { reference ->
                val aliases = setOf(
                    reference.id,
                    GovernanceIds.referenceId(
                        analysisSourceId = record.analysisSource.id,
                        backend = reference.backend,
                        sourceLanguage = reference.sourceLanguage,
                        targetLanguage = reference.targetLanguage,
                        source = reference.source,
                        target = reference.target,
                        dependencyKind = reference.dependencyKind,
                    ),
                )
                if (aliases.any { it in expected }) {
                    presentExpected += aliases.filter { it in expected }
                    presentActual += reference.id
                }
            }
            return when {
                presentExpected.size < expected.size && !evidenceCoversDependency(record, usableSources, evidence.caseSensitive) ->
                    notInAnalysis(recordId)
                presentExpected.isEmpty() -> GovernanceRecordMatch(
                    recordId,
                    GovernanceRecordStatus.RESOLVED,
                    diagnostics = listOf("None of the recorded concrete references exists anymore."),
                )
                presentExpected.size < expected.size -> GovernanceRecordMatch(
                    recordId,
                    GovernanceRecordStatus.PARTIAL,
                    matchedReferenceIds = presentActual.toList(),
                    diagnostics = listOf("Only ${presentExpected.size} of ${expected.size} recorded concrete references still matches; governance fails closed."),
                )
                else -> GovernanceRecordMatch(recordId, GovernanceRecordStatus.ACTIVE, presentActual.toList())
            }
        }

        return if (matched.isEmpty()) {
            if (!evidenceCoversDependency(record, usableSources, evidence.caseSensitive)) {
                notInAnalysis(recordId)
            } else GovernanceRecordMatch(
                recordId,
                GovernanceRecordStatus.RESOLVED,
                diagnostics = listOf("Source and target still exist, but the governed dependency evidence is gone."),
            )
        } else {
            GovernanceRecordMatch(recordId, GovernanceRecordStatus.ACTIVE, matched)
        }
    }

    private fun matchingNodes(
        record: CycleGovernanceRecord,
        side: GovernanceOwnerSide,
        evidence: GovernanceEvidenceSnapshot,
        usableSources: List<GovernanceEvidenceSource>,
    ): List<GovernanceEvidenceNode> {
        val selector = if (side == GovernanceOwnerSide.SOURCE) record.source else record.target
        return evidence.nodes.filter { node ->
            sourceIsUsable(node.analysisSourceId, usableSources, evidence.caseSensitive) &&
                node.backend == record.analysisSource.backend &&
                (side != record.ownerSide || node.language == record.analysisSource.language) &&
                identityMatches(selector, node.identity, evidence.caseSensitive)
        }
    }

    private fun referenceMatches(
        record: CycleGovernanceRecord,
        reference: GovernanceEvidenceReference,
        caseSensitive: Boolean,
    ): Boolean {
        if (reference.backend != record.analysisSource.backend) return false
        val ownerLanguage = if (record.ownerSide == GovernanceOwnerSide.SOURCE) {
            reference.sourceLanguage
        } else {
            reference.targetLanguage
        }
        if (ownerLanguage != record.analysisSource.language) return false
        if (!identityMatches(record.source, reference.source, caseSensitive)) return false
        if (!identityMatches(record.target, reference.target, caseSensitive)) return false
        if (record.dependencyKind != null && record.dependencyKind != reference.dependencyKind) return false
        return true
    }

    private fun sourceIsUsable(
        analysisSourceId: String,
        usableSources: List<GovernanceEvidenceSource>,
        caseSensitive: Boolean,
    ): Boolean = usableSources.any { equalsText(it.id, analysisSourceId, caseSensitive) }

    private fun evidenceCovers(
        record: CycleGovernanceRecord,
        identity: GovernanceIdentity,
        usableSources: List<GovernanceEvidenceSource>,
        caseSensitive: Boolean,
    ): Boolean {
        if (usableSources.any { it.repositoryComplete }) return true
        if (usableSources.any { equalsText(it.id, record.analysisSource.id, caseSensitive) }) return true
        if (record.analysisSource.backend == GovernanceBackend.JVM_BYTECODE && identity.module != null) {
            return usableSources.any { source ->
                source.includedJvmModules.any { equalsText(it, identity.module, caseSensitive) }
            }
        }
        return false
    }

    private fun evidenceCoversDependency(
        record: CycleGovernanceRecord,
        usableSources: List<GovernanceEvidenceSource>,
        caseSensitive: Boolean,
    ): Boolean =
        usableSources.any { it.repositoryComplete } ||
            usableSources.any { equalsText(it.id, record.analysisSource.id, caseSensitive) } ||
            (
                record.analysisSource.backend == GovernanceBackend.JVM_BYTECODE &&
                    record.source.module != null &&
                    record.target.module != null &&
                    usableSources.any { source ->
                        source.includedJvmModules.any { equalsText(it, record.source.module, caseSensitive) } &&
                            source.includedJvmModules.any { equalsText(it, record.target.module, caseSensitive) }
                    }
                )

    private fun notInAnalysis(recordId: String) = GovernanceRecordMatch(
        recordId = recordId,
        status = GovernanceRecordStatus.NOT_IN_ANALYSIS,
        diagnostics = listOf(
            "This repository record is outside the current partial analysis and was not evaluated.",
        ),
    )

    private fun identityMatches(
        selector: GovernanceIdentity,
        actual: GovernanceIdentity,
        caseSensitive: Boolean,
    ): Boolean {
        if (!equalsText(selector.architectureUnit, actual.architectureUnit, caseSensitive)) return false
        if (selector.module != null && !equalsText(selector.module, actual.module, caseSensitive)) return false
        if (selector.type != null && !equalsText(selector.type, actual.type, caseSensitive)) return false
        if (selector.sourceFile != null && !equalsText(selector.sourceFile, actual.sourceFile, caseSensitive)) return false
        if (selector.member != null) {
            val actualMember = actual.member ?: return false
            if (!equalsText(selector.member.name, actualMember.name, caseSensitive)) return false
            if (selector.member.descriptor != null &&
                !equalsText(selector.member.descriptor, actualMember.descriptor, caseSensitive)
            ) return false
        }
        return true
    }

    private fun equalsText(expected: String, actual: String?, caseSensitive: Boolean): Boolean =
        actual != null && if (caseSensitive) expected == actual else expected.equals(actual, ignoreCase = true)

    private fun ambiguityDiagnostic(
        record: CycleGovernanceRecord,
        side: GovernanceOwnerSide,
        nodes: List<GovernanceEvidenceNode>,
        caseSensitive: Boolean,
    ): String? {
        val selector = if (side == GovernanceOwnerSide.SOURCE) record.source else record.target
        val sideLabel = side.name.lowercase()
        val moduleLocations = nodes.map { it.identity.module }.distinct()
        if (selector.module == null && moduleLocations.size > 1) {
            val locations = moduleLocations
                .map { it ?: "unattributed bytecode" }
                .sorted()
                .joinToString(", ")
            return "The $sideLabel selector '${selector.architectureUnit}' omits its JVM module and matches multiple module locations: $locations. Add the intended module to the $sideLabel identity."
        }
        if (record.scope == GovernanceScope.PACKAGE || record.scope == GovernanceScope.SOURCE_FOLDER) return null
        val isNarrowed = selector.type != null || selector.sourceFile != null || selector.member != null
        if (!isNarrowed || nodes.map { identityKey(it.identity, caseSensitive) }.distinct().size <= 1) return null

        val repair = when {
            selector.member != null && selector.member.descriptor == null &&
                nodes.mapNotNull { it.identity.member?.descriptor }.distinct().size > 1 ->
                "Add the JVM member descriptor to select one overload."
            selector.type == null && nodes.mapNotNull { it.identity.type }.distinct().size > 1 ->
                "Add the owner-side type to select one declaration."
            selector.sourceFile == null && nodes.mapNotNull { it.identity.sourceFile }.distinct().size > 1 ->
                "Add the owner-side source file to select one file."
            else -> "Narrow the $sideLabel identity until it selects exactly one declaration."
        }
        return "The $sideLabel selector '${selector.diagnosticLabel()}' matches multiple concrete identities. $repair"
    }

    private fun GovernanceIdentity.diagnosticLabel(): String = buildString {
        append(architectureUnit)
        type?.let { append(" :: ").append(it) }
        sourceFile?.let { append(" [").append(it).append(']') }
        member?.let { append(" # ").append(it.name).append(it.descriptor.orEmpty()) }
    }

    private fun isModuleAmbiguous(
        selector: GovernanceIdentity,
        nodes: List<GovernanceEvidenceNode>,
        caseSensitive: Boolean,
    ): Boolean {
        if (selector.module != null) return false
        return nodes.map { normalizedModule(it.identity.module, caseSensitive) }.distinct().size > 1
    }

    private fun modulePairCandidates(
        record: CycleGovernanceRecord,
        evidence: GovernanceEvidenceSnapshot,
        usableSources: List<GovernanceEvidenceSource>,
    ): List<GovernanceModulePairCandidate> = evidence.references.asSequence()
        .filter { reference -> sourceIsUsable(reference.analysisSourceId, usableSources, evidence.caseSensitive) }
        .filter { reference -> referenceMatches(record, reference, evidence.caseSensitive) }
        .mapNotNull { reference ->
            val sourceModule = reference.source.module ?: return@mapNotNull null
            val targetModule = reference.target.module ?: return@mapNotNull null
            Triple(sourceModule, targetModule, reference)
        }
        .groupBy { (sourceModule, targetModule) ->
            normalizedModule(sourceModule, evidence.caseSensitive) to
                normalizedModule(targetModule, evidence.caseSensitive)
        }
        .values
        .mapNotNull { candidates ->
            val (sourceModule, targetModule) = candidates.first()
            val qualifiedRecord = record.copy(
                source = record.source.copy(module = sourceModule),
                target = record.target.copy(module = targetModule),
            )
            val qualifiedMatch = matchRecord("module-candidate", qualifiedRecord, evidence)
            if (qualifiedMatch.status != GovernanceRecordStatus.ACTIVE) return@mapNotNull null
            GovernanceModulePairCandidate(
                sourceModule = sourceModule,
                targetModule = targetModule,
                matchingReferenceCount = qualifiedMatch.matchedReferenceIds.size,
            )
        }
        .sortedWith(compareBy(GovernanceModulePairCandidate::sourceModule, GovernanceModulePairCandidate::targetModule))

    private fun normalizedModule(module: String?, caseSensitive: Boolean): String =
        (module ?: "").let { if (caseSensitive) it else it.lowercase() }

    private fun candidates(
        record: CycleGovernanceRecord,
        side: GovernanceOwnerSide,
        evidence: GovernanceEvidenceSnapshot,
        usableSources: List<GovernanceEvidenceSource>,
    ): List<GovernanceRetargetCandidate> {
        val missing = if (side == GovernanceOwnerSide.SOURCE) record.source else record.target
        val desiredLeaf = leaf(missing)
        return evidence.nodes.asSequence()
            .filter { sourceIsUsable(it.analysisSourceId, usableSources, evidence.caseSensitive) }
            .filter { it.backend == record.analysisSource.backend }
            .filter { candidate -> sameSelectorShape(missing, candidate.identity) }
            .filter { candidate ->
                missing.module == null || equalsText(missing.module, candidate.identity.module, evidence.caseSensitive)
            }
            .filter { candidate ->
                desiredLeaf.equals(leaf(candidate.identity), ignoreCase = true) ||
                    candidate.identity.architectureUnit.substringAfterLast('/').substringAfterLast('.')
                        .equals(missing.architectureUnit.substringAfterLast('/').substringAfterLast('.'), ignoreCase = true)
            }
            .distinctBy { identityKey(it.identity, evidence.caseSensitive) }
            .sortedBy { identityKey(it.identity, evidence.caseSensitive) }
            .take(5)
            .map { GovernanceRetargetCandidate(side, it.identity, "Similar identity; review before retargeting.") }
            .toList()
    }

    private fun sameSelectorShape(missing: GovernanceIdentity, candidate: GovernanceIdentity): Boolean =
        (missing.type == null) == (candidate.type == null) &&
            (missing.sourceFile == null) == (candidate.sourceFile == null) &&
            (missing.member == null) == (candidate.member == null)

    private fun leaf(identity: GovernanceIdentity): String =
        identity.member?.name
            ?: identity.type?.substringAfterLast('.')
            ?: identity.sourceFile?.substringAfterLast('/')
            ?: identity.architectureUnit.substringAfterLast('/').substringAfterLast('.')

    private fun identityKey(identity: GovernanceIdentity, caseSensitive: Boolean): String {
        val key = listOf(
            identity.architectureUnit,
            identity.type.orEmpty(),
            identity.sourceFile.orEmpty(),
            identity.member?.name.orEmpty(),
            identity.member?.descriptor.orEmpty(),
            identity.module.orEmpty(),
        ).joinToString("|")
        return if (caseSensitive) key else key.lowercase()
    }
}
