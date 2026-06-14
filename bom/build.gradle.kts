plugins {
    `java-platform`
    id("aktive.published")
}

dependencies {
    constraints {
        api(project(":core"))
        api(project(":storage-fs"))
        api(project(":storage-s3"))
        api(project(":metadata-exposed-jdbc"))
    }
}
