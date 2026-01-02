---
description: GitHub CLI patterns for API calls, PR management, and repository exploration
---

# GitHub CLI Patterns

<critical>
ALWAYS use `gh` CLI for GitHub content. NEVER use curl/wget/fetch for GitHub URLs.
</critical>

## Repository Info

| Variable | Value |
|----------|-------|
| `OWNER/REPO` | `albertocavalcante/groovy-lsp` |

---

## Common Operations

### PR Management
```bash
# View PR
gh pr view <NUMBER>
gh pr view <NUMBER> --json title,body,state,reviews

# List PRs
gh pr list --state open
gh pr list --author @me

# Create PR (use temp file for body)
cat > /tmp/pr-body.md << 'EOF'
## Summary
Brief description

## Changes
- Change 1
- Change 2
EOF
gh pr create --title "feat: description" --body-file /tmp/pr-body.md
rm /tmp/pr-body.md

# Check CI status
gh pr checks <NUMBER>
gh pr checks <NUMBER> --watch
```

### Issue Management
```bash
# View issue
gh issue view <NUMBER>
gh issue view <NUMBER> --json title,body,labels,comments

# Create issue (use temp file for body)
cat > /tmp/issue-body.md << 'EOF'
## Problem
Description

## Proposed Solution
Approach
EOF
gh issue create --title "[area] description" --body-file /tmp/issue-body.md --label "enhancement"
rm /tmp/issue-body.md
```

### Repository Content
```bash
# File content (base64 decode)
gh api repos/OWNER/REPO/contents/path/to/file.md --jq '.content' | base64 -d

# Directory listing
gh api repos/OWNER/REPO/contents/path --jq '.[].name'

# Search repos
gh search repos "keyword language:kotlin" --limit 5
```

---

## GraphQL API

### Using Saved Queries
```bash
# Queries are stored in .agent/queries/
gh api graphql -F owner=':owner' -F name=':repo' -F number=<N> \
  -f query="$(cat .agent/queries/pr-review-threads.graphql)"
```

### Magic Variables
The `gh` CLI auto-resolves these from git remote:
- `:owner` → repository owner
- `:repo` → repository name

---

## CI/Workflow Operations

```bash
# List recent runs
gh run list --limit 5

# View run details
gh run view <RUN_ID>
gh run view <RUN_ID> --log
gh run view <RUN_ID> --log-failed

# Re-run failed jobs
gh run rerun <RUN_ID> --failed
```

---

## Output Best Practices

### Structured Output to Files
```bash
# Always use temp files for complex data
gh pr view 123 --json comments > /tmp/pr-123-comments.json
# ... process ...
rm /tmp/pr-123-comments.json
```

### JSON Processing
```bash
# Extract specific fields
gh pr view 123 --json title,state --jq '{title, state}'

# Filter arrays
gh pr list --json number,title --jq '.[] | select(.title | contains("fix"))'
```
