plugins {
    kotlin("jvm")
    groovy
}

dependencies {
    // Groovy
    implementation(libs.groovy.core)

    // Logging
    // kotlin-logging provides the API; SLF4J implementation needed at runtime.
    // This is a leaf module with no project dependencies, so logback must be explicit for tests.
    // Other modules get logback transitively via groovy-lsp or don't have tests that initialize loggers.
    implementation(libs.kotlin.logging)
    testRuntimeOnly(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}
