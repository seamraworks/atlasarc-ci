package io.atlasarc.governance

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class FrameworkBoundaryTest {
    @Test
    fun `governance core bytecode has no IntelliJ PSI or JCEF references`() {
        val location = Path.of(CycleGovernanceDocument::class.java.protectionDomain.codeSource.location.toURI())
        val forbidden = listOf(
            "com/intellij",
            "org/cef",
            "com/tngtech/archunit",
            "org/gradle",
            "PsiElement",
            "JBCef",
        )
        val violations = mutableListOf<String>()

        Files.walk(location).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.forEach { classFile ->
                val bytecodeText = Files.readAllBytes(classFile).toString(StandardCharsets.ISO_8859_1)
                forbidden.filter(bytecodeText::contains).forEach { marker ->
                    violations += "${location.relativize(classFile)} contains $marker"
                }
            }
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}
