plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":groovy-diagnostics:api"))

    // LSP4J for Diagnostic types
    implementation(libs.lsp4j)
    implementation(libs.lsp4j.jsonrpc)

    // Kotlin serialization for JSON output
    implementation(libs.kotlin.serialization.json)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
