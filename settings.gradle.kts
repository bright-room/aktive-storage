pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
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
