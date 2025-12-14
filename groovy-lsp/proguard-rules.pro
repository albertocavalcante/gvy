# ProGuard rules for shrinking the shaded groovy-lsp uber jar.
# Conservative defaults: shrink only (no obfuscation/optimization) to reduce risk.

-dontobfuscate
-dontoptimize

-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,StackMapTable

# Entry point
-keep class com.github.albertocavalcante.groovylsp.MainKt { public static void main(java.lang.String[]); }

# Keep our public API surface (LSP + CLI)
-keep class com.github.albertocavalcante.** { public *; }

# Groovy is highly reflective/dynamic; keep it intact.
-keep class org.apache.groovy.** { *; }
-keep class org.codehaus.groovy.** { *; }
-keep class groovy.** { *; }

# CodeNarc loads rules dynamically; keep it intact.
-keep class org.codenarc.** { *; }
-keep class org.gmetrics.** { *; }

# LSP4J uses reflective JSON-RPC wiring; keep it intact.
-keep class org.eclipse.lsp4j.** { *; }
-keep class org.eclipse.lsp4j.jsonrpc.** { *; }

# Keep the SLF4J provider implementation (loaded via ServiceLoader).
-keep class ch.qos.logback.classic.spi.LogbackServiceProvider { *; }

# Be lenient about optional/runtime-specific references.
# Prefer targeted suppressions to keep ProGuard output actionable for maintainers.
-ignorewarnings
-dontwarn org.openrewrite.jgit.**
-dontwarn net.java.dev.jna.**
-dontwarn com.sun.jna.**
