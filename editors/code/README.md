# Groovy Language Support

[![CI](https://github.com/albertocavalcante/gvy/actions/workflows/ci.yml/badge.svg)](https://github.com/albertocavalcante/gvy/actions/workflows/ci.yml)

A VS Code extension that adds Groovy language support with syntax highlighting, code completion, and error checking.

> **Status:** Early development. Feature coverage and correctness vary by project. See the
> [feature matrix](https://github.com/albertocavalcante/gvy/blob/main/docs/lsp/feature-support.md) for current support
> and limits.

This README describes the current source checkout. The stable v0.5.4 VSIX bundles the older v0.4.8 server; read the
[stable release limitation](https://github.com/albertocavalcante/gvy/blob/main/docs/installation.md#stable-release-limitation)
before comparing its behavior with development features. For a first project, follow the
[quickstart](https://github.com/albertocavalcante/gvy/blob/main/docs/user/quickstart.md).

## Features

### Core Language Support

- [x] Syntax highlighting for all Groovy file types
- [x] Code completion and IntelliSense with type information
- [x] Real-time error detection and diagnostics
- [x] Hover documentation with GroovyDoc/JavaDoc
- [x] Go to Definition & Type Definition
- [x] Find All References
- [x] Rename Symbol (workspace-wide)
- [x] Signature Help (parameter hints)
- [x] Code Formatting (OpenRewrite-based)
- [x] Document & Workspace Symbols
- [x] Automatic language server management

### Static Analysis

- [x] CodeNarc integration with project-specific rulesets
- [x] Auto-detection of project types (Jenkins, Gradle, Spock)
- [x] Quick fixes for common issues
- [x] TODO/FIXME comment scanning

### Build Integration

- [x] Gradle project support
- [x] Automatic dependency resolution
- [x] Multi-workspace support

### Jenkins Pipeline

- [x] Jenkinsfile syntax support
- [x] Shared library configuration
- [x] GDSL file support for DSL extensions
- [x] Custom pipeline step completion

## Supported Files

| File Type        | Extensions                       | What it's for       |
| ---------------- | -------------------------------- | ------------------- |
| Groovy Scripts   | `.groovy`, `.gvy`, `.gy`, `.gsh` | General Groovy code |
| Gradle Build     | `.gradle`                        | Build scripts       |
| Jenkins Pipeline | `Jenkinsfile`, `Jenkinsfile.*`   | CI/CD pipelines     |
| Groovy DSL       | `.gdsl`                          | DSL contributions   |

### Editing GDSL files

`.gdsl` files open in **Groovy DSL** mode with Groovy syntax highlighting, line and block comments, bracket and quote
pairing, automatic indentation, and indentation/region folding. These editing features work without Java or a running
language server.

Type `gdsl-` and select a snippet from suggestions, or use **Insert Snippet** from the Command Palette:

| Prefix              | Inserts                                               |
| ------------------- | ----------------------------------------------------- |
| `gdsl-contributor`  | A contributor block for global script scope           |
| `gdsl-context`      | A reusable global script context                      |
| `gdsl-method`       | A method with positional parameters and documentation |
| `gdsl-named-method` | A method with named parameters and documentation      |
| `gdsl-property`     | A typed property with documentation                   |
| `gdsl-parameter`    | A parameter for a method's `namedParams` list         |

The snippets use `scriptScope()` for global contributions. When gvy loads GDSL for Jenkins metadata, it flattens
contributions and does not filter them by context; class, closure, and annotation scopes are therefore not offered by
these snippets. Other GDSL consumers may handle scopes differently.

Press Tab to move through snippet fields. GDSL authoring currently provides these editing features and snippets;
GDSL-specific semantic completion, hover, diagnostics, formatting, and navigation are not yet available. Loading a GDSL
file to contribute Jenkins metadata uses the separate `groovy.jenkins.gdslPaths` and
`groovy.jenkins.gdslExecution.enabled` user settings described below.

## Setup

On macOS or Linux,
[install with Homebrew](https://github.com/albertocavalcante/gvy/blob/main/docs/installation.md#macos-and-linux-with-homebrew)
to include Java and use the extension installer.

For a manual installation, **Java 17+** must be installed on your system.

1. Download `gvy-<version>.vsix` from the
   [latest GitHub Release](https://github.com/albertocavalcante/gvy/releases/latest). The extension is not published to
   the VS Code Marketplace or Open VSX.
2. Optionally verify the download, either against `checksums.txt` from the same release (lines look like
   `<sha256>  ./gvy-0.5.2.vsix`):

   ```bash
   shasum -a 256 -c --ignore-missing checksums.txt
   ```

   or via its build attestation:

   ```bash
   gh attestation verify gvy-<version>.vsix --owner albertocavalcante
   ```

3. Install it:

   ```bash
   code --install-extension gvy-<version>.vsix
   ```

4. Open any `.groovy`, `.gradle`, or `Jenkinsfile` - language support starts automatically

To update, install the newer `.vsix` the same way. The language server JAR is bundled in the `.vsix`; the separate
`gls-<version>.jar` release asset is only needed for use outside VS Code.

Cursor and VSCodium use the same **Extensions: Install from VSIX…** command.

The extension finds Java from your PATH or `JAVA_HOME`.

## Configuration

### Basic Settings

```json
{
  // Java configuration for Language Server (requires Java 17+)
  "groovy.languageServer.javaHome": "/path/to/your/java17",

  // Java configuration for Project Build (Maven/Gradle)
  "groovy.project.javaHome": "/path/to/your/project/java",

  // DEPRECATED: Use groovy.languageServer.javaHome instead
  // "groovy.java.home": "/path/to/your/java17",

  // Enable/disable code formatting
  "groovy.format.enable": true,

  // Trace LSP communication (off, messages, verbose)
  "groovy.trace.server": "off"
}
```

#### Java Settings Explained

- **`groovy.languageServer.javaHome`**: Java installation for running the Groovy Language Server. Requires Java 17 or
  higher. If not set, the extension will auto-detect Java from your system.

- **`groovy.project.javaHome`**: Java installation for project build and test execution (Maven/Gradle). This can be
  different from the Language Server Java. If not set, uses your system's `JAVA_HOME`.

- **`groovy.server.vmargs`** / **`groovy.server.vmargsDefaults`**: the server JVM starts with memory-friendly defaults
  (heap bounded at 2 GB, G1 with an idle GC every 60 s that returns unused memory to the OS, exit on out-of-memory so
  the server restarts). `groovy.server.vmargs` is appended after them, so your `-X`/`-XX` options win. A default is
  dropped only when it would conflict with yours: `-Xmx2g` when you set a maximum heap (`-Xmx`, `-XX:MaxRAMPercentage`,
  ...) or an initial heap above 2g, `-Xms64m` when your maximum is below 64m, a `HeapFreeRatio` default when your other
  bound would contradict it, and the G1-only defaults when you pick another collector (for example
  `-XX:+UseParallelGC`). `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` are taken into account too (for
  example `-XX:MaxRAMPercentage` there replaces `-Xmx2g`); `@argfiles`, `-XX:VMOptionsFile` and `-XX:Flags` are not
  inspected. Changing either setting prompts for a server restart.

  **Upgrading:** a custom `groovy.server.vmargs` list (including `[]`) no longer replaces the defaults. Set
  `groovy.server.vmargsDefaults` to `false` to use only your own list.

- **`groovy.java.home`**: **DEPRECATED**. This setting is replaced by `groovy.languageServer.javaHome` and will be
  removed in a future version. For backward compatibility, it still works but you should migrate to the new setting
  names.

### CodeNarc Static Analysis

```json
{
  // Enable CodeNarc linting
  "groovy.codenarc.enabled": true,

  // Path to custom CodeNarc config file
  "groovy.codenarc.propertiesFile": "/path/to/codenarc.properties",

  // Auto-detect project type and apply appropriate ruleset
  "groovy.codenarc.autoDetect": true
}
```

### Jenkins Pipeline Support

For Jenkins shared libraries:

```json
{
  // File patterns to recognize as Jenkinsfiles (this is the default; setting it replaces the whole list)
  "groovy.jenkins.filePatterns": [
    "Jenkinsfile",
    "Jenkinsfile.*",
    "Jenkinsfile-*",
    "Jenkinsfile_*",
    "*.jenkinsfile",
    "*.Jenkinsfile",
    "vars/*.groovy"
  ],

  // Configure shared libraries
  "groovy.jenkins.sharedLibraries": [
    {
      "name": "my-pipeline-lib",
      "jar": "/path/to/my-pipeline-lib.jar",
      "sourcesJar": "/path/to/my-pipeline-lib-sources.jar"
    }
  ],

  // GDSL files for DSL enhancements (user or remote settings only)
  "groovy.jenkins.gdslPaths": ["/path/to/jenkins.gdsl"],

  // Execute GDSL scripts (disabled by default for security; user or remote settings only,
  // so a workspace's .vscode/settings.json cannot turn it on)
  "groovy.jenkins.gdslExecution.enabled": true,

  // Optional: Jenkins plugin discovery overrides
  "groovy.jenkins.pluginsTxtPath": "/path/to/plugins.txt",
  "groovy.jenkins.plugins": [
    "workflow-basic-steps",
    "pipeline-model-definition"
  ],
  "groovy.jenkins.includeDefaultPlugins": true
}
```

Shared-library entries currently use local, prebuilt JARs. A reference such as `@Library('my-pipeline-lib@main')`
selects the configured library by name; `main` does not fetch a Git revision or select a different JAR.

| Entry fields                                          | Current behavior                                |
| ----------------------------------------------------- | ----------------------------------------------- |
| No `source`: `name`, `jar`, optional `sourcesJar`     | Uses the existing JAR selection rules           |
| `source`, neither `jar` nor `sourcesJar`              | Inactive; logs an indexed configuration warning |
| `source` with either `jar` or `sourcesJar`, even null | Inactive; logs a format conflict                |

The presence of `source` prevents fallback to a legacy JAR, including when its value is malformed. These settings do not
import the controller's library definitions or implicit-loading configuration. Source-based libraries and full Jenkins
loading context are tracked in the [shared-library design](https://github.com/albertocavalcante/gvy/issues/1953).

### Compilation Settings

```json
{
  // Compilation mode: "workspace" (accurate) or "single-file" (fast)
  "groovy.compilation.mode": "workspace",

  // Files changed threshold for full recompilation
  "groovy.compilation.incrementalThreshold": 50,

  // Maximum files to compile in workspace mode
  "groovy.compilation.maxWorkspaceFiles": 500,

  // Compiled files kept in memory; open documents are never evicted, and
  // least recently used closed files beyond this are recompiled on demand
  "groovy.compilation.cacheMaxEntries": 300
}
```

### REPL Settings

```json
{
  "groovy.repl.enabled": true,
  "groovy.repl.maxSessions": 10,
  "groovy.repl.sessionTimeoutMinutes": 60
}
```

### Semantic Token Customization

You can customize the colors of different code elements, including import statements:

```json
{
  // Customize semantic token colors for Groovy
  "editor.semanticTokenColorCustomizations": {
    "[Default Dark+]": {
      "rules": {
        // Make import class names more visible (yellow-ish)
        "class.declaration:groovy": "#DCDCAA",

        // Or customize all classes
        "class:groovy": "#4EC9B0",

        // Customize other tokens
        "method:groovy": "#C586C0",
        "property:groovy": "#9CDCFE"
      }
    }
  }
}
```

**Note**: Import class names use the `class.declaration` token type, which allows them to be styled distinctly from
other class references. The extension provides multiple TextMate scope fallbacks to ensure good visibility across
different themes.

### Update Checking

The extension periodically checks for new Groovy Language Server releases.

```json
{
  // Check for updates when extension starts
  "groovy.update.checkOnStartup": true,

  // Hours between automatic checks (minimum: 1)
  "groovy.update.checkIntervalHours": 24,

  // When to show notifications: "off", "onlyWhenOutdated", "always"
  "groovy.update.notifications": "onlyWhenOutdated"
}
```

Use `Groovy: Check for Language Server Updates` command to check manually.

## Commands

- `Groovy: Restart Language Server` - Restart the language server if something goes wrong
- `Groovy: Show Language Server Version` - Display the current LSP server version
- `Groovy: Check for Language Server Updates` - Check for new LSP releases

### Gradle tasks

`Gradle: Build`, `Gradle: Test` and `Gradle: Clean` run the root project's task with the project's wrapper (`gradlew`)
when present. Tasks in `tasks.json` use the `groovy-gradle` type, with an optional subproject:

```json
{ "type": "groovy-gradle", "task": "test", "project": ":app" }
```

**Migrating:** the type used to be `gradle`, which belongs to the Gradle for Java extension; tasks of type `gradle` are
no longer handled by this extension. Change their `type` to `groovy-gradle`.

## Status Bar

The extension displays real-time language server status in the status bar:

| Display              | State     | Meaning                |
| -------------------- | --------- | ---------------------- |
| `✓ Groovy`           | Ready     | All features available |
| `⟳ Groovy: Deps`     | Resolving | Loading dependencies   |
| `⟳ Groovy: Indexing` | Indexing  | Analyzing source files |
| `⚠ Groovy`           | Degraded  | Limited functionality  |
| `■ Groovy`           | Stopped   | Server not running     |

**Tip**: Hover over the status bar for details and quick actions (restart, check updates).

### Why This Matters

When dependencies are loading, you may see import errors. The status bar shows "Groovy: Deps" to indicate this is
temporary and will resolve once dependencies finish loading.

## Troubleshooting

**Extension not working?**

1. Check you have Java 17+ installed: `java -version`
2. Try restarting the language server: `Cmd/Ctrl+Shift+P` → "Groovy: Restart Language Server"
3. Check the "Groovy Language Server" output panel for error messages

**File not recognized?** Make sure it has a supported extension or is named `Jenkinsfile`.

## Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for setup and guidelines.

---

**Links:** [GitHub](https://github.com/albertocavalcante/gvy) •
[Issues](https://github.com/albertocavalcante/gvy/issues) • [Language Server](https://github.com/albertocavalcante/gvy)
