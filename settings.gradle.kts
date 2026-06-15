pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradleup.nmcp.settings") version "1.5.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "aktive-storage"

include("core")
include("storage-fs")
include("storage-s3")
include("metadata-exposed-jdbc")
include("integration-tests")
include("bom")

nmcpSettings {
    centralPortal {
        username = System.getenv("SONATYPE_CENTRAL_USERNAME")
        password = System.getenv("SONATYPE_CENTRAL_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}
