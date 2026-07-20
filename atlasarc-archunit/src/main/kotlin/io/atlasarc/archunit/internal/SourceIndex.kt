package io.atlasarc.archunit.internal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal data class SourceRoot(val path: Path, val module: String)

internal data class SourceFileInfo(
    val packageName: String,
    val fileName: String,
    val absolutePath: String,
    val module: String,
) {
    fun moduleRef(): JvmModuleRef = module.takeIf(String::isNotBlank)?.let(JvmModuleRef::Named)
        ?: JvmModuleRef.Moduleless
}

internal class SourceIndex private constructor(
    val allFiles: List<SourceFileInfo>,
) {
    fun resolveSourceFile(packageName: String, sourceFileName: String?, moduleName: String? = null): String {
        val fileName = sourceFileName ?: return ""
        val matches = allFiles.filter { it.packageName == packageName && it.fileName == fileName }
        moduleName?.let { module -> matches.singleOrNull { it.module == module }?.let { return it.absolutePath } }
        return matches.singleOrNull()?.absolutePath ?: fileName
    }

    fun moduleRefFor(packageName: String, sourceFileName: String?): JvmModuleRef? {
        val fileName = sourceFileName ?: return null
        val owners = allFiles.filter { it.packageName == packageName && it.fileName == fileName }
            .mapTo(linkedSetOf(), SourceFileInfo::moduleRef)
        return when (owners.size) {
            0 -> null
            1 -> owners.single()
            else -> JvmModuleRef.Unattributed
        }
    }

    fun uniqueModuleRefForPackage(packageName: String): JvmModuleRef? {
        val owners = allFiles.filter { it.packageName == packageName }
            .mapTo(linkedSetOf(), SourceFileInfo::moduleRef)
        return when (owners.size) {
            0 -> null
            1 -> owners.single()
            else -> JvmModuleRef.Unattributed
        }
    }

    companion object {
        fun build(roots: List<SourceRoot>): SourceIndex {
            val files = roots.sortedWith(compareBy({ it.module }, { it.path.toString() }))
                .flatMap(::scan)
                .sortedWith(compareBy({ it.packageName }, { it.fileName }, { it.module }, { it.absolutePath }))
            return SourceIndex(files)
        }

        private fun scan(root: SourceRoot): List<SourceFileInfo> {
            val path = root.path.toAbsolutePath().normalize()
            require(path.isDirectory()) { "A configured Java/Kotlin source root is missing: $path" }
            return Files.walk(path).use { stream ->
                stream.filter {
                    it.isRegularFile() && (it.extension.equals("java", true) || it.extension.equals("kt", true))
                }.sorted().map { file ->
                    val text = Files.readString(file)
                    val packageName = PACKAGE.find(text)?.groupValues?.get(1)
                        ?: throw IllegalArgumentException("A configured Java/Kotlin source file has no package declaration: $file")
                    SourceFileInfo(packageName, file.fileName.toString(), file.toString(), root.module)
                }.toList()
            }
        }

        private val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
    }
}
