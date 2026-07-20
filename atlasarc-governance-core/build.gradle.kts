plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    jacoco
}

base.archivesName.set("atlasarc-governance-core")

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
}

mavenPublishing {
    coordinates("io.atlasarc", "atlasarc-governance-core", project.version.toString())
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
    pom {
        name.set("AtlasArc Governance Core")
        description.set("Portable cycle-governance contract, matcher, and evaluation engine for AtlasArc.")
        inceptionYear.set("2026")
        url.set("https://github.com/seamraworks/atlasarc-ci")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("seamraworks")
                name.set("Seamra Works")
                url.set("https://seamraworks.com")
            }
        }
        scm {
            url.set("https://github.com/seamraworks/atlasarc-ci")
            connection.set("scm:git:https://github.com/seamraworks/atlasarc-ci.git")
            developerConnection.set("scm:git:ssh://git@github.com/seamraworks/atlasarc-ci.git")
        }
    }
}
