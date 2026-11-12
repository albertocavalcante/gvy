// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.lsp4j)
    implementation(libs.groovy.core) // For ModuleNode type in CompilationAccessor

    detektPlugins(libs.detekt.formatting)
}
