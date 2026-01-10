plugins {
    kotlin("jvm")
}

dependencies {
    // Core dependencies for parsing and common utilities
    api(project(":parser:native"))
    api(project(":groovy-common"))

    // Testing frameworks
    api(libs.junit.jupiter)
    api(libs.kotlin.test)
    api(libs.mockk)
    api(libs.assertj.core)
    api(libs.kotlin.coroutines.test)

    // Logging
    implementation(libs.slf4j.api)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}

// Skip coverage verification for test utilities module
tasks.named("koverVerify") {
    enabled = false
}
