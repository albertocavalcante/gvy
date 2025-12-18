plugins {
    kotlin("jvm")
}

dependencies {
    // Functional Programming - Arrow-kt core types (Either, Option, etc.)
    api(libs.arrow.core)

    // Coroutines - needed for CancellationException in Pipeline
    implementation(libs.kotlin.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
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
