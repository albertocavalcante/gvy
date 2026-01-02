# GitHub Issue Tools

This directory contains tools for managing GitHub issues and labels for the Groovy LSP project.

## Scripts

### `setup-labels.sh`

Sets up the standard label taxonomy in the repository.

**Usage:**

```bash
./setup-labels.sh [OPTIONS]
```

**Options:**

- `--dry-run`: Preview changes without applying them.
- `--groovy-lsp-only`: Only setup labels for the groovy-lsp repository.
- `--vscode-only`: Only setup labels for the vscode-groovy repository.

**Dependencies:**

- `gh` (GitHub CLI)
- `jq`

### `issue_wizard.py`

An interactive CLI tool to create GitHub issues that adhere to the project's labeling standards. It guides you through
selecting the correct Type, Area, Priority, and Size.

**Usage:**

```bash
python3 issue_wizard.py
```

**Workflow:**

1. **Title**: Enter a descriptive title.
2. **Type**: Select one (Bug, Enhancement, etc.).
3. **Area**: Select one or more LSP areas (e.g., `lsp/completion`).
4. **Priority**: Select a priority (P0-P3).
5. **Size**: Estimate the effort (XS-XL).
6. **Body**: Opens your default `$EDITOR` to write the description.
7. **Submit**: Creates the issue on GitHub via `gh`.

**Dependencies:**

- Python 3.x
- `gh` (GitHub CLI)

## Configuration

- **`github-labels.json`**: The single source of truth for all label definitions. Both scripts rely on this file.
