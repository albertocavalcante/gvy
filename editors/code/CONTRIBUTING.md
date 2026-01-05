# Contributing to VS Code Groovy Extension

Thank you for your interest in contributing to the Groovy Language Support extension for Visual Studio Code!

## Development Setup

### Prerequisites

- **Node.js 20+**: For extension development
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
   GLS_USE_PINNED=true npm run prepare-server
   ```

Note: Users get the **latest release by default**. The pinned version serves as:

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
npm run lint
npm run check-types
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
   npm run compile
   npm run lint
   npm run check-types
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

**Running All Tests** To run both unit and integration tests:

```bash
npm run test:all
```

_Note: Integration tests on Linux require `xvfb`. Using the CI environment or a Docker container is recommended for
headless testing._

**Running Unit Tests** Unit tests cover individual components in isolation:

```bash
npm test
```

**Running Integration Tests** Integration tests run within a VS Code Extension Host instance:

```bash
npm run test:integration
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

1. Clean build: `rm -rf node_modules client/node_modules && npm install`
2. Reset server: `rm server/*.jar && npm run prepare-server`
3. Check Node.js version: `node --version` (should be 20+)

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
