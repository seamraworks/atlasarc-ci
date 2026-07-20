pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "atlasarc-ci"

include("atlasarc-governance-core")
include("atlasarc-archunit")
include("atlasarc-ci")
