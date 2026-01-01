---
description: Defer work to GitHub issue and track with TODO comments
---

# /defer - Track Deferred Work

Use this workflow when you encounter work that should NOT be addressed in the current session:
- PR review feedback that's out of scope
- Discovered improvements or refactoring opportunities
- Existing TODO comments that should be tracked as issues
- Tech debt that needs visibility

## How It Works

1. **Identify deferred items** - Note unaddressed work during the session
2. **Create GitHub issue** - Track each item with proper labels
3. **Add/update TODO** - Reference the issue in code comments

## Step 1: Create Issue

> [!CAUTION]
> **MANDATORY: Always use a temp file for the issue body.**
> 
> **NEVER use inline `--body "..."` with complex content** - it WILL break due to:
> - Shell escaping issues with backticks, quotes, newlines
> - Truncation of long content
> - Unpredictable behavior with code blocks
>
> **ALWAYS use `--body-file /tmp/issue-body.md` instead.**

```bash
# Step 1: Write body to temp markdown file (REQUIRED)
cat > /tmp/issue-body.md << 'EOF'
## Problem
[Describe the limitation or improvement needed]

## Context
[Why this came up, link to PR if relevant]

## Proposed Approach
[One or more options]

## References
- PR #NNN (if applicable)
EOF

# Step 2: Create issue with --body-file (NEVER use --body inline)
gh issue create -R albertocavalcante/gvy \
  --title "[area] Brief description" \
  --body-file /tmp/issue-body.md \
  --label "enhancement" --label "P3-nice" --label "size/M"

# Step 3: Clean up temp file
rm /tmp/issue-body.md
```

### Label Formula
From `.agent/workflows/github-issues.md`:
- **Type**: `enhancement` | `tech-debt` | `bug`
- **Priority**: `P0-critical` | `P1-must` | `P2-should` | `P3-nice`
- **Size**: `size/XS` | `size/S` | `size/M` | `size/L` | `size/XL`

## Step 2: Add TODO Comment

Use this format to link code locations to issues:

**Kotlin/Java:**
```kotlin
// TODO(#123): Brief description.
//   See: https://github.com/albertocavalcante/gvy/issues/123
```

**YAML/Markdown:**
```yaml
# TODO(#123): Brief description.
#   See: https://github.com/albertocavalcante/gvy/issues/123
```

## Step 3: Reply to Reviewer (if PR feedback)

```
Created #123 to track this improvement. Added TODO with issue link.
```

## Example Session

During PR review, a comment suggests improving synchronization:

```bash
# 1. Write issue body to temp file (MANDATORY)
cat > /tmp/issue-body.md << 'EOF'
## Problem
Using Thread.sleep(100) for synchronization is brittle.

## Context
Raised in PR #313 review comments.

## Proposed Approach
- Option A: LSP4J handshake pattern
- Option B: Custom groovy/ready notification
EOF

# 2. Create issue with --body-file
gh issue create -R albertocavalcante/gvy \
  --title "[e2e] Replace Thread.sleep with proper synchronization" \
  --body-file /tmp/issue-body.md \
  --label "enhancement" --label "tech-debt" --label "P3-nice" --label "size/M"
# Returns: https://github.com/albertocavalcante/gvy/issues/314

# 3. Clean up
rm /tmp/issue-body.md

# 4. Add TODO in code
# // TODO(#314): Replace with proper synchronization.
# //   See: https://github.com/albertocavalcante/gvy/issues/314

# 5. Reply to PR comment: "Created #314 to track this."
```
