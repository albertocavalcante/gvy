plugins {
    kotlin("jvm")
    groovy
}

dependencies {
    // Groovy
    implementation(libs.groovy.core)

    // Logging
    implementation(libs.slf4j.api)

    api(project(":groovy-gdsl"))

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
