plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
    id("org.cyclonedx.bom") version "3.3.0"
    jacoco
}

group = "io.atlasarc"
version = providers.gradleProperty("releaseVersion").orElse("1.0.0-SNAPSHOT").get()

tasks.cyclonedxBom {
    componentGroup = project.group.toString()
    componentName = "atlasarc-ci"
    componentVersion = project.version.toString()
    includeBomSerialNumber = false
    includeBuildSystem = true
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/atlasarc-ci.cdx.json"))
    xmlOutput.unsetConvention()
}

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                javaParameters.set(true)
            }
        }
    }
}

tasks.register("releaseCandidate") {
    group = "distribution"
    description = "Builds, tests, verifies, and packages every AtlasArc CI deliverable."
    dependsOn(
        ":atlasarc-governance-core:check",
        ":atlasarc-governance-core:generatePomFileForMavenPublication",
        ":atlasarc-governance-core:sourcesJar",
        ":atlasarc-governance-core:dokkaJavadocJar",
        ":atlasarc-archunit:check",
        ":atlasarc-archunit:generatePomFileForMavenPublication",
        ":atlasarc-archunit:sourcesJar",
        ":atlasarc-archunit:dokkaJavadocJar",
        ":atlasarc-ci:check",
        ":atlasarc-ci:generatePomFileForMavenPublication",
        ":atlasarc-ci:sourcesJar",
        ":atlasarc-ci:dokkaJavadocJar",
        ":atlasarc-ci:releaseBundle",
        "cyclonedxBom",
    )
}
