plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.lsp4j)

    detektPlugins(libs.detekt.formatting)
}
