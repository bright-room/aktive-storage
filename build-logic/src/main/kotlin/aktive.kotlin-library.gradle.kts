import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.diffplug.spotless")
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

extensions.configure<SpotlessExtension> {
    kotlin {
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
        target("src/**/*.kt")
    }
    kotlinGradle {
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}
