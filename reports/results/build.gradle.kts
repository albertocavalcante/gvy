plugins {
    kotlin("jvm")
}

base {
    archivesName.set("reports-results")
}

dependencies {
    // Internal dependencies
    api(project(":reports:api"))

    // Logging
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)

    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}
