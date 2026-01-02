# GitHub Actions Utilities

## macOS Self-Hosted Runner Setup

Automates installation of a local GitHub Actions runner on macOS. Use this to:

- Run heavy CI workloads (E2E tests) on your Mac
- Avoid GitHub-hosted runner costs/time limits
- Test workflows locally before pushing

### Prerequisites

- **macOS** (arm64 or x64)
- **Homebrew** packages:
  ```bash
  brew install gh jq
  gh auth login
  ```
- **Admin access** to this repository

### Quick Start

```bash
./tools/github/actions/setup-macos-runner.sh
```

This will automatically:

1. Generate a runner registration token (via `gh` CLI)
2. Download and verify the GitHub Actions runner (SHA256 checksum validation)
3. Configure the runner with project-specific labels
4. Install and start the background service

**Verification**: Check that the runner appears online at:\
[Repository Settings → Actions → Runners](https://github.com/albertocavalcante/groovy-lsp/settings/actions/runners)

---

## Advanced Usage

### Command-Line Options

```bash
# Skip service installation (run in foreground only)
./tools/github/actions/setup-macos-runner.sh --no-svc

# Use specific runner version
./tools/github/actions/setup-macos-runner.sh --version 2.329.0

# Custom runner name and extra labels
./tools/github/actions/setup-macos-runner.sh --name my-mac --labels "ssd,fast"

# Manual mode (provide token explicitly)
./tools/github/actions/setup-macos-runner.sh <RUNNER_TOKEN>

# View all options
./tools/github/actions/setup-macos-runner.sh --help
```

### Runner Labels (Auto-Applied)

Every runner created by this script receives these labels:

| Label           | Purpose                    | Usage                |
| --------------- | -------------------------- | -------------------- |
| `self-hosted`   | Required by GitHub Actions | Auto-added           |
| `macOS`         | OS identifier              | Filter by OS         |
| `arm64` / `x64` | Architecture               | Filter by chip       |
| `groovy-lsp`    | Project scope              | Prevent runner theft |
| `local-macos`   | **CI target label**        | Use in `runs-on`     |

---

## Using in CI Workflows

### Option 1: Manual Dispatch

When manually triggering the CI workflow:

1. Go to **Actions** → **CI** → **Run workflow**
2. Set `runner_label` = `local-macos`

### Option 2: Automatic (Repository Variable)

Set a permanent default:

1. Go to **Settings** → **Secrets and variables** → **Actions** → **Variables**
2. Create variable: `CI_RUNNER_LABEL` = `local-macos`

All CI runs will now use your local macOS runner.

---

## Troubleshooting

### "gh: command not found"

Install GitHub CLI:

```bash
brew install gh
gh auth login
```

### "gh CLI not authenticated"

Run authentication flow:

```bash
gh auth login
```

### Runner already exists

The script will prompt you to remove the old runner directory. Answer `y` to replace.

### Service won't start

Check service status:

```bash
cd ~/.gha-runners/groovy-lsp-*
./svc.sh status
```

View logs:

```bash
cd ~/.gha-runners/groovy-lsp-*
cat _diag/*.log
```

### Cleanup / Uninstall

Use the automated cleanup script to remove all runners:

```bash
# Clean up all runners (with confirmation prompt)
./tools/github/actions/cleanup-macos-runner.sh

# Also remove cached downloads
./tools/github/actions/cleanup-macos-runner.sh --all

# Skip GitHub unregistration (faster, but leaves orphaned entries)
./tools/github/actions/cleanup-macos-runner.sh --skip-unregister
```

**What it does:**

1. Finds all `groovy-lsp` runner directories
2. Stops and uninstalls services
3. Unregisters runners from GitHub (removes from web UI)
4. Deletes runner directories
5. Optionally cleans cache (`--all`)

**Manual cleanup** (if needed):

```bash
# Stop service
cd ~/.gha-runners/groovy-lsp-*
./svc.sh stop
./svc.sh uninstall

# Unregister from GitHub
./config.sh remove --token <REMOVAL_TOKEN>

# Remove directory
cd ~
rm -rf ~/.gha-runners/groovy-lsp-*
```

---

## Security

- **Checksum validation**: All runner downloads are verified against official SHA256 checksums from GitHub releases
- **Labeled runners**: The `groovy-lsp` label prevents unauthorized job execution
- **Token scoping**: Registration tokens expire and are single-use
