plugins {
    kotlin("jvm")
}

dependencies {
    // Groovy runtime for execution
    implementation(libs.groovy.core)

    // Apache Ivy - required for Groovy's @Grab annotation (Grape dependency management)
    // Without this, code using @Grab will fail with NoClassDefFoundError: org/apache/ivy/util/MessageLogger
    runtimeOnly(libs.ivy)

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}
