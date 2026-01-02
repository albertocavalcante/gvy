---
description: GitHub issue creation patterns and label conventions
---

// turbo

# GitHub Issues

## Project Variables

| Variable     | Value                          |
| ------------ | ------------------------------ |
| `OWNER/REPO` | `albertocavalcante/groovy-lsp` |

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
