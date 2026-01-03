plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(libs.kotlin.stdlib)

    // Logging
    implementation(libs.slf4j.api)

    // Coroutines
    implementation(libs.kotlin.coroutines.core)

    // Gradle Tooling API
    implementation(libs.gradle.tooling.api)

    // Build Server Protocol (for Bazel, sbt, Mill support)
    implementation(libs.bsp4j)

    // JSON parsing for BSP connection files
    implementation(libs.kotlin.serialization.json)

    // Maven Embedder (for programmatic dependency resolution)
    implementation(libs.maven.embedder)
    implementation(libs.maven.compat)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.http)
    implementation(libs.maven.resolver.supplier)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)

    // Detekt Formatting
    detektPlugins(libs.detekt.formatting)
}

// Exclude BSP client from coverage - requires real BSP server (integration test)
// NOTE: BspConnectionDetails and BspBuildTool are tested via unit tests.
// BspClient requires a running BSP server process to test properly.
kover {
    reports {
        filters {
            excludes {
                classes("*BspClient*")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
