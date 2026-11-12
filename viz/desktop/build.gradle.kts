import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    implementation(project(":viz:ast-model"))
    implementation(project(":parser:api"))
    implementation(project(":parser:core"))
    implementation(project(":parser:native"))
    implementation(project(":parser:rewrite"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
}

compose.desktop {
    application {
        mainClass = "com.github.albertocavalcante.gvy.viz.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Groovy AST Visualizer"
            packageVersion = "1.0.0" // Native package version (must start with > 0)
            description = "Visualize Groovy Abstract Syntax Trees"
            vendor = "Alberto Cavalcante"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.check {
    dependsOn("koverVerify")
}
