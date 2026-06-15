plugins {
    id("aktive.kotlin-library")
    id("aktive.published")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.io.core)
}
