// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    // Facts types are used inside the builder only; none is in its public API yet.
    implementation(project(":jvm-metadata:facts"))
    implementation(libs.asm)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
