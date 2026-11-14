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

   Development happens in the [gvy monorepo](https://github.com/albertocavalcante/gvy/tree/main). The
   [standalone extension repository](https://github.com/albertocavalcante/vscode-groovy) is a read-only mirror.

   ```bash
   git clone https://github.com/albertocavalcante/gvy
   cd ./gvy/editors/code
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
editors/code/
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

### Promise handling

The AI adapter (`client/src/features/ai/`) enforces type-aware floating/misused-promise rules. These include VS Code's
`Thenable` results: await or return work needed by the caller, and attach rejection handling to intentional background
work. `void` alone does not handle rejection. Avoid awaiting a non-modal notification just to satisfy lint; that can
delay its caller until the user dismisses it.

`pnpm run lint` checks the production code. The normal `pnpm test` suite also exercises the actual configured rules with
typed Promise and VS Code Thenable fixtures, including negative cases and awaited/returned/handled controls. Weakening
or removing either rule must fail these tests. Other source modules retain their existing policy while their effects are
classified under [#1801](https://github.com/albertocavalcante/gvy/issues/1801); this is not project-wide enforcement.

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
- **Documentation**: Check the [README](README.md) and
  [extension CI workflow](https://github.com/albertocavalcante/gvy/blob/main/.github/workflows/extension.yml)

## Release Process

Releases are automated through Release Please:

1. Make changes using conventional commits
2. Release Please creates/updates a release PR
3. Review the generated changelog
4. Merge the release PR
5. The VSIX is attached to the GitHub Release. Marketplace and Open VSX publication are not configured.

Thank you for contributing to making Groovy development in VS Code better!

## Run the latest green main

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
periodically (launchd, cron, a login item) is up to you; the repository ships no scheduler. For a prebuilt daily
snapshot, use the
[nightly download instructions](https://github.com/albertocavalcante/gvy/blob/main/docs/installation.md#nightly-download).
An automatic nightly update channel in the extension remains follow-up work in
[#1736](https://github.com/albertocavalcante/gvy/issues/1736).

## Install an exact main commit from CI

For a current `main` snapshot, reuse its source-built server and VSIX without a local Gradle or extension build:

```sh
node editors/code/tools/gvy-install.js --ref main --wait 600 --json
# Equivalent direct entrypoint:
node editors/code/tools/install-extension.js --from-ci --ref main --wait 600 --json
```

This mode needs Node 24+, authenticated `gh`, Java 21+, and a compatible VS Code `code` command. It supports macOS and
Linux. Unlike a local source build, it does not need Gradle, pnpm dependencies, or a separate build checkout.

The installer resolves the ref to a full SHA once. It selects both artifacts from the same successful Extension CI run
for that SHA, verifies GitHub's artifact digests, verifies the bundled server and its clean source identity, checks
editor compatibility, and smoke-checks the server before installation. It then verifies the actual installed server and
client hashes. A matching package version alone does not make an installation current.

The fixed wait limit bounds polling. If the selected run is pending, missing, canceled, or failed, the JSON result
records that state and the receipt needed to resume. Resume that receipt to keep the selected SHA; rerunning a fresh
`--ref main` selects a new snapshot. Use `--from-ci --resume <receipt> --wait 600 --json`, preserving a custom
`--state-root` and local configuration. An explicit `--ref <full-40-character-SHA>` also selects that exact commit. The
command never substitutes a release or an older green commit. Extension CI success does not establish that the broader
project CI has passed.

Resume keeps the original CI run attempt as well as the SHA. A failed or canceled run does not become successful by
waiting. If that run is rerun, start a fresh invocation with `--ref <saved-full-SHA>` to verify the new attempt without
following a newer `main` commit.

Use `--java-home` to select an installed JDK, `--installer code` to select VS Code, `--state-root` for artifact and
receipt storage, and `--bin-dir` for the standalone `gvy` command. The command preserves unrelated files and reports a
conflicting or shadowed command. `--package-only` downloads, verifies, and smoke-checks the pair without changing the
editor or standalone command. Capture stderr in a local log when using `--json`; normal stdout contains the result.

The installer keeps the verified runtime, paired VSIX, and receipt for resuming or recovery. The standalone command's
symlink changes atomically; the editor CLI's installation is a separate operation. A partial failure is recorded as such
and must be resumed or recovered before reporting success. After an update, run **Developer: Reload Window** in an
existing VS Code window.

The macOS state root defaults to `~/Library/Application Support/gvy/source-installs`; Linux uses
`${XDG_STATE_HOME:-~/.local/state}/gvy/source-installs`. CI receipts and commit directories live in its `ci-v1`
subdirectory. Older manual installations are preserved and are not automatically adopted as tool-owned commands.

This CI path is for supported POSIX installations using VS Code. For a requested local checkout or local changes, use
the source-build command below. For a policy that chooses the newest fully green commit, use
[the green-main workflow](#run-the-latest-green-main).

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
