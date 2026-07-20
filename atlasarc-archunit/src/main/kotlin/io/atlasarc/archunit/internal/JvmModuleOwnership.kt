package io.atlasarc.archunit.internal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

internal sealed interface JvmModuleRef {
    val stableName: String?

    data class Named(override val stableName: String) : JvmModuleRef
    data object Moduleless : JvmModuleRef { override val stableName: String? = null }
    data object Unattributed : JvmModuleRef { override val stableName: String? = null }
}

internal data class ModuleClassRoot(val path: Path, val module: String)

internal class CompiledClassOwnershipIndex private constructor(
    private val modulesByClass: Map<String, Set<JvmModuleRef>>,
) {
    fun moduleRefFor(className: String): JvmModuleRef? = when (val owners = modulesByClass[className].orEmpty()) {
        emptySet<JvmModuleRef>() -> null
        else -> owners.singleOrNull() ?: JvmModuleRef.Unattributed
    }

    companion object {
        val EMPTY = CompiledClassOwnershipIndex(emptyMap())

        fun build(roots: List<ModuleClassRoot>): CompiledClassOwnershipIndex {
            val ownership = linkedMapOf<String, MutableSet<JvmModuleRef>>()
            roots.sortedWith(compareBy({ it.module }, { it.path.toString() })).forEach { configured ->
                val root = configured.path.toAbsolutePath().normalize()
                if (!Files.isDirectory(root)) return@forEach
                val owner = configured.module.takeIf(String::isNotBlank)
                    ?.let(JvmModuleRef::Named)
                    ?: JvmModuleRef.Moduleless
                Files.walk(root).use { files ->
                    files.filter { it.isRegularFile() && it.extension.equals("class", ignoreCase = true) }
                        .forEach { file ->
                            val className = root.relativize(file).toString()
                                .replace('\\', '.')
                                .replace('/', '.')
                                .removeSuffix(".class")
                            ownership.getOrPut(className) { linkedSetOf() } += owner
                        }
                }
            }
            return CompiledClassOwnershipIndex(ownership.mapValues { it.value.toSet() })
        }
    }
}
