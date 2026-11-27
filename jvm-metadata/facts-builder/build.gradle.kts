// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    // Facts, Sha256, WriteOptions and ReadLimits appear in the public API of the builder.
    api(project(":jvm-metadata:facts"))
    implementation(libs.asm)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
