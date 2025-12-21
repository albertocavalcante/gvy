plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    // Jupyter protocol - ZMQ messaging
    implementation(libs.jeromq)

    // JSON serialization for Jupyter messages
    implementation(libs.kotlin.serialization.json)

    // Reused modules
    implementation(project(":groovy-parser"))
    implementation(project(":groovy-common"))

    // Groovy runtime for execution
    implementation(libs.groovy.core)

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
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

application {
    mainClass = "com.github.albertocavalcante.groovyjupyter.MainKt"
}

tasks.shadowJar {
    archiveBaseName = "groovy-jupyter"
    archiveClassifier = "all"

    manifest {
        attributes["Main-Class"] = "com.github.albertocavalcante.groovyjupyter.MainKt"
    }
}
