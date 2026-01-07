plugins {
    kotlin("jvm")
}

tasks.jar {
    archiveBaseName.set("groovy-semantics-dsl")
}

dependencies {
    // Dependency on semantics:core
    api(project(":semantics:core"))

    // Groovy AST for DSL matching
    implementation(libs.groovy.core)

    // Arrow-kt for functional programming
    implementation(libs.arrow.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
