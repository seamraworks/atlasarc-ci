plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    jacoco
}

base.archivesName.set("atlasarc-ci")

dependencies {
    implementation(project(":atlasarc-governance-core"))
    implementation(project(":atlasarc-archunit"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.1.2")
}

application {
    applicationName = "atlasarc-ci"
    mainClass.set("io.atlasarc.evaluator.MainKt")
}

tasks.withType<Test>().configureEach {
    systemProperty("atlasarc.testFixtures", rootProject.file("test-fixtures").absolutePath)
}

val standaloneJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Builds the self-contained AtlasArc CI executable JAR."
    archiveClassifier.set("standalone")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest.attributes["Main-Class"] = application.mainClass.get()
    manifest.attributes["Implementation-Title"] = "AtlasArc CI"
    manifest.attributes["Implementation-Version"] = project.version.toString()
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map(::zipTree) })
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
    from(rootProject.file("THIRD-PARTY-NOTICES.md"))
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

val distributionDir = layout.buildDirectory.dir("release")

val generateChecksums by tasks.registering(GenerateChecksumsTask::class) {
    dependsOn(standaloneJar, ":cyclonedxBom")
    artifacts.from(
        standaloneJar.flatMap { it.archiveFile },
        rootProject.layout.buildDirectory.file("reports/cyclonedx/atlasarc-ci.cdx.json"),
    )
    outputFile.set(distributionDir.map { it.file("SHA256SUMS") })
}

val releaseBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Assembles the executable, schemas, examples, license, notice, and checksum manifest."
    archiveBaseName.set("atlasarc-ci")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dependsOn(standaloneJar, generateChecksums, ":cyclonedxBom")
    from(standaloneJar)
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
    from(rootProject.file("THIRD-PARTY-NOTICES.md"))
    from(rootProject.file("README.md"))
    from(distributionDir)
    from(rootProject.layout.buildDirectory.file("reports/cyclonedx/atlasarc-ci.cdx.json"))
    from("src/main/resources/evaluator-config.schema.json")
    from(project(":atlasarc-governance-core").file("src/main/resources/io/atlasarc/governance/cycle-governance-v1.schema.json"))
    from("examples") { into("examples") }
}

val verifyRuntimeBoundary by tasks.registering(VerifyArchiveBoundaryTask::class) {
    dependsOn(standaloneJar)
    archive.from(standaloneJar.flatMap { it.archiveFile })
    forbiddenPrefixes.set(listOf("com/intellij/", "org/cef/", "com/jetbrains/"))
}

tasks.named("check") {
    dependsOn(verifyRuntimeBoundary, releaseBundle)
}

mavenPublishing {
    coordinates("io.atlasarc", "atlasarc-ci", project.version.toString())
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
    pom {
        name.set("AtlasArc CI")
        description.set("Language-neutral command-line enforcement for AtlasArc repository cycle governance.")
        inceptionYear.set("2026")
        url.set("https://github.com/seamraworks/atlasarc-ci")
        licenses { license { name.set("The Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt"); distribution.set("repo") } }
        developers { developer { id.set("seamraworks"); name.set("Seamra Works"); url.set("https://seamraworks.com") } }
        scm {
            url.set("https://github.com/seamraworks/atlasarc-ci")
            connection.set("scm:git:https://github.com/seamraworks/atlasarc-ci.git")
            developerConnection.set("scm:git:ssh://git@github.com/seamraworks/atlasarc-ci.git")
        }
    }
}
