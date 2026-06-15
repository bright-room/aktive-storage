plugins {
    `maven-publish`
    signing
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

// Kotlin/JVM モジュールには sources + dokka javadoc jar を付ける（java-platform は除く）
plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        withSourcesJar()
    }
    apply(plugin = "org.jetbrains.dokka-javadoc")
    val javadocJar = tasks.register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaGeneratePublicationJavadoc"))
    }
    tasks.named("assemble") { dependsOn(javadocJar) }
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            val isPlatform = project.plugins.hasPlugin("java-platform")
            if (isPlatform) from(components["javaPlatform"]) else from(components["java"])
            if (!isPlatform) {
                artifact(tasks.named("javadocJar"))
            }
            pom {
                name.set(project.name)
                description.set("Framework-agnostic file attachment toolkit for the JVM (${project.name}).")
                url.set("https://github.com/bright-room/aktive-storage")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("bright-room")
                        name.set("Nonaka Koki")
                        email.set("koki-nonaka@outlook.jp")
                        url.set("https://bright-room.net")
                    }
                }
                scm { url.set("https://github.com/bright-room/aktive-storage") }
            }
        }
    }
}

signing {
    isRequired = false
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    val pass = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications)
    }
}
