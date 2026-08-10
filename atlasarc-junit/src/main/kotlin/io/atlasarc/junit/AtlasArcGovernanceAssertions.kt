package io.atlasarc.junit

import io.atlasarc.evaluator.EvaluatorApplication
import io.atlasarc.evaluator.EvaluatorExitCode
import org.opentest4j.AssertionFailedError
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

/** JUnit Jupiter assertions backed by the configured AtlasArc.io evaluator. */
object AtlasArcGovernanceAssertions {
    private val defaultConfigPath: Path = Path.of(".atlasarc/evaluator.json")

    /** Evaluates `.atlasarc/evaluator.json` relative to the current test directory. */
    @JvmStatic
    fun assertGovernance() {
        assertGovernance(defaultConfigPath, Path.of("."))
    }

    /** Evaluates the supplied configuration relative to the current test directory. */
    @JvmStatic
    fun assertGovernance(configPath: Path) {
        assertGovernance(configPath, Path.of("."))
    }

    /** Evaluates the supplied configuration and fails the current test unless the verdict is clean. */
    @JvmStatic
    fun assertGovernance(configPath: Path, currentDirectory: Path) {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = PrintStream(stdout, true, UTF_8).use { out ->
            PrintStream(stderr, true, UTF_8).use { err ->
                EvaluatorApplication(out = out, err = err).run(
                    arrayOf("evaluate", "--config", configPath.toString(), "--format", "human"),
                    currentDirectory,
                )
            }
        }
        if (exitCode == EvaluatorExitCode.CLEAN) return

        val output = stdout.toString(UTF_8).trim()
        val errors = stderr.toString(UTF_8).trim()
        val details = listOf(output, errors).filter(String::isNotBlank).joinToString("\n\n")
        val suffix = if (details.isBlank()) "" else "\n\n$details"
        throw AssertionFailedError(
            "AtlasArc.io cycle governance ${exitLabel(exitCode)} (evaluator exit $exitCode).$suffix",
        )
    }

    private fun exitLabel(exitCode: Int): String = when (exitCode) {
        EvaluatorExitCode.PROBLEMS -> "found unaccepted cycles"
        EvaluatorExitCode.INVALID -> "could not produce a valid verdict"
        EvaluatorExitCode.INTERNAL_ERROR -> "encountered an internal error"
        else -> "failed"
    }
}
