plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.lsp4j.jsonrpc) // For Gson
    implementation(project(":indexer:core"))
}
