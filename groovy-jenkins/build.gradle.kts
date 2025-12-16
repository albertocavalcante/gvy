plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    groovy
}

dependencies {
    // Groovy
    implementation(libs.groovy.core)

    // Kotlin
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // ClassGraph for scanning plugin JARs
    implementation(libs.classgraph)

    api(project(":groovy-gdsl"))
    api(project(":groovy-build-tool"))

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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
