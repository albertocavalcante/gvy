plugins {
    kotlin("jvm")
    `java-library`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Jupyter protocol - ZMQ messaging
    api(libs.jeromq)

    // JSON serialization for Jupyter messages
    api(libs.kotlin.serialization.json)

    // Logging
    api(libs.slf4j.api)

    // Groovy REPL execution
    api(project(":groovy-repl"))

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
