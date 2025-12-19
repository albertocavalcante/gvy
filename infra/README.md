# Infrastructure (Self-Hosted Runners)

This directory contains the Terraform/OpenTofu configuration to provision ephemeral GitHub Actions runners on Magalu Cloud.

## Architecture

```
┌─────────────────────────────┐     ┌─────────────────────────────┐
│  runner-provision.yml       │     │  runner-destroy.yml         │
│  (Manual trigger)           │     │  (Manual + Nightly cleanup) │
│                             │     │                             │
│  Provisions Magalu Cloud VM │     │  Tears down VM              │
│  Registers GitHub runner    │     │  Deregisters runner         │
└─────────────────────────────┘     └─────────────────────────────┘
            │                                    │
            └──────────┬─────────────────────────┘
                       │
                       ▼
              ┌─────────────────┐
              │     ci.yml      │
              │                 │
              │ runner_label:   │
              │   - self-hosted │
              │   - magalu      │
              │   - cloud       │
              └─────────────────┘
```

## Workflow Usage

### 1. Provision a Runner
```bash
# Via GitHub UI: Actions → "Infra: Provision Magalu Runner" → Run workflow
# Or via CLI:
gh workflow run runner-provision.yml
```

### 2. Run CI on Magalu
```bash
# Via GitHub UI: Actions → CI → Run workflow → runner_label: magalu
# Or via CLI:
gh workflow run ci.yml -f runner_label=magalu
```

### 3. Destroy Runner (when done)
```bash
# Via GitHub UI: Actions → "Infra: Destroy Magalu Runner" → Run workflow
# Or wait for nightly cleanup at 3 AM UTC
gh workflow run runner-destroy.yml
```

## Required Secrets

To run the Magalu Runner workflows, the following repository secrets must be set in GitHub:

| Secret | Description |
| :--- | :--- |
| `TF_API_TOKEN` | Terraform Cloud User API Token (for state management) |
| `GH_PAT_RUNNER` | GitHub Personal Access Token for runner registration. **Required scopes:** `repo` (for repo-level runners) or `admin:org` (for org-level runners). See [GitHub docs](https://docs.github.com/en/actions/hosting-your-own-runners/managing-self-hosted-runners/autoscaling-with-self-hosted-runners#authentication-requirements). |
| `MGC_API_KEY` | Magalu Cloud API Key (for provisioning VMs) |

## GitHub Environments

The workflows use [GitHub Environments](https://docs.github.com/en/actions/deployment/targeting-different-environments/using-environments-for-deployment) for approval gates:

| Environment | Purpose | Approval Required |
| :--- | :--- | :--- |
| `production` | Manual approval for infrastructure changes | ✅ Yes |
| `cleanup` | Auto-approval for scheduled nightly cleanup | ❌ No |

### Setup via `gh` CLI

The `gh` CLI doesn't have direct environment commands, but we can use the GitHub API:

```bash
# 1. Create the 'production' environment (requires manual approval)
gh api -X PUT /repos/albertocavalcante/groovy-lsp/environments/production

# 2. Add yourself as required reviewer for 'production'
#    Replace YOUR_GITHUB_USER_ID with your numeric user ID
#    To find your user ID: gh api /users/albertocavalcante --jq '.id'
gh api -X PUT /repos/albertocavalcante/groovy-lsp/environments/production \
  -f 'reviewers[][type]=User' \
  -F 'reviewers[][id]=YOUR_GITHUB_USER_ID'

# 3. Create the 'cleanup' environment (no approval needed)
gh api -X PUT /repos/albertocavalcante/groovy-lsp/environments/cleanup

# 4. Verify environments exist
gh api /repos/albertocavalcante/groovy-lsp/environments --jq '.environments[].name'
```

<details>
<summary><strong>📖 How Environments Work</strong></summary>

When a job specifies `environment: production`, GitHub Actions:

1. **Pauses** the workflow before that job starts
2. **Notifies** required reviewers (via email/GitHub UI)
3. **Waits** for approval (or timeout after 30 days)
4. **Runs** the job only after approval

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  plan job   │ ──▶ │  APPROVAL   │ ──▶ │  apply job  │
│  (runs)     │     │  GATE       │     │  (blocked)  │
└─────────────┘     └─────────────┘     └─────────────┘
                          ▲
                          │
                    Reviewer clicks
                    "Approve" in UI
```

In `runner-destroy.yml`, the environment is dynamic:
```yaml
environment: ${{ github.event_name == 'schedule' && 'cleanup' || 'production' }}
```
- **Scheduled runs** → `cleanup` (no approval)
- **Manual runs** → `production` (requires approval)

</details>

### Setup via GitHub UI

1. Go to **Settings → Environments → New environment**
2. Create `production`:
   - Enable **"Required reviewers"**
   - Add yourself (or your team) as a reviewer
3. Create `cleanup`:
   - No protection rules needed (allows auto-approval)

## Terraform State

State is managed remotely via Terraform Cloud:
- Organization: `alberto`
- Workspace: `groovy-lsp-runner`

