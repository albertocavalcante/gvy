---
description: GitHub issue creation patterns and label conventions
---

// turbo

# GitHub Issues

## Project Variables

| Variable     | Value                   |
| ------------ | ----------------------- |
| `OWNER/REPO` | `albertocavalcante/gvy` |

## Quick Issue Creation

```bash
gh issue create -R OWNER/REPO \
  --title "[lsp/completion] Add method signatures" \
  --body-file github-issues/issue.md \
  --label "enhancement" --label "lsp/completion" --label "P1-must" --label "size/M"
```

## Label Formula: Type + Area + Priority + Size

### Type Labels

- `bug` - Something isn't working
- `enhancement` - New feature or request
- `documentation` - Documentation improvements
- `architecture` - Architectural changes
- `tech-debt` - Technical debt cleanup

### Area Labels

- `lsp/completion` - Completion features
- `lsp/navigation` - Navigation features
- `lsp/diagnostics` - Diagnostic features
- `lsp/hover` - Hover information
- `lsp/symbols` - Symbol features

### Priority Labels

- `P0-critical` - Must fix immediately
- `P1-must` - Must have for next release
- `P2-should` - Should have
- `P3-nice` - Nice to have

### Size Labels

- `size/XS` - Extra small (< 1 hour)
- `size/S` - Small (< 4 hours)
- `size/M` - Medium (1-2 days)
- `size/L` - Large (3-5 days)
- `size/XL` - Extra large (> 1 week)

## Create Labels

```bash
gh label create "lsp/completion" -c "c2e0c6" -d "Completion features"
```

## Dependency bug labels

Follow [the dependency bug policy](../../docs/dependency-bugs.md), including release/fix searches and candidate-version
tests before introducing a workaround. Add `dependency-bug` and the relevant `dep/<name>` label. Add `upstream/reported`
for a linked upstream report and `upstream/fix-released` for a released fix covering the issue's defect scope. Keep
exact versions, test results, and gvy adoption status in the issue body.

The versioned source is `tools/github-issues/github-labels.json`. Preview this task's additive label set with:

```bash
jq '[.[] | select(.name == "dependency-bug" or (.name | startswith("dep/")) or (.name | startswith("upstream/")))]' \
  tools/github-issues/github-labels.json
```

Apply only that set to gvy, preserving the existing label taxonomy:

```bash
(
  set -euo pipefail
  jq -c '.[] | select(.name == "dependency-bug" or (.name | startswith("dep/")) or (.name | startswith("upstream/")))' \
    tools/github-issues/github-labels.json |
    while IFS= read -r entry; do
      label_name=$(jq -r '.name' <<<"$entry")
      label_color=$(jq -r '.color' <<<"$entry")
      label_description=$(jq -r '.description' <<<"$entry")
      gh label create "$label_name" --repo albertocavalcante/gvy --force \
        --color "$label_color" --description "$label_description" || exit 1
    done
)
```

Use this scoped sync for these labels. The legacy `setup-labels.sh` targets former repository names and has broader side
effects; [#1614](https://github.com/albertocavalcante/gvy/issues/1614) tracks its replacement.
