plugins {
    id("org.jetbrains.kotlin.jvm")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs @Tag(\"integration\") tests."
    group = "verification"
    useJUnitPlatform {
        // aktive.kotlin-library excludes the tag on all Test tasks; reset so only integration runs here.
        excludeTags = LinkedHashSet<String>()
        includeTags("integration")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") { dependsOn(integrationTest) }
