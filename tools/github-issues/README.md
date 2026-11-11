# GitHub Issue Tools

This directory contains tools for managing GitHub issues and labels for the gvy monorepo.

## Scripts

### `setup-labels.sh`

This legacy script edits labels across two repositories using their former names. Its replacement is tracked in
[#1614](https://github.com/albertocavalcante/gvy/issues/1614). For gvy's `viz` label, use the scoped synchronization
command in [the issue workflow](../../.agent/workflows/github-issues.md).

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
3. **Area**: Select one or more areas (e.g., `lsp/completion` or `viz`).
4. **Priority**: Select a priority (P0-P3).
5. **Size**: Estimate the effort (XS-XL).
6. **Body**: Opens your default `$EDITOR` to write the description.
7. **Submit**: Creates the issue on GitHub via `gh`.

**Dependencies:**

- Python 3.11 or later
- `gh` (GitHub CLI)

## Configuration

- **`github-labels.json`**: The single source of truth for all label definitions. Both scripts rely on this file.

## Development checks

From this directory, use the locked Python tools and run the wizard tests without contacting GitHub:

```bash
uv sync --locked
uv run --locked python -m unittest -v
uv run --locked ruff format --check issue_wizard.py test_issue_wizard.py
uv run --locked ruff check issue_wizard.py test_issue_wizard.py
uv run --locked basedpyright issue_wizard.py test_issue_wizard.py
uv run --locked ty check issue_wizard.py test_issue_wizard.py
```

The wizard requires a list of label objects with string `name`, `color`, and `description` fields. Additional fields are
ignored.
