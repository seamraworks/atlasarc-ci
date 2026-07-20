plugins {
    `java-library`
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    jacoco
}

base.archivesName.set("atlasarc-archunit")

dependencies {
    api(project(":atlasarc-governance-core"))
    api("com.tngtech.archunit:archunit:1.4.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.1.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("atlasarc.testFixtures", rootProject.file("test-fixtures").absolutePath)
}

mavenPublishing {
    coordinates("io.atlasarc", "atlasarc-archunit", project.version.toString())
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
    pom {
        name.set("AtlasArc ArchUnit Adapter")
        description.set("ArchUnit and JUnit adapter for AtlasArc repository cycle governance.")
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
