plugins {
    alias(libs.plugins.kotlin.jvm)
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

    // Maven Embedder (for programmatic dependency resolution)
    implementation(libs.maven.embedder)
    implementation(libs.maven.compat)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.http)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)

    // Detekt Formatting
    detektPlugins(libs.detekt.formatting)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
