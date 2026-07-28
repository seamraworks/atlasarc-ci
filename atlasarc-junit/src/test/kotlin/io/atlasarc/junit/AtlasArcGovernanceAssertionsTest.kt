package io.atlasarc.junit

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtlasArcGovernanceAssertionsTest {
    @TempDir
    lateinit var root: Path

    private lateinit var configPath: Path

    @BeforeEach
    fun prepareJvmCycle() {
        assertEquals(0, ProcessBuilder("git", "init", "--quiet", root.toString()).start().waitFor())
        val sourceRoot = root.resolve("src/main/java")
        val classesRoot = root.resolve("target/classes")
        val orders = sourceRoot.resolve("demo/orders/Orders.java")
        val billing = sourceRoot.resolve("demo/billing/Billing.java")
        orders.parent.createDirectories()
        billing.parent.createDirectories()
        Files.writeString(
            orders,
            "package demo.orders; import demo.billing.Billing; public class Orders { public void call(Billing value) { value.touch(); } public void touch() {} }\n",
        )
        Files.writeString(
            billing,
            "package demo.billing; import demo.orders.Orders; public class Billing { public void call(Orders value) { value.touch(); } public void touch() {} }\n",
        )
        classesRoot.createDirectories()
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-d",
                classesRoot.toString(),
                orders.toString(),
                billing.toString(),
            ),
        )
        val old = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
        Files.setLastModifiedTime(orders, old)
        Files.setLastModifiedTime(billing, old)

        configPath = root.resolve(".atlasarc/evaluator.json")
        configPath.parent.createDirectories()
        Files.writeString(
            configPath,
            """
            {
              "\u0024schema": "https://atlasarc.io/schemas/evaluator-config-v1.schema.json",
              "configVersion": 1,
              "repositoryRoot": "..",
              "sources": [{
                "id": "jvm:whole-project",
                "backend": "jvm-bytecode",
                "classDirectories": [{"path": "target/classes"}],
                "sourceRoots": [{"path": "src/main/java"}]
              }]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `ungoverned cycle becomes an ordinary JUnit assertion failure`() {
        val failure = assertFailsWith<AssertionFailedError> {
            AtlasArcGovernanceAssertions.assertGovernance(configPath, root)
        }

        assertContains(failure.message.orEmpty(), "found unaccepted cycles")
        assertContains(failure.message.orEmpty(), "demo.billing -> demo.orders")
        assertContains(failure.message.orEmpty(), "evaluator exit 1")
    }

    @Test
    fun `governed cycle passes the JUnit assertion`() {
        val governancePath = root.resolve(".atlasarc/governance/cycles.json")
        governancePath.parent.createDirectories()
        Files.writeString(
            governancePath,
            """
            {
              "\u0024schema": "https://atlasarc.io/schemas/cycle-governance-v1.schema.json",
              "schemaVersion": 1,
              "records": {
                "orders-to-billing-debt": {
                  "analysisSource": {"id": "jvm:whole-project", "backend": "jvm-bytecode", "language": "java"},
                  "scope": "package",
                  "ownerSide": "source",
                  "source": {"architectureUnit": "demo.orders"},
                  "target": {"architectureUnit": "demo.billing"},
                  "referenceIds": [],
                  "kind": "DEBT",
                  "reason": "Existing dependency tracked for removal."
                }
              }
            }
            """.trimIndent(),
        )

        AtlasArcGovernanceAssertions.assertGovernance(configPath, root)
    }

    @Test
    fun `invalid evaluator input fails closed in JUnit`() {
        Files.writeString(configPath, """{"configVersion":1,"sources":[]}""")

        val failure = assertFailsWith<AssertionFailedError> {
            AtlasArcGovernanceAssertions.assertGovernance(configPath, root)
        }

        assertContains(failure.message.orEmpty(), "could not produce a valid verdict")
        assertContains(failure.message.orEmpty(), "evaluator exit 2")
    }

    @Test
    fun `TypeScript dependency cruiser evidence can fail the same JUnit assertion`() {
        val appFile = root.resolve("src/app/index.ts")
        val domainFile = root.resolve("src/domain/model.ts")
        appFile.parent.createDirectories()
        domainFile.parent.createDirectories()
        Files.writeString(appFile, "import { model } from '../domain/model';\nexport const app = model;\n")
        Files.writeString(domainFile, "import { app } from '../app/index';\nexport const model = app;\n")
        val dependencyGraph = root.resolve(".atlasarc/depgraph.json")
        Files.writeString(
            dependencyGraph,
            """
            {
              "modules": [
                {
                  "source": "src/app/index.ts",
                  "dependencies": [
                    {"module":"../domain/model","resolved":"src/domain/model.ts","dependencyTypes":["local","import"],"circular":true,"valid":true}
                  ]
                },
                {
                  "source": "src/domain/model.ts",
                  "dependencies": [
                    {"module":"../app/index","resolved":"src/app/index.ts","dependencyTypes":["local","import"],"circular":true,"valid":true}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            configPath,
            """
            {
              "\u0024schema": "https://atlasarc.io/schemas/evaluator-config-v1.schema.json",
              "configVersion": 1,
              "repositoryRoot": "..",
              "sources": [{
                "id": "typescript:frontend",
                "backend": "typescript-artifact",
                "root": ".",
                "dependencyCruiserJson": ".atlasarc/depgraph.json"
              }]
            }
            """.trimIndent(),
        )

        val failure = assertFailsWith<AssertionFailedError> {
            AtlasArcGovernanceAssertions.assertGovernance(configPath, root)
        }

        assertContains(failure.message.orEmpty(), "src/app -> src/domain")
        assertContains(failure.message.orEmpty(), "evaluator exit 1")
    }
}
