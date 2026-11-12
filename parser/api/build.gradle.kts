// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
    `java-library`
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    // Minimal dependencies - this is a pure API module
    implementation(libs.kotlin.stdlib)
    // The compiler policy customizes Groovy's CompilerConfiguration; consumers (parser:core/native) bring Groovy
    compileOnly(libs.groovy.core)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.groovy.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
