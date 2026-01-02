---
description: GitHub Actions configuration, SHA pinning, and workflow debugging
---

// turbo-all

# GitHub Actions Best Practices

## SHA Pinning (REQUIRED)

ALWAYS pin GitHub Actions to full SHA commit hash, NOT version tags.
Add an inline comment with the full semver version for readability.

✅ Good:

```yaml
uses: actions/checkout@8e8c483db84b4bee98b60c0593521ed34d9990e8 # v6.0.1
uses: actions/setup-java@f2beeb24e141e01a676f977032f5a29d81c9e27e # v5.1.0
```

❌ Bad:

```yaml
uses: actions/checkout@v4
uses: actions/setup-java@v4
```

**Rationale**: SHA pinning prevents supply chain attacks where a tag could be moved to point to malicious code. The inline version comment maintains readability.

## Find SHA for a Version

```bash
gh api repos/OWNER/REPO/git/refs/tags/VERSION --jq '.object.sha'
```

Example:

```bash
gh api repos/actions/checkout/git/refs/tags/v6.0.1 --jq '.object.sha'
```

## Debugging Workflow Failures

1. Check workflow run status:
   ```bash
   gh run list --limit 5
   gh run view RUN_ID
   ```

2. Get job logs:
   ```bash
   gh run view RUN_ID --log
   gh run view RUN_ID --log-failed
   ```

3. Re-run failed jobs:
   ```bash
   gh run rerun RUN_ID --failed
   ```
