plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Groovy
    implementation(libs.groovy.core)

    // Kotlin
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.coroutines.core)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Arrow for functional programming
    implementation(libs.arrow.core)

    // Logging
    implementation(libs.slf4j.api)

    // ClassGraph for scanning plugin JARs
    implementation(libs.classgraph)

    // HTML to Markdown conversion for vars documentation
    implementation(libs.flexmark.html2md)

    api(project(":groovy-common"))
    api(project(":groovy-gdsl"))
    api(project(":groovy-build-tool"))

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}
