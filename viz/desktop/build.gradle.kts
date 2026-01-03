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

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(libs.kotlin.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
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

            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
