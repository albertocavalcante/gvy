# Kotlin/Native launcher preview

This source-only preview gives the existing JVM application a native `gvy` entry point. Its first purpose is to dogfood
Kotlin/Native in a small, replaceable production boundary. The JVM still provides every CLI operation and LSP feature.

The initial targets are macOS Apple silicon and Linux x64 with glibc. Windows, Intel macOS, Linux Arm64, bundled Java,
runtime minimization and single-file extraction remain follow-up work in
[#1741](https://github.com/albertocavalcante/gvy/issues/1741). Existing JAR, editor and Homebrew installations continue
to use their current entry points. This preview has a Gradle build; it has no Bazel or Buck2 native target or release
artifact yet.

## Build and dogfood

Follow the [JVM build setup](../CONTRIBUTING.md#set-up-the-jvm-build). Building an Apple executable also requires Xcode;
the command-line tools alone do not pass Kotlin/Native's linker setup. Kotlin/Native downloads its compiler and native
dependencies on the first build.

On macOS Apple silicon:

```bash
./gradlew :launcher:stageMacosArm64Launcher
launcher/build/install/macosArm64/gvy version --json
launcher/build/install/macosArm64/gvy --help
```

On Linux x64:

```bash
./gradlew :launcher:stageLinuxX64Launcher
launcher/build/install/linuxX64/gvy version --json
```

The staging task builds the current server JAR and places `gvy` and `gvy.jar` together. Add that directory to PATH or
symlink `gvy` into your preferred bin directory. Keep `gvy.jar` beside the actual executable; symlinks are resolved
before payload lookup. Starting `gvy` with no arguments starts the existing LSP over stdio.

## Runtime and payload selection

| Input           | Behavior                                                                                                  |
| --------------- | --------------------------------------------------------------------------------------------------------- |
| `GVY_JAR`       | Optional path to an existing readable server JAR; otherwise use `gvy.jar` beside the resolved executable. |
| `GVY_JAVA_HOME` | First choice for the server's Java installation. Does not modify `JAVA_HOME` for project tools.           |
| `JAVA_HOME`     | Used when `GVY_JAVA_HOME` is unset.                                                                       |
| `PATH`          | Search for `java` when both Java-home variables are unset.                                                |

Java 17 or newer is required by the JVM application. An explicitly empty or invalid override fails without falling back.
The launcher passes `-Xmx2g -jar <payload>` followed by every original argument, without interpreting shell syntax or
consuming flags such as `--help`. JVM options can use Java's existing `JDK_JAVA_OPTIONS` or `JAVA_TOOL_OPTIONS`; those
variables and the rest of the application environment are inherited unchanged.

The POSIX implementation replaces its process with Java. Stdin, stdout, stderr, working directory, PID, signals and the
application's exit status therefore belong directly to Java. Launcher failures print only to stderr: status 1 for
configuration/payload failures, 127 when Java cannot be found, and 126 for other execution failures. Java's own
validation handles unsupported runtimes and invalid JAR contents.

## Validate the boundary

Run the host's native tests, release linker and quality checks, then the process tests against the same staged JAR:

```bash
./gradlew :launcher:macosArm64Test :launcher:stageMacosArm64Launcher :launcher:lintFix :launcher:lint
GVY_LAUNCHER="$PWD/launcher/build/install/macosArm64/gvy" \
  GVY_TEST_JAR="$PWD/launcher/build/install/macosArm64/gvy.jar" \
  node --test launcher/test/launcher.test.cjs
```

Use `linuxX64Test`, `stageLinuxX64Launcher` and the `linuxX64` directory on Linux. The process tests check argument
boundaries, empty/Unicode arguments, binary stdin, separate stderr, environment preservation, runtime precedence,
symlink payload lookup, failure statuses, process replacement and SIGINT. With `GVY_TEST_JAR`, they compare version JSON
against direct Java execution and perform a complete LSP initialize/shutdown/exit handshake using the shared editor
smoke helper. Without it, that one integration case reports an explicit skip. Native CI requires it on both platforms.
The root JVM `test` task does not include native tests; native CI runs them explicitly.

Build size, startup time, runtime trimming and long-term platform maintenance remain measurements to collect, not
benefits established by selecting Kotlin/Native. The process interface above can be reimplemented without moving Groovy
analysis or CLI definitions out of the JVM.

The pinned Kotlin 2.3.0 plugin emits Gradle 9.8.2 deprecations for the Native bundle attribute enum and dependency
notation, alongside the previously tracked archives warning. Build/CI maintainers own their resolution in
[#1603](https://github.com/albertocavalcante/gvy/issues/1603): reproduce with the native tasks, update compatible plugin
APIs or versions, and retain native CLI/LSP parity. This limited compatibility exception expires on 2026-12-11 or before
a Gradle 10 upgrade, whichever comes first. Warnings remain visible; native compiler and lint diagnostics must be fixed.
