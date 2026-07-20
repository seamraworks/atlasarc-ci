import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.zip.ZipFile

@CacheableTask
abstract class GenerateChecksumsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifacts: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val lines = artifacts.files.sortedBy { it.name }.joinToString("") { artifact ->
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
                .joinToString("") { "%02x".format(it) }
            "$digest  ${artifact.name}\n"
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines, Charsets.UTF_8)
        }
    }
}

@CacheableTask
abstract class VerifyArchiveBoundaryTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: ConfigurableFileCollection

    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val violations = archive.singleFile.let(::ZipFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .filter { path -> forbiddenPrefixes.get().any(path::startsWith) }
                .toList()
        }
        check(violations.isEmpty()) {
            "AtlasArc CI crosses the IDE boundary: ${violations.joinToString()}"
        }
    }
}
