# Groovy Language Support

[![CI](https://github.com/albertocavalcante/gvy/actions/workflows/main.yml/badge.svg)](https://github.com/albertocavalcante/gvy/actions/workflows/main.yml)

A VS Code extension that adds Groovy language support with syntax highlighting, code completion, and error checking.

> **Status:** Early release - actively maintained and stable for everyday use.

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

## Setup

**Requires Java 17+** to be installed on your system.

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

That's it! The extension will find Java automatically from your PATH or `JAVA_HOME`.

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

## Development

Want to contribute? Check out the [Contributing Guide](CONTRIBUTING.md).

```bash
git clone https://github.com/albertocavalcante/gvy.git
pnpm install
pnpm run compile
```

### Run the latest green main

Releases lag `main`, so for daily use run `make ext-latest` from a gvy checkout. It installs the extension and server
jar built from the newest `origin/main` commit whose CI passed:

```bash
make ext-latest              # build + install the newest green main
make ext-latest DRY_RUN=1    # show the chosen commit and what would happen
make ext-rollback            # reinstall the previous kept VSIX
```

Then run **Developer: Reload Window**. The build runs in its own clone under `~/.cache/gvy/ext-latest/src`, never in
your worktrees; the clone is marked `.git/ext-latest-owned` and the script refuses any other directory. Only one run
happens at a time (a lock directory under the cache dir; a dead holder's lock is taken over).

A commit counts as green only when its builds actually ran and passed: `CI` needs a passing `Build and Test` job and
`Extension CI` a passing `Build current server` job. When path filters skip a build (the workflow still reports success)
or `Extension CI` did not run, the commit inherits that workflow's state from its parent, so an extension-only commit on
top of a red server commit is not green. Pending, failed and cancelled workflows are never green. The walk goes back up
to 20 commits (max 100) and fails clearly if it cannot establish a green commit.

The command exits early with "already up to date" when the installed server already reports that commit. Before
installing it checks that the built jar reports the target sha and a clean tree, that the VSIX holds that exact jar, and
afterwards that the installed jar is byte-identical. A kept VSIX is reused only after its own `.gls-version` record
matches the commit. The last 3 VSIX files are kept as `~/.local/share/gvy/vsix/gvy-<version>+g<sha7>.vsix` (the
installed one is never pruned; pruning happens after a verified install). Requires `gh` (authenticated), `jq`, `java`,
`pnpm` and `unzip`; Gradle runs through `build-slot` when it is on `PATH`.

| Variable                       | Default                                               | Meaning                                                           |
| ------------------------------ | ----------------------------------------------------- | ----------------------------------------------------------------- |
| `EXT_EDITORS`                  | `code`                                                | Comma list of `code`, `cursor`, `windsurf`, `agy`, `kiro`         |
| `EXT_LATEST_SHA`               | newest green                                          | Build this commit instead (its CI status is still reported)       |
| `EXT_LATEST_ALLOW_UNGREEN`     | `0`                                                   | `1` allows a forced or newest commit without green CI             |
| `EXT_LATEST_MAX_COMMITS`       | `20`                                                  | How many commits to walk back looking for green                   |
| `EXT_LATEST_REQUIRED`          | `CI`                                                  | Workflows (comma list) that must have succeeded                   |
| `EXT_LATEST_PROOF`             | `CI=Build and Test;Extension CI=Build current server` | Job-name prefix that must have run and passed per workflow        |
| `EXT_LATEST_BRANCH`            | `main`                                                | Branch whose history is walked                                    |
| `EXT_LATEST_REPO_URL`          | `origin` of this checkout                             | Clone URL for the build clone                                     |
| `EXT_LATEST_REPO`              | derived from the URL                                  | `owner/repo` used for `gh api` calls                              |
| `EXT_LATEST_SRC_DIR`           | `<cache dir>/src`                                     | Build clone; must be missing or empty, or a clone ext-latest made |
| `EXT_LATEST_OPTIONAL`          | `Extension CI`                                        | Workflows that must succeed only if they ran                      |
| `EXT_LATEST_KEEP`              | `3`                                                   | VSIX files kept for rollback                                      |
| `EXT_LATEST_CACHE_DIR`         | `${XDG_CACHE_HOME:-~/.cache}/gvy/ext-latest`          | Holds the build clone (`src/`)                                    |
| `EXT_LATEST_VSIX_DIR`          | `${XDG_DATA_HOME:-~/.local/share}/gvy/vsix`           | Kept VSIX files                                                   |
| `FORCE=1`                      | off                                                   | Rebuild and reinstall even when up to date                        |
| `ROLLBACK_SHA` (rollback only) | previous build                                        | Reinstall the kept VSIX for this sha prefix                       |

`make ext-rollback` steps back one build from what is installed: the next older kept VSIX in build order (run it again
to go further back; `ROLLBACK_SHA` picks a specific one). Run `make ext-latest` to return to the newest. Running this
periodically (launchd, cron, a login item) is up to you; the repository ships no scheduler. A nightly pre-release VSIX
and an update channel in the extension are tracked separately in #1576.

## Copilot coding agent

See [COPILOT.md](COPILOT.md) for agent-specific setup and the `copilot-setup-steps` workflow.

## Troubleshooting

**Extension not working?**

1. Check you have Java 17+ installed: `java -version`
2. Try restarting the language server: `Cmd/Ctrl+Shift+P` → "Groovy: Restart Server"
3. Check the "Groovy Language Server" output panel for error messages

**File not recognized?** Make sure it has a supported extension or is named `Jenkinsfile`.

## Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for setup and guidelines.

---

**Links:** [GitHub](https://github.com/albertocavalcante/gvy) •
[Issues](https://github.com/albertocavalcante/gvy/issues) • [Language Server](https://github.com/albertocavalcante/gvy)
