package io.atlasarc.governance

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object GovernanceIds {
    fun newRecordId(): String = "cycle-${UUID.randomUUID()}"

    fun referenceId(
        analysisSourceId: String,
        backend: GovernanceBackend,
        sourceLanguage: GovernanceLanguage,
        targetLanguage: GovernanceLanguage,
        source: GovernanceIdentity,
        target: GovernanceIdentity,
        dependencyKind: GovernanceDependencyKind?,
    ): String {
        val canonical = listOf(
            analysisSourceId,
            backend.name,
            sourceLanguage.name,
            targetLanguage.name,
            identityKey(source),
            identityKey(target),
            dependencyKind?.name.orEmpty(),
        ).joinToString(separator = "\u001f") { value -> "${value.length}:$value" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "ref-${digest.take(32)}"
    }

    private fun identityKey(identity: GovernanceIdentity): String {
        val legacyComponents = listOf(
            identity.architectureUnit,
            identity.type.orEmpty(),
            identity.sourceFile.orEmpty(),
            identity.member?.name.orEmpty(),
            identity.member?.descriptor.orEmpty(),
        )
        val components = identity.module
            ?.let { module -> legacyComponents + module }
            ?: legacyComponents
        return components.joinToString(separator = "\u001e") { value -> "${value.length}:$value" }
    }
}
