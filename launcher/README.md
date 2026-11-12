# Kotlin/Native launcher preview

This source-only preview gives the existing JVM application a native `gvy` entry point. Its first purpose is to dogfood
Kotlin/Native in a small, replaceable production boundary. The JVM still provides every CLI operation and LSP feature.

The preview targets macOS Apple silicon, Linux x64 with glibc, and Windows x64 (Windows 10 / Server 2016 or newer).
Intel macOS, Linux Arm64, bundled Java, runtime minimization and single-file extraction remain follow-up work in
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

On Windows x64, from PowerShell with JDK 21 available for the build:

```powershell
.\gradlew.bat :launcher:stageMingwX64Launcher
.\launcher\build\install\mingwX64\gvy.exe version --json
```

The staging task builds the current server JAR and places `gvy` (`gvy.exe` on Windows) and `gvy.jar` together. Add that
directory to PATH or symlink `gvy` into your preferred bin directory. Keep `gvy.jar` beside the actual executable;
symlinks are resolved before payload lookup. Starting `gvy` with no arguments starts the existing LSP over stdio.

## Runtime and payload selection

| Input           | Behavior                                                                                                  |
| --------------- | --------------------------------------------------------------------------------------------------------- |
| `GVY_JAR`       | Optional path to an existing readable server JAR; otherwise use `gvy.jar` beside the resolved executable. |
| `GVY_JAVA_HOME` | First choice for the server's Java installation. Does not modify `JAVA_HOME` for project tools.           |
| `JAVA_HOME`     | Used when `GVY_JAVA_HOME` is unset.                                                                       |
| `PATH`          | Search for `java` when both Java-home variables are unset.                                                |

Java 17 or newer is required by the JVM application. An explicitly empty or invalid override fails without falling back.
On Windows, empty `PATH` entries are ignored; an explicit `.` opts into searching the current directory. The launcher
passes `-Xmx2g -jar <payload>` followed by every original argument, without interpreting shell syntax or consuming flags
such as `--help`. JVM options can use Java's existing `JDK_JAVA_OPTIONS` or `JAVA_TOOL_OPTIONS`; those variables and the
rest of the application environment are inherited unchanged.

The POSIX implementation replaces its process with Java. Stdin, stdout, stderr, working directory, PID, signals and the
application's exit status therefore belong directly to Java. Launcher failures print only to stderr: status 1 for
configuration/payload failures, 127 when Java cannot be found, and 126 for other execution failures. Java's own
validation handles unsupported runtimes and invalid JAR contents.

Windows uses a supervised Java process, with UTF-16 paths/environment and shell-free argument quoting. A private job is
assigned during process creation; terminating the launcher closes that job and terminates Java and its newly created
descendants. Existing processes outside that job remain outside its ownership. Fresh project-tool daemons started by the
server can therefore end with the launcher. Standard streams are inherited directly, and the full Windows exit status is
preserved. A shared console delivers Ctrl+C directly to Java; the supervisor waits for Java's actual exit. Windows does
not promise POSIX PID replacement or POSIX signal names.

**Windows runtime limitation:** the selected `java.exe` can still reject installation/JAR paths outside the Windows ANSI
code page. This was reproduced with direct Zulu Java 21 on an ACP-1252 machine; a UTF-16 launcher alone does not fix
Java's conversion. Use an installation path representable by that runtime (ASCII with spaces is tested). See
[#1817](https://github.com/albertocavalcante/gvy/issues/1817) for the exact evidence and the candidate UTF-8 manifest
approach for a future gvy-owned bundled runtime. The preview never modifies the user's JDK or machine locale.

## Validate the boundary

Run the host's native tests, release linker and quality checks, then the process tests against the same staged JAR:

```bash
./gradlew :launcher:macosArm64Test :launcher:stageMacosArm64Launcher :launcher:lintFix :launcher:lint
GVY_LAUNCHER="$PWD/launcher/build/install/macosArm64/gvy" \
  GVY_TEST_JAR="$PWD/launcher/build/install/macosArm64/gvy.jar" \
  node --test --test-concurrency=1 launcher/test/*.test.cjs
```

Use `linuxX64Test`, `stageLinuxX64Launcher` and the `linuxX64` directory on Linux. The process tests check argument
boundaries, empty/Unicode arguments, binary stdin, separate stderr, environment preservation, runtime precedence,
symlink payload lookup, failure statuses, process replacement and SIGINT. With `GVY_TEST_JAR`, they compare version JSON
against direct Java execution and perform a complete LSP initialize/shutdown/exit handshake using the shared editor
smoke helper. Without it, that integration case reports an explicit skip. Native CI requires it on every platform. The
root JVM `test` task does not include native tests; native CI runs them explicitly.

On Windows use `mingwX64Test`, `stageMingwX64Launcher`, and `gvy.exe`. The process harness compiles a tiny native PE
probe with GCC; install MinGW GCC and Node 24 to run it locally. Windows tests exercise wide arguments, binary pipes,
full exit statuses, private process-tree cancellation, hidden launches, runtime lookup, junctions and extended paths.
The actual-JAR Unicode-path test executes separately and reports a narrowly attributed TODO only when direct Java
reproduces #1817; this is a visible unsupported runtime case, not passing Unicode-JAR support.

The agent workload test parses success/error SARIF, checks formatting without overwriting the source, reads an exported
LSIF index, and opens a document over the default LSP transport to request class symbols. See the
[dogfood findings](../kb/LAUNCHER_DOGFOOD.md) for reproduced defects, scope and remaining distribution work.

Build size, startup time, runtime trimming and long-term platform maintenance remain measurements to collect, not
benefits established by selecting Kotlin/Native. The process interface above can be reimplemented without moving Groovy
analysis or CLI definitions out of the JVM.

The pinned Kotlin 2.3.0 plugin emits Gradle 9.8.2 deprecations for the Native bundle attribute enum and dependency
notation, alongside the previously tracked archives warning. Build/CI maintainers own their resolution in
[#1603](https://github.com/albertocavalcante/gvy/issues/1603): reproduce with the native tasks, update compatible plugin
APIs or versions, and retain native CLI/LSP parity. This limited compatibility exception expires on 2026-12-11 or before
a Gradle 10 upgrade, whichever comes first. Warnings remain visible; native compiler and lint diagnostics must be fixed.

The observed Native fingerprints are the `KotlinNativeBundleArtifactsTypes` attribute-enum diagnostic at
`KotlinNativeBundleArtifactFormat.kt:26` and multi-string notation for
`org.jetbrains.kotlin:kotlin-native-prebuilt:2.3.0:macos-aarch64@tar.gz` at the same file's line 79. Both are attributed
by Gradle deprecation traces to the pinned plugin's
[bundle resolver](https://github.com/JetBrains/kotlin/blob/f95cb2f7693a15966fd8fd9b1d78d440545e7b66/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/toolchain/KotlinNativeBundleArtifactFormat.kt).
These are recorded compatibility exceptions, not a warning allowlist or an assertion that all build diagnostics are
resolved.
