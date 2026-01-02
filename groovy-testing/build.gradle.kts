plugins {
    kotlin("jvm")
}

dependencies {
    // Groovy AST for ClassNode/ModuleNode
    implementation(project(":parser:native"))
    // Spock-specific detection and extraction
    implementation(project(":groovy-spock"))
    implementation(libs.slf4j.api)

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
