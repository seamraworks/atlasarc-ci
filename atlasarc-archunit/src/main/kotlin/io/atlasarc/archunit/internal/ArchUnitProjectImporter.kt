package io.atlasarc.archunit.internal

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.nio.file.Path

/**
 * Thin wrapper around ArchUnit's [ClassFileImporter].
 *
 * Imports compiled class files from explicit filesystem paths rather than
 * from the JVM classpath, so the graph reflects only the analyzed project.
 *
 * Call this **outside** any IntelliJ read action — bytecode I/O does not
 * require the IntelliJ model lock.
 */
class ArchUnitProjectImporter {

    /**
     * Imports all `.class` files found under [outputRoots].
     *
     * @throws IllegalArgumentException if [outputRoots] is empty.
     */
    fun importOutputRoots(outputRoots: List<Path>): JavaClasses {
        require(outputRoots.isNotEmpty()) {
            "No compiler output roots provided. Build the project before analyzing."
        }

        return ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPaths(outputRoots)
    }
}
