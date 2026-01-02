plugins {
    kotlin("jvm")
    `java-library`
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    // Minimal dependencies - this is a pure API module
    implementation(libs.kotlin.stdlib)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
