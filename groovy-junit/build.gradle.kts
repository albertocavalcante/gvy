plugins {
    kotlin("jvm")
}

dependencies {
    // Shared testing API and types
    api(project(":groovy-testing"))

    // Groovy AST for ClassNode/ModuleNode
    implementation(project(":parser:native"))

    // JUnit 5 API (compileOnly, as we only need it for constants/identifiers)
    // We don't want to force JUnit 5 on the runtime classpath of the user's project
    compileOnly(libs.junit.jupiter.api)
    compileOnly(libs.junit4)

    // Logging
    implementation(libs.slf4j.api)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)

    // Tests
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
