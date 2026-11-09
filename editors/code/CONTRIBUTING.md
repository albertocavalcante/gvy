# Contributing to VS Code Groovy Extension

Thank you for your interest in contributing to the Groovy Language Support extension for Visual Studio Code!

## Development Setup

### Prerequisites

- **Node.js 22+**: For extension development
- **pnpm**: 12 (CI pins 12.14.0). The manifest has no `packageManager` field on purpose: with one, pnpm writes a second
  YAML document into `pnpm-lock.yaml` that Bazel's `rules_js` cannot parse
- **Java 17+**: Required for the Groovy Language Server
- **VS Code**: For testing the extension
- **Git**: For version control

### Getting Started

1. **Clone the repository**

   ```bash
   git clone https://github.com/albertocavalcante/gvy.git
   cd gvy
   ```

2. **Install dependencies**

   ```bash
   pnpm install
   ```

3. **Prepare the Groovy Language Server**

   ```bash
   pnpm run prepare-server
   ```

4. **Compile the extension**
   ```bash
   pnpm run compile
   ```

## Development Workflow

### Project Structure

```
gvy/
├── client/src/          # Extension source code
│   ├── commands/        # VS Code commands
│   ├── configuration/   # Configuration management
│   ├── java/           # Java detection and management
│   ├── server/         # Language Server client
│   ├── ui/             # UI components (status bar, etc.)
│   └── extension.ts    # Main extension entry point
├── server/             # Language Server JAR
├── syntaxes/           # TextMate grammars
└── tools/              # Build and setup scripts
```

### Available Scripts

- `pnpm run compile` - Compile TypeScript and bundle with esbuild
- `pnpm run watch` - Watch mode for development
- `pnpm run lint` - Run ESLint
- `pnpm run check-types` - TypeScript type checking
- `pnpm run package` - Build and package VSIX
- `pnpm run prepare-server` - Download/prepare Groovy Language Server

### Updating the Pinned LSP Version

When a new stable LSP version is released:

1. Update `editors/code/tools/prepare-server.js`:
   - `PINNED_RELEASE_TAG = "v0.x.y"`
   - `PINNED_JAR_ASSET = "gls-0.x.y.jar"`
   - `PINNED_DOWNLOAD_URL = ...`
   - `PINNED_JAR_SHA256 = "..."` (get from release checksums.txt)

2. Update `editors/code/AGENTS.md`:
   - Update "Pinned LSP" line with new version

3. Test fallback behavior:
   ```bash
   GLS_USE_PINNED=true pnpm run prepare-server
   ```

Preparation defaults to the pinned release. Set `GLS_CHANNEL=release` to select the latest stable release or
`GLS_LOCAL_JAR` to select a local JAR. The pinned version serves as:

- Fallback for network issues (with `GLS_ALLOW_PINNED_FALLBACK=true`)
- Explicit stability option (with `GLS_USE_PINNED=true`)
- Emergency escape hatch if latest has critical bugs

### Testing the Extension

1. **Open in VS Code**

   ```bash
   code .
   ```

2. **Launch Extension Development Host**
   - Press `F5` or go to Run → Start Debugging
   - This opens a new VS Code window with your extension loaded

3. **Test with Groovy files**
   - Create or open `.groovy`, `.gradle`, or `Jenkinsfile` files
   - Verify language features work (syntax highlighting, completion, etc.)

4. **Check the Output panel**
   - View → Output → Select "Groovy Language Server"
   - Monitor for any errors or issues

### Code Quality

We enforce code quality through automated checks:

- **ESLint**: Code style and potential issues
- **TypeScript**: Type safety
- **Bundle size**: Must stay under 2MB

Run quality checks:

```bash
pnpm run lint
pnpm run check-types
```

## Making Changes

### Commit Guidelines

We use [Conventional Commits](https://www.conventionalcommits.org/) for automated version management:

- `feat:` - New feature (minor version bump)
- `fix:` - Bug fix (patch version bump)
- `feat!:` or `fix!:` - Breaking change (major version bump)
- `docs:` - Documentation changes
- `chore:` - Maintenance tasks
- `refactor:` - Code refactoring
- `test:` - Adding tests

**Examples:**

```bash
git commit -m "feat: add Gradle task provider integration"
git commit -m "fix: resolve Java detection on Windows"
git commit -m "docs: update configuration examples"
```

### Pull Request Process

1. **Create a feature branch**

   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes**
   - Follow existing code patterns
   - Add appropriate error handling
   - Update documentation if needed

3. **Test locally**

   ```bash
   pnpm run compile
   pnpm run lint
   pnpm run check-types
   ```

4. **Create a pull request**
   - Include a clear description
   - Reference any related issues
   - Ensure CI checks pass

### CI/CD Pipeline

Our automated pipeline includes:

- **PR Checks**: Lint, type check, build, bundle size validation
- **Multi-platform testing**: Linux, Windows, macOS
- **Automatic releases**: Via Release Please when PRs are merged

## Architecture Guidelines

### Extension Architecture

- **Modular design**: Separate concerns into focused modules
- **Error handling**: Graceful degradation with helpful error messages
- **Configuration**: Reactive to VS Code setting changes
- **Performance**: Minimal startup impact, efficient resource usage

### Language Server Integration

- **Robust connection handling**: Automatic restart capabilities
- **Java runtime detection**: Support multiple Java installations
- **Server lifecycle management**: Proper startup, shutdown, restart

### Adding New Features

1. **Commands**: Add to `client/src/commands/`
2. **Configuration**: Update `package.json` contributes section
3. **UI Elements**: Add to `client/src/ui/`
4. **Server Communication**: Extend `client/src/server/`

## Testing

### Automated Testing

We use automated tests to ensure the quality of the extension.

**Running All Tests** Build and prepare the server from the current checkout before running editor tests. From the
repository root:

```bash
./gradlew :gls:stageEditorServer
cd editors/code
gvy_test_jar="$(pwd)/../../gls/build/editor-server/gls.jar"
GLS_LOCAL_JAR="$gvy_test_jar" FORCE_DOWNLOAD=true pnpm install --frozen-lockfile
GVY_TEST_SERVER_JAR="$gvy_test_jar" pnpm run test:all
```

The editor suite checks that the bundled JAR has the same SHA-256 as this candidate and invokes VS Code's built-in
implementation provider. `GVY_TEST_SERVER_JAR` accepts an explicit candidate path; otherwise tests use the staged path
in this checkout. Rebuild and prepare the JAR after backend changes. Extension CI builds it once and uses that artifact
for Linux, macOS, Windows, and VSIX packaging whenever code changes.

CI also unpacks the VSIX and runs the implementation tests against its packaged bundle and JAR before uploading it. To
select an unpacked package locally, set `GVY_TEST_EXTENSION_ROOT` to its `extension` directory and run
`pnpm exec vscode-test --grep "Implementation navigation" --fail-zero --forbid-pending` with the candidate JAR
configured.

After preparing the current server, run both suites with:

```bash
pnpm run test:all
```

_Note: Integration tests on Linux require `xvfb`. Using the CI environment or a Docker container is recommended for
headless testing._

**Running Unit Tests** Unit tests cover individual components in isolation:

```bash
pnpm test
```

**Running Integration Tests** Integration tests run within a VS Code Extension Host instance:

```bash
pnpm run test:integration
```

**Debugging Tests** You can debug tests directly in VS Code:

1. Open the Debug view (`Cmd+Shift+D` / `Ctrl+Shift+D`).
2. Select **"Extension Tests"** from the configuration dropdown.
3. Press `F5` to start debugging.

### Manual Testing Checklist

- [ ] Extension activates without errors
- [ ] Groovy files are recognized and highlighted
- [ ] Language server starts successfully
- [ ] Code completion works
- [ ] Diagnostics appear for syntax errors
- [ ] Commands execute without errors
- [ ] Settings changes are applied
- [ ] Extension deactivates cleanly

### File Type Testing

Test with these file types:

- `.groovy` - General Groovy scripts
- `.gradle` - Gradle build files
- `Jenkinsfile` - Jenkins Pipeline files
- `.gvy`, `.gy`, `.gsh` - Alternative Groovy extensions

### Platform Testing

Verify functionality on:

- Linux (primary CI platform)
- Windows (path separator handling, Java detection)
- macOS (Java detection, file permissions)

## Troubleshooting Development Issues

### Extension Won't Start

1. Check VS Code Developer Console (`Help → Toggle Developer Tools`)
2. Verify Java 17+ is installed: `java -version`
3. Check Groovy Language Server JAR exists: `ls -la server/gls.jar`
4. Review extension output panel

### Build Issues

1. Clean build: `rm -rf node_modules client/node_modules && pnpm install`
2. Reset server: `rm server/*.jar && pnpm run prepare-server`
3. Check Node.js version: `node --version` (should be 22+)

### Language Server Issues

1. Check Java installation and PATH
2. Verify server JAR integrity
3. Review server logs in Output panel
4. Try manual server restart: `Groovy: Restart Server`

## Getting Help

- **Issues**: [GitHub Issues](https://github.com/albertocavalcante/gvy/issues)
- **Discussions**: [GitHub Discussions](https://github.com/albertocavalcante/gvy/discussions)
- **Documentation**: Check the [README](README.md) and [CI/CD Setup](.github/CI_CD_SETUP.md)

## Release Process

Releases are automated through Release Please:

1. Make changes using conventional commits
2. Release Please creates/updates a release PR
3. Review the generated changelog
4. Merge the release PR
5. Extension is automatically published to VS Code Marketplace

Thank you for contributing to making Groovy development in VS Code better!

## Build and install this checkout

From the repository root:

```sh
make ext-doctor
make ext-vscode-source
```

The source command builds and stages the current checkout's server, runs its CLI and LSP startup/shutdown smoke checks,
installs locked extension dependencies, runs helper checks and unit tests, and packages the extension. Before
installing, it verifies that the VSIX contains the exact staged server and compiled client. It never falls back to a
release, nightly, or another checkout. It does not fetch Git updates; select the desired revision first. Local changes
are allowed and reported as `dirty` in the receipt.

Prerequisites: JDK 21+, Node 24+, pnpm 12+, Git, and a compatible VS Code CLI. Set `JAVA_HOME` to the build JDK. Build
storage and the Gradle cache need at least 1 GiB free each; a cold build may need more. Choose another build location
when space is limited:

```sh
node editors/code/tools/install-extension.js --from-source \
  --build-root /path/with-space/build \
  --java-home /path/to/jdk21 --installer code --json 2>source-build.log
```

`GVY_BUILD_ROOT` is the environment equivalent of `--build-root`. The default is `editors/code/dist/source/`.
`GRADLE_USER_HOME` remains authoritative when set; otherwise the command keeps its Gradle cache inside the build root.
The command checks the requested editor before building and never substitutes another editor. It leaves Java and editor
settings unchanged.

The build root contains `artifacts/gvy-source.vsix` and `artifacts/install-result.json` with the checkout SHA, dirty
status, versions, artifact hashes, and final stage. `--json` emits one result object on stdout; command logs go to
stderr. For a failed build, inspect the reported stage and log. `--package-only` builds and verifies without installing.
`--doctor` checks prerequisites without building, although it may create build/cache directories.

A source installation holds guards in the extension directory and build root. Concurrent installs fail rather than
sharing intermediate files. If an interrupted process leaves `.source-install.lock`, inspect its `owner.json` and verify
the recorded process has stopped before removing that guard and retrying.

To reinstall an existing package without building:

```sh
node editors/code/tools/install-extension.js --vsix /path/to/gvy-source.vsix --installer code
```

This uses VS Code's normal extension replacement operation. Keep a previous VSIX if you need to roll back; build and
verification failures do not invoke the editor installer. The source command retains its build/cache for fast repeat
runs.
