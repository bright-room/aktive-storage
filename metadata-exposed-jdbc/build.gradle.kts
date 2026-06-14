plugins {
    id("aktive.kotlin-library")
    id("aktive.integration-test")
}

dependencies {
    api(project(":core"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql.driver)
}
