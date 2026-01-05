plugins {
    kotlin("jvm")
    groovy
}

dependencies {
    implementation(project(":groovy-diagnostics:api"))
    implementation(project(":parser:native"))

    // LSP4J - Language Server Protocol implementation
    implementation(libs.lsp4j)

    // CodeNarc
    implementation(libs.codenarc)
    implementation(libs.gmetrics)
    implementation(libs.groovy.core)
    implementation(libs.groovy.json)

    implementation(libs.kotlin.coroutines.core)
    implementation(libs.slf4j.api)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "junit.jupiter.tempdir.base",
        layout.buildDirectory
            .dir("tmp/junit")
            .get()
            .asFile,
    )
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
