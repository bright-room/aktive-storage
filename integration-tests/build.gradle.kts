plugins {
    id("aktive.kotlin-library")
    id("aktive.integration-test")
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":storage-s3"))
    testImplementation(project(":metadata-exposed-jdbc"))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql.driver)
}
