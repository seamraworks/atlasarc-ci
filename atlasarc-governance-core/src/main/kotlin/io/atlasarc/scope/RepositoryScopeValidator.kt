package io.atlasarc.scope

import io.atlasarc.governance.GovernanceIssueSeverity

class RepositoryScopeValidator {
    fun validate(document: RepositoryScopeDocument): List<RepositoryScopeIssue> {
        val issues = mutableListOf<RepositoryScopeIssue>()
        if (document.schema != REPOSITORY_SCOPE_SCHEMA_URI) {
            issues += issue("invalid-schema-uri", "The repository scope schema URI is not supported.", field = "\$schema")
        }
        if (document.schemaVersion != REPOSITORY_SCOPE_SCHEMA_VERSION) {
            issues += issue(
                "unsupported-schema-version",
                "Schema version ${document.schemaVersion} is not supported; expected $REPOSITORY_SCOPE_SCHEMA_VERSION.",
                field = "schemaVersion",
            )
        }

        val selectors = mutableMapOf<String, String>()
        document.exclusions.toSortedMap().forEach { (ruleId, exclusion) ->
            if (!RULE_ID.matches(ruleId)) {
                issues += issue(
                    "invalid-rule-id",
                    "Repository scope rule IDs must use lowercase letters, digits, dots, underscores, or hyphens.",
                    ruleId,
                    "exclusions.$ruleId",
                )
            }
            if (exclusion.reason.isBlank()) {
                issues += issue("blank-reason", "A repository scope exclusion requires a reason.", ruleId, "reason")
            } else if (exclusion.reason.length > 1_000) {
                issues += issue("reason-too-long", "A repository scope exclusion reason cannot exceed 1000 characters.", ruleId, "reason")
            }

            validateSelector(ruleId, exclusion.selector, issues)
            val selectorKey = listOf(
                exclusion.selector.kind.name,
                exclusion.selector.module ?: "<moduleless>",
                exclusion.selector.pattern,
            ).joinToString("|")
            selectors.putIfAbsent(selectorKey, ruleId)?.let { previous ->
                issues += issue(
                    "duplicate-selector",
                    "Repository scope rules '$previous' and '$ruleId' use the same selector.",
                    ruleId,
                    "selector",
                )
            }
        }
        return issues
    }

    private fun validateSelector(
        ruleId: String,
        selector: RepositoryScopeSelector,
        issues: MutableList<RepositoryScopeIssue>,
    ) {
        if (selector.pattern.isBlank()) {
            issues += issue("blank-pattern", "A repository scope selector pattern cannot be blank.", ruleId, "selector.pattern")
            return
        }
        if (selector.pattern != selector.pattern.trim()) {
            issues += issue("non-canonical-pattern", "A repository scope selector pattern cannot have surrounding whitespace.", ruleId, "selector.pattern")
        }
        if ('\\' in selector.pattern) {
            issues += issue("non-canonical-pattern", "Use canonical separators in repository scope patterns.", ruleId, "selector.pattern")
        }

        val separator = when (selector.kind) {
            RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN -> '.'
            RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN -> '/'
        }
        if (selector.kind == RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN && '/' in selector.pattern) {
            issues += issue("invalid-pattern-separator", "JVM package patterns use '.' separators.", ruleId, "selector.pattern")
        }
        val segments = selector.pattern.split(separator)
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            issues += issue("invalid-pattern-segment", "Repository scope patterns cannot contain empty, '.' or '..' segments.", ruleId, "selector.pattern")
        }
        segments.filter { '*' in it }.filterNot { it == "*" || it == "**" }.forEach {
            issues += issue(
                "invalid-pattern-wildcard",
                "Wildcards must occupy a complete segment: '*' matches one segment and '**' matches zero or more.",
                ruleId,
                "selector.pattern",
            )
        }

        when (selector.kind) {
            RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN -> {
                if (selector.module?.isBlank() == true) {
                    issues += issue("blank-module", "A JVM module selector cannot be blank.", ruleId, "selector.module")
                }
            }
            RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN -> {
                if (selector.module != null) {
                    issues += issue(
                        "typescript-module-selector",
                        "TypeScript source-folder selectors do not carry JVM module ownership.",
                        ruleId,
                        "selector.module",
                    )
                }
            }
        }
    }

    private fun issue(code: String, message: String, ruleId: String? = null, field: String? = null) =
        RepositoryScopeIssue(code, message, GovernanceIssueSeverity.ERROR, ruleId, field)

    private companion object {
        val RULE_ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}
