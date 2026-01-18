plugins {
    kotlin("jvm")
}

dependencies {
    // Functional Programming - Arrow-kt core types (Either, Option, etc.)
    api(libs.arrow.core)

    // Coroutines - needed for CancellationException in Pipeline
    implementation(libs.kotlin.coroutines.core)

    // Logging
    // kotlin-logging provides the API; SLF4J implementation needed at runtime.
    // This is a leaf module with no project dependencies, so logback must be explicit for tests.
    // Other modules get logback transitively via groovy-lsp or don't have tests that initialize loggers.
    implementation(libs.kotlin.logging)
    testRuntimeOnly(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(project(":parser:core")) // For Groovydoc tests
    testRuntimeOnly(libs.junit.platform.launcher)
    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}
