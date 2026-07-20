package io.atlasarc.evaluator

import io.atlasarc.governance.GovernanceBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvaluatorConfigContractTest {
    @Test
    fun `bundled schema declares the stable v1 contract`() {
        val schema = requireNotNull(javaClass.getResource("/evaluator-config.schema.json"))
            .readText()
            .let(Json::parseToJsonElement)
            .jsonObject

        assertEquals(
            "https://atlasarc.io/schemas/evaluator-config-v1.schema.json",
            schema.getValue("\$id").jsonPrimitive.content,
        )
        assertTrue(schema.containsKey("\$defs"))
    }

    @Test
    fun `published JVM and TypeScript examples decode through the production codec`() {
        val jvm = EvaluatorConfigCodec.read(Path.of("examples/jvm-evaluator.json"))
        val typescript = EvaluatorConfigCodec.read(Path.of("examples/typescript-evaluator.json"))

        assertEquals(GovernanceBackend.JVM_BYTECODE, jvm.sources.single().backend)
        assertEquals(GovernanceBackend.TYPESCRIPT_ARTIFACT, typescript.sources.single().backend)
        assertEquals(EVALUATOR_CONFIG_VERSION, jvm.configVersion)
        assertEquals(EVALUATOR_CONFIG_VERSION, typescript.configVersion)
    }

    @Test
    fun `one explicitly module-less JVM universe is valid`() {
        val config = EvaluatorConfig(
            sources = listOf(
                EvaluatorSourceConfig(
                    id = "jvm:whole-project",
                    backend = GovernanceBackend.JVM_BYTECODE,
                    classDirectories = listOf(
                        EvaluatorPathSpec("build/classes/java/main"),
                        EvaluatorPathSpec("build/classes/kotlin/main"),
                    ),
                    sourceRoots = listOf(EvaluatorPathSpec("src/main/java")),
                ),
            ),
        )

        EvaluatorConfigCodec.validate(config)
    }

    @Test
    fun `named and module-less JVM roots cannot be mixed`() {
        val config = EvaluatorConfig(
            sources = listOf(
                EvaluatorSourceConfig(
                    id = "jvm:whole-project",
                    backend = GovernanceBackend.JVM_BYTECODE,
                    classDirectories = listOf(
                        EvaluatorPathSpec("modules/orders/build/classes", "orders"),
                        EvaluatorPathSpec("build/classes"),
                    ),
                ),
            ),
        )

        val exception = assertFailsWith<EvaluatorConfigurationException> {
            EvaluatorConfigCodec.validate(config)
        }

        assertTrue(exception.message.orEmpty().contains("mixes named and module-less roots"))
    }
}
