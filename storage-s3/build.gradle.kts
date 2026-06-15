plugins {
    id("aktive.kotlin-library")
    id("aktive.integration-test")
    id("aktive.published")
}

dependencies {
    api(project(":core"))
    api(libs.aws.s3)

    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}
