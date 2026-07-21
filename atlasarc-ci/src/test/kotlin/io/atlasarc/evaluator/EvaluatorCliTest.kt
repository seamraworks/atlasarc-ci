package io.atlasarc.evaluator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class EvaluatorCliTest {
    @TempDir lateinit var root: Path

    @Test
    fun `baseline requires config and defaults to a read only preview`() {
        val command = assertIs<EvaluatorCommand.Baseline>(
            EvaluatorCli.parse(arrayOf("baseline", "--config", ".atlasarc/evaluator.json"), root),
        )

        assertFalse(command.invocation.write)
        assertEquals(root.resolve(".atlasarc/evaluator.json").toAbsolutePath().normalize(), command.invocation.evaluator.configPath)
        assertEquals(EvaluatorOutputFormat.HUMAN, command.invocation.evaluator.format)
        assertEquals(null, command.invocation.reason)
        assertEquals(null, command.invocation.ticket)

        val failure = assertFailsWith<EvaluatorConfigurationException> {
            EvaluatorCli.parse(
                arrayOf("baseline", "--backend", "jvm-bytecode", "--source-id", "jvm:main"),
                root,
            )
        }
        assertEquals("Baseline generation requires --config with complete repository evidence.", failure.message)
    }

    @Test
    fun `baseline accepts explicit write metadata but rejects sarif`() {
        val command = assertIs<EvaluatorCommand.Baseline>(
            EvaluatorCli.parse(
                arrayOf(
                    "baseline", "--config", "evaluator.json", "--write",
                    "--reason", "Existing debt", "--ticket", "ARCH-42", "--format", "json",
                ),
                root,
            ),
        )

        assertEquals(true, command.invocation.write)
        assertEquals("Existing debt", command.invocation.reason)
        assertEquals("ARCH-42", command.invocation.ticket)
        assertEquals(EvaluatorOutputFormat.JSON, command.invocation.evaluator.format)

        val failure = assertFailsWith<EvaluatorConfigurationException> {
            EvaluatorCli.parse(
                arrayOf("baseline", "--config", "evaluator.json", "--format", "sarif"),
                root,
            )
        }
        assertEquals("Baseline output must be human or json, not sarif.", failure.message)
    }
}
