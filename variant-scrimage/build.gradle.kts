plugins {
    id("aktive.kotlin-library")
    id("aktive.published")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.scrimage.core)
    implementation(libs.scrimage.webp)
}
