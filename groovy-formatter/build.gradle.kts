plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.rewrite.groovy)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
