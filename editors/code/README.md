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

| File Type | Extensions | What it's for |
|-----------|------------|---------------|
| Groovy Scripts | `.groovy`, `.gvy`, `.gy`, `.gsh` | General Groovy code |
| Gradle Build | `.gradle` | Build scripts |
| Jenkins Pipeline | `Jenkinsfile`, `Jenkinsfile.*` | CI/CD pipelines |

## Setup

**Requires Java 17+** to be installed on your system.

1. Install this extension from the VS Code Marketplace
2. Open any `.groovy`, `.gradle`, or `Jenkinsfile` - language support starts automatically

That's it! The extension will find Java automatically from your PATH or `JAVA_HOME`.

## Configuration

### Basic Settings

```json
{
  // Java configuration (required)
  "groovy.java.home": "/path/to/your/java17",

  // Enable/disable code formatting
  "groovy.format.enable": true,

  // Trace LSP communication (off, messages, verbose)
  "groovy.trace.server": "off"
}
```

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
  // File patterns to recognize as Jenkinsfiles
  "groovy.jenkins.filePatterns": ["Jenkinsfile", "vars/*.groovy"],

  // Configure shared libraries
  "groovy.jenkins.sharedLibraries": [
    {
      "name": "my-pipeline-lib",
      "jar": "/path/to/my-pipeline-lib.jar",
      "sourcesJar": "/path/to/my-pipeline-lib-sources.jar"
    }
  ],

  // GDSL files for DSL enhancements
  "groovy.jenkins.gdslPaths": ["/path/to/jenkins.gdsl"],

  // Execute GDSL scripts (disabled by default for security)
  "groovy.jenkins.gdslExecution.enabled": true,

  // Optional: Jenkins plugin discovery overrides
  "groovy.jenkins.pluginsTxtPath": "/path/to/plugins.txt",
  "groovy.jenkins.plugins": ["workflow-basic-steps", "pipeline-model-definition"],
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
  "groovy.compilation.maxWorkspaceFiles": 500
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

## Status Bar

The extension displays real-time language server status in the status bar:

| Display | State | Meaning |
|---------|-------|---------|
| `✓ Groovy` | Ready | All features available |
| `⟳ Groovy: Deps` | Resolving | Loading dependencies |
| `⟳ Groovy: Indexing` | Indexing | Analyzing source files |
| `⚠ Groovy` | Degraded | Limited functionality |
| `■ Groovy` | Stopped | Server not running |

**Tip**: Hover over the status bar for details and quick actions (restart, check updates).

### Why This Matters

When dependencies are loading, you may see import errors. The status bar shows "Groovy: Deps" to indicate this is temporary and will resolve once dependencies finish loading.

## Development

Want to contribute? Check out the [Contributing Guide](CONTRIBUTING.md).

```bash
git clone https://github.com/albertocavalcante/gvy.git
pnpm install
pnpm run compile
```

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

**Links:** [GitHub](https://github.com/albertocavalcante/gvy) • [Issues](https://github.com/albertocavalcante/gvy/issues) • [Language Server](https://github.com/albertocavalcante/gvy)
