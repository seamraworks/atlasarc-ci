package io.atlasarc.evaluator

import io.atlasarc.governance.GovernanceBackend
import java.nio.file.Path

enum class EvaluatorOutputFormat { HUMAN, JSON, SARIF }

sealed interface EvaluatorCommand {
    data object Help : EvaluatorCommand
    data object Version : EvaluatorCommand
    data class Evaluate(val invocation: EvaluatorInvocation) : EvaluatorCommand
}

data class EvaluatorInvocation(
    val configPath: Path?,
    val directConfig: EvaluatorConfig?,
    val configBase: Path,
    val format: EvaluatorOutputFormat,
    val output: Path?,
)

object EvaluatorCli {
    fun parse(arguments: Array<String>, currentDirectory: Path): EvaluatorCommand {
        val args = arguments.toMutableList()
        if (args.firstOrNull() == "evaluate") args.removeAt(0)
        if (args.isEmpty() || args.singleOrNull() in setOf("--help", "-h", "help")) {
            return EvaluatorCommand.Help
        }
        if (args.singleOrNull() == "--version" || args.singleOrNull() == "version") {
            return EvaluatorCommand.Version
        }

        var configPath: Path? = null
        var repositoryRoot = "."
        var sourceId: String? = null
        var backend: GovernanceBackend? = null
        var sourceRoot = "."
        val classDirectories = mutableListOf<EvaluatorPathSpec>()
        val sourceRoots = mutableListOf<EvaluatorPathSpec>()
        var dependencyCruiser: String? = null
        var format = EvaluatorOutputFormat.HUMAN
        var output: Path? = null

        fun requireValue(index: Int, option: String): String =
            args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
                ?: throw EvaluatorConfigurationException("$option requires a value.")

        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--config" -> {
                    configPath = Path.of(requireValue(index, option))
                    index += 2
                }
                "--repository-root" -> {
                    repositoryRoot = requireValue(index, option)
                    index += 2
                }
                "--source-id" -> {
                    sourceId = requireValue(index, option)
                    index += 2
                }
                "--backend" -> {
                    backend = parseBackend(requireValue(index, option))
                    index += 2
                }
                "--root" -> {
                    sourceRoot = requireValue(index, option)
                    index += 2
                }
                "--classes" -> {
                    classDirectories += parsePathSpec(requireValue(index, option))
                    index += 2
                }
                "--source-root" -> {
                    sourceRoots += parsePathSpec(requireValue(index, option))
                    index += 2
                }
                "--dependency-cruiser" -> {
                    dependencyCruiser = requireValue(index, option)
                    index += 2
                }
                "--format" -> {
                    format = when (requireValue(index, option).lowercase()) {
                        "human", "text" -> EvaluatorOutputFormat.HUMAN
                        "json" -> EvaluatorOutputFormat.JSON
                        "sarif" -> EvaluatorOutputFormat.SARIF
                        else -> throw EvaluatorConfigurationException("--format must be human, json, or sarif.")
                    }
                    index += 2
                }
                "--output" -> {
                    output = Path.of(requireValue(index, option))
                    index += 2
                }
                "--help", "-h" -> return EvaluatorCommand.Help
                "--version" -> return EvaluatorCommand.Version
                else -> throw EvaluatorConfigurationException("Unknown evaluator option '$option'.")
            }
        }

        if (configPath != null) {
            val directOptionsUsed = sourceId != null || backend != null || classDirectories.isNotEmpty() ||
                sourceRoots.isNotEmpty() || dependencyCruiser != null ||
                repositoryRoot != "." || sourceRoot != "."
            if (directOptionsUsed) {
                throw EvaluatorConfigurationException(
                    "--config cannot be combined with direct analysis-source options.",
                )
            }
            val resolvedConfig = resolve(currentDirectory, configPath)
            return EvaluatorCommand.Evaluate(
                EvaluatorInvocation(
                    configPath = resolvedConfig,
                    directConfig = null,
                    configBase = resolvedConfig.parent ?: currentDirectory,
                    format = format,
                    output = output?.let { resolve(currentDirectory, it) },
                ),
            )
        }

        val resolvedBackend = backend
            ?: throw EvaluatorConfigurationException("Direct evaluation requires --backend.")
        val resolvedSourceId = sourceId
            ?: throw EvaluatorConfigurationException("Direct evaluation requires --source-id.")
        val source = EvaluatorSourceConfig(
            id = resolvedSourceId,
            backend = resolvedBackend,
            root = sourceRoot,
            classDirectories = classDirectories,
            sourceRoots = sourceRoots,
            dependencyCruiserJson = dependencyCruiser,
        )
        val config = EvaluatorConfig(repositoryRoot = repositoryRoot, sources = listOf(source))
        EvaluatorConfigCodec.validate(config)
        return EvaluatorCommand.Evaluate(
            EvaluatorInvocation(
                configPath = null,
                directConfig = config,
                configBase = currentDirectory,
                format = format,
                output = output?.let { resolve(currentDirectory, it) },
            ),
        )
    }

    private fun parseBackend(value: String): GovernanceBackend = when (value.lowercase()) {
        "jvm", "jvm-bytecode" -> GovernanceBackend.JVM_BYTECODE
        "typescript", "typescript-artifact", "ts" -> GovernanceBackend.TYPESCRIPT_ARTIFACT
        else -> throw EvaluatorConfigurationException(
            "--backend must be jvm-bytecode or typescript-artifact.",
        )
    }

    private fun parsePathSpec(value: String): EvaluatorPathSpec {
        val separator = value.indexOf('=')
        return if (separator > 0) {
            EvaluatorPathSpec(
                path = value.substring(separator + 1),
                module = value.substring(0, separator).takeIf(String::isNotBlank),
            )
        } else {
            EvaluatorPathSpec(value)
        }
    }

    private fun resolve(base: Path, path: Path): Path =
        (if (path.isAbsolute) path else base.resolve(path)).toAbsolutePath().normalize()
}
