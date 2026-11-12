// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
}

dependencies {
    // Groovy AST for ClassNode/ModuleNode
    implementation(project(":parser:native"))
    // Spock-specific detection and extraction
    implementation(project(":ext:spock"))

    // Logging
    // kotlin-logging provides the API; SLF4J implementation needed at runtime.
    // Tests initialize classes with loggers, requiring logback on test classpath.
    implementation(libs.kotlin.logging)
    testImplementation(libs.logback.classic)
    testImplementation("ch.qos.logback:logback-core:${libs.versions.logback.get()}")
    testImplementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}
