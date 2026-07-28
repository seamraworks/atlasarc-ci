package io.atlasarc.governance

const val MAX_CYCLE_GOVERNANCE_RECORDS: Int = 10_000

class CycleGovernanceValidator {
    fun validate(document: CycleGovernanceDocument): List<GovernanceValidationIssue> {
        val issues = mutableListOf<GovernanceValidationIssue>()
        if (document.schemaVersion != CYCLE_GOVERNANCE_SCHEMA_VERSION) {
            issues += issue(
                code = "unsupported-schema-version",
                field = "schemaVersion",
                message = "Schema version ${document.schemaVersion} is not supported; expected $CYCLE_GOVERNANCE_SCHEMA_VERSION.",
            )
        }
        if (document.schema != CYCLE_GOVERNANCE_SCHEMA_URI) {
            issues += issue(
                code = "unexpected-schema-uri",
                field = "\$schema",
                message = "Schema URI must be $CYCLE_GOVERNANCE_SCHEMA_URI.",
            )
        }
        if (document.records.size > MAX_CYCLE_GOVERNANCE_RECORDS) {
            issues += issue("too-many-records", "records", "At most $MAX_CYCLE_GOVERNANCE_RECORDS governance records are supported.")
        }

        document.records.toSortedMap().forEach { (id, record) ->
            issues += validateRecord(id, record)
        }

        document.records.entries
            .groupBy { selectorKey(it.value) }
            .values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                val ids = duplicates.map { it.key }.sorted()
                duplicates.forEach { duplicate ->
                    issues += issue(
                        code = "duplicate-selector",
                        field = "records.${duplicate.key}",
                        message = "Record duplicates the selector used by ${ids.filterNot { it == duplicate.key }.joinToString()}.",
                        recordId = duplicate.key,
                    )
                }
            }

        return issues.sortedWith(compareBy({ it.recordId ?: "" }, { it.field ?: "" }, { it.code }))
    }

    fun validateRecord(id: String, record: CycleGovernanceRecord): List<GovernanceValidationIssue> {
        val issues = mutableListOf<GovernanceValidationIssue>()
        fun add(code: String, field: String, message: String) {
            issues += issue(code, "records.$id.$field", message, id)
        }

        if (!RECORD_ID.matches(id)) add("invalid-record-id", "id", "Record ID must match ${RECORD_ID.pattern}.")
        if (CycleDebtBaselineRecordIds.isReserved(id) && !CycleDebtBaselineRecordIds.isManaged(id, record)) {
            add(
                "invalid-baseline-record-id",
                "id",
                "Reserved cycle-baseline IDs must match their single exact reference and remain source-owned Debt records.",
            )
        }
        bounded(record.analysisSource.id, 1, MAX_IDENTITY, "analysisSource.id", ::add)
        bounded(record.reason, 1, MAX_REASON, "reason", ::add)
        record.ticket?.let { bounded(it, 1, MAX_TICKET, "ticket", ::add) }
        validateIdentity(record.source, "source", ::add)
        validateIdentity(record.target, "target", ::add)

        if (record.source.architectureUnit == record.target.architectureUnit &&
            record.source.type == record.target.type && record.source.member == record.target.member &&
            record.source.module == record.target.module
        ) {
            add("self-selector", "target", "Source and target identities must not be identical.")
        }

        when (record.analysisSource.backend) {
            GovernanceBackend.JVM_BYTECODE -> validateJvm(record, ::add)
            GovernanceBackend.TYPESCRIPT_ARTIFACT -> validateTypeScript(record, ::add)
        }

        if (record.scope == GovernanceScope.REFERENCE && record.referenceIds.isEmpty()) {
            add("missing-reference-ids", "referenceIds", "Reference scope requires at least one stable reference ID.")
        }
        if (record.scope == GovernanceScope.REFERENCE && record.ownerSide != GovernanceOwnerSide.SOURCE) {
            add(
                "noncanonical-reference-owner",
                "ownerSide",
                "Reference scope identifies both sides and must use source ownership as its storage convention.",
            )
        }
        if (record.scope != GovernanceScope.REFERENCE && record.referenceIds.isNotEmpty()) {
            add("unexpected-reference-ids", "referenceIds", "Reference IDs are only valid for reference scope.")
        }
        if (record.referenceIds.size > MAX_REFERENCE_IDS) {
            add("too-many-reference-ids", "referenceIds", "At most $MAX_REFERENCE_IDS reference IDs are allowed.")
        }
        record.referenceIds.forEachIndexed { index, referenceId ->
            bounded(referenceId, 1, MAX_IDENTITY, "referenceIds[$index]", ::add)
        }

        record.display?.let { display ->
            display.source?.let { bounded(it, 1, MAX_DISPLAY, "display.source", ::add) }
            display.target?.let { bounded(it, 1, MAX_DISPLAY, "display.target", ::add) }
            display.sourcePath?.let { validatePath(it, "display.sourcePath", ::add) }
            display.targetPath?.let { validatePath(it, "display.targetPath", ::add) }
        }
        return issues
    }

    private fun validateJvm(record: CycleGovernanceRecord, add: AddIssue) {
        if (record.analysisSource.language == GovernanceLanguage.TYPESCRIPT) {
            add("backend-language-mismatch", "analysisSource.language", "JVM bytecode records must use Java or Kotlin.")
        }
        if (record.scope !in JVM_SCOPES) {
            add("unsupported-scope", "scope", "JVM bytecode does not support ${record.scope} governance.")
        }
        listOf("source" to record.source, "target" to record.target).forEach { (field, identity) ->
            if (identity.member != null && identity.member.descriptor.isNullOrBlank()) {
                add(
                    "missing-member-descriptor",
                    "$field.member.descriptor",
                    "Every JVM member identity requires an exact erased descriptor so overloaded members cannot collide.",
                )
            }
        }
        if (record.scope == GovernanceScope.TYPE && owner(record).type == null) {
            add("missing-owner-type", ownerField(record, "type"), "Type scope requires the owner-side type identity.")
        }
        if (record.scope == GovernanceScope.MEMBER) {
            if (owner(record).type == null) {
                add("missing-owner-type", ownerField(record, "type"), "Member scope requires the owner-side type identity.")
            }
            if (owner(record).member == null) {
                add("missing-owner-member", ownerField(record, "member"), "Member scope requires the owner-side member identity.")
            }
        }
        if (record.source.sourceFile != null) validatePath(record.source.sourceFile, "source.sourceFile", add)
        if (record.target.sourceFile != null) validatePath(record.target.sourceFile, "target.sourceFile", add)
    }

    private fun validateTypeScript(record: CycleGovernanceRecord, add: AddIssue) {
        if (record.analysisSource.language != GovernanceLanguage.TYPESCRIPT) {
            add("backend-language-mismatch", "analysisSource.language", "TypeScript artifact records must use TypeScript.")
        }
        if (record.scope !in TYPESCRIPT_SCOPES) {
            add("unsupported-scope", "scope", "TypeScript artifacts do not support ${record.scope} governance.")
        }
        listOf("source" to record.source, "target" to record.target).forEach { (field, identity) ->
            if (identity.type != null || identity.member != null) {
                add("unsupported-declaration-identity", field, "TypeScript artifact records cannot contain type or member identities.")
            }
            validatePath(identity.architectureUnit, "$field.architectureUnit", add)
            identity.sourceFile?.let { validatePath(it, "$field.sourceFile", add) }
            if (identity.module != null) {
                add("unsupported-module-identity", "$field.module", "TypeScript artifact records cannot contain JVM module identities.")
            }
        }
        if (record.scope == GovernanceScope.SOURCE_FILE && owner(record).sourceFile == null) {
            add("missing-owner-source-file", ownerField(record, "sourceFile"), "Source-file scope requires the owner-side source file.")
        }
    }

    private fun validateIdentity(identity: GovernanceIdentity, field: String, add: AddIssue) {
        bounded(identity.architectureUnit, 1, MAX_IDENTITY, "$field.architectureUnit", add)
        identity.type?.let { bounded(it, 1, MAX_IDENTITY, "$field.type", add) }
        identity.sourceFile?.let { bounded(it, 1, MAX_PATH, "$field.sourceFile", add) }
        identity.member?.let {
            bounded(it.name, 1, MAX_IDENTITY, "$field.member.name", add)
            it.descriptor?.let { descriptor -> bounded(descriptor, 1, MAX_IDENTITY, "$field.member.descriptor", add) }
        }
        identity.module?.let { bounded(it, 1, MAX_IDENTITY, "$field.module", add) }
    }

    private fun validatePath(value: String, field: String, add: AddIssue) {
        bounded(value, 1, MAX_PATH, field, add)
        if (!GovernancePaths.isCanonicalRepositoryRelative(value)) {
            add("non-canonical-path", field, "Path must be repository-relative, use forward slashes, and contain no dot segments.")
        }
    }

    private fun bounded(value: String, min: Int, max: Int, field: String, add: AddIssue) {
        if (value.length !in min..max || value.isBlank()) {
            add("invalid-length", field, "Value must contain between $min and $max non-blank characters.")
        }
        if (value.any { it == '\u0000' || (it.code in 1..8) || (it.code in 11..12) || (it.code in 14..31) }) {
            add("control-character", field, "Value contains an unsupported control character.")
        }
    }

    private fun owner(record: CycleGovernanceRecord): GovernanceIdentity =
        if (record.ownerSide == GovernanceOwnerSide.SOURCE) record.source else record.target

    private fun ownerField(record: CycleGovernanceRecord, suffix: String): String =
        "${if (record.ownerSide == GovernanceOwnerSide.SOURCE) "source" else "target"}.$suffix"

    private fun selectorKey(record: CycleGovernanceRecord): String = listOf(
        record.analysisSource.id,
        record.analysisSource.backend.name,
        record.analysisSource.language.name,
        record.scope.name,
        record.ownerSide.name,
        identityKey(record.source),
        identityKey(record.target),
        record.dependencyKind?.name.orEmpty(),
        record.referenceIds.sorted().joinToString(";"),
    ).joinToString("|")

    private fun identityKey(identity: GovernanceIdentity): String = listOf(
        identity.architectureUnit,
        identity.type.orEmpty(),
        identity.sourceFile.orEmpty(),
        identity.member?.name.orEmpty(),
        identity.member?.descriptor.orEmpty(),
        identity.module.orEmpty(),
    ).joinToString("~")

    private fun issue(
        code: String,
        field: String,
        message: String,
        recordId: String? = null,
    ) = GovernanceValidationIssue(code = code, message = message, recordId = recordId, field = field)

    private fun interface AddIssue {
        operator fun invoke(code: String, field: String, message: String)
    }

    private companion object {
        val RECORD_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")
        val JVM_SCOPES = setOf(GovernanceScope.PACKAGE, GovernanceScope.TYPE, GovernanceScope.MEMBER, GovernanceScope.REFERENCE)
        val TYPESCRIPT_SCOPES = setOf(GovernanceScope.SOURCE_FOLDER, GovernanceScope.SOURCE_FILE, GovernanceScope.REFERENCE)
        const val MAX_IDENTITY = 512
        const val MAX_PATH = 2_048
        const val MAX_REASON = 4_096
        const val MAX_TICKET = 512
        const val MAX_DISPLAY = 1_024
        const val MAX_REFERENCE_IDS = 1_000
    }
}
