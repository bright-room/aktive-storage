plugins {
    id("aktive.kotlin-library")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
}
