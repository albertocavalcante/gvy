plugins {
    kotlin("jvm")
}

dependencies {
    // Groovy AST for ClassNode/ModuleNode
    implementation(project(":parser:native"))
    // Spock-specific detection and extraction
    implementation(project(":groovy-spock"))

    // Logging
    // kotlin-logging provides the API; SLF4J implementation needed at runtime.
    // Tests initialize classes with loggers, requiring logback on test classpath.
    implementation(libs.kotlin.logging)
    testRuntimeOnly(libs.logback.classic)

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
