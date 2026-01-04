plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    implementation(project(":semantics:core"))
    implementation(project(":parser:core")) // For TypeSolver

    // Native Groovy AST
    implementation(libs.groovy.core)

    testImplementation(kotlin("test"))
    // testImplementation(libs.kotest.assertions)
    testImplementation(project(":parser:native"))
    testImplementation(project(":parser:api"))
    // testImplementation(libs.kotest.assertions)
    testImplementation(project(":parser:native"))
    testImplementation(project(":parser:api"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
