# Infrastructure (Self-Hosted Runners)

This directory contains the Terraform/OpenTofu configuration to provision ephemeral GitHub Actions runners on Magalu
Cloud.

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

| Secret          | Description                                           |
| :-------------- | :---------------------------------------------------- |
| `TF_API_TOKEN`  | Terraform Cloud User API Token (for state management) |
| `GH_PAT_RUNNER` | GitHub Personal Access Token for runner registration  |
| `MGC_API_KEY`   | Magalu Cloud API Key (for provisioning VMs)           |

### Creating the GitHub PAT (`GH_PAT_RUNNER`)

GitHub does not support creating PATs via CLI for security reasons. Create one manually:

1. Go to
   [GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)](https://github.com/settings/tokens)
2. Click **"Generate new token (classic)"**
3. Set a descriptive name (e.g., `groovy-lsp-runner`)
4. Select scopes:
   - `repo` — for repository-level runners
   - `admin:org` — for organization-level runners (if applicable)
5. Generate and copy the token
6. Add to GitHub Secrets:
   ```bash
   gh secret set GH_PAT_RUNNER
   # Paste token when prompted
   ```

### Creating the Magalu Cloud API Key (`MGC_API_KEY`)

Use the MGC CLI to create an API key:

```bash
# 1. Login to Magalu Cloud (browser-based OAuth)
mgc auth login

# 2. Create an API key with required scopes for VM provisioning
mgc auth api-key create --name="groovy-lsp-runner" \
  --description="CI runner provisioning" \
  --scopes='["virtual-machine.read", "virtual-machine.write", "network.read", "network.write", "gdb:ssh-pkey-r", "gdb:ssh-pkey-w"]'

# Output will show: uuid: <ID>

# 3. Get the full API key details using the UUID
mgc auth api-key get <UUID>

# Output includes:
#   api_key: <THIS IS THE VALUE FOR MGC_API_KEY>
#   key_pair_id: (for Object Storage, not needed here)
#   key_pair_secret: (for Object Storage, not needed here)

# 4. Copy the api_key value and add to GitHub Secrets
gh secret set MGC_API_KEY
# Paste the api_key value when prompted
```

> [!TIP]
> Manage API keys: `mgc auth api-key list` | `mgc auth api-key revoke --id=<UUID>`

## GitHub Environments

The workflows use
[GitHub Environments](https://docs.github.com/en/actions/deployment/targeting-different-environments/using-environments-for-deployment)
for approval gates:

| Environment  | Purpose                                     | Approval Required |
| :----------- | :------------------------------------------ | :---------------- |
| `production` | Manual approval for infrastructure changes  | ✅ Yes            |
| `cleanup`    | Auto-approval for scheduled nightly cleanup | ❌ No             |

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

## Magalu Cloud Pricing

Reference pricing for **Balanced Value (High Memory)** VMs with **10GB disk**:

| Type            | vCPU | RAM  | R$/hour | R$/month | Notes                                |
| :-------------- | :--: | :--: | ------: | -------: | :----------------------------------- |
| `BV1-4-10`      |  1   | 4GB  |  0.1000 |    72.99 | Minimum viable                       |
| `BV2-8-10`      |  2   | 8GB  |  0.1644 |   119.99 | Light workloads                      |
| **`BV4-16-10`** |  4   | 16GB |  0.3151 |   229.99 | **Default** (recommended for Gradle) |
| `BV8-32-10`     |  8   | 32GB |  0.6575 |   479.99 | Heavy parallel builds                |

> [!NOTE]
> All Balanced Value VMs have **10GB disk** regardless of tier. This is sufficient for ephemeral CI runners since Gradle
> cache is externalized.

### Cost Estimation

| Usage Pattern                | Hours/Month | Estimated Cost |
| :--------------------------- | :---------: | -------------: |
| On-demand (8h/day, weekdays) |    ~176h    |          ~R$55 |
| Always-on (development)      |    ~720h    |         ~R$227 |
| Burst (CI-triggered)         |    ~40h     |          ~R$13 |

To change machine type, use the `machine_type` input when provisioning:

```bash
gh workflow run runner-provision.yml -f machine_type=BV8-32-10
```

## Terraform State

State is managed remotely via Terraform Cloud:

- Organization: `alberto`
- Workspace: `groovy-lsp-runner`

## Local Development

### Prerequisites

1. **Export Terraform Cloud credentials** (for state access):
   ```bash
   export TF_CLOUD_ORGANIZATION="alberto"
   export TF_WORKSPACE="groovy-lsp-runner"
   export TF_TOKEN_app_terraform_io="<your-terraform-cloud-token>"
   ```

2. **Export cloud provider credentials**:
   ```bash
   export TF_VAR_mgc_api_key="<your-magalu-api-key>"
   export TF_VAR_github_token="<your-github-pat>"
   ```

### Terraform Output Commands

After running `terraform apply`, retrieve sensitive outputs:

#### Get SSH Private Key

```bash
# Export SSH key to file (for connecting to runner)
terraform output -raw generated_ssh_private_key > runner-key.pem

# Set correct permissions
chmod 600 runner-key.pem
```

#### Get Runner IP Addresses

```bash
# JSON format (required for list/tuple outputs)
terraform output -json runner_ipv4s

# Example output: ["201.23.15.34"]

# Extract single IP for scripting
terraform output -json runner_ipv4s | jq -r '.[0]'
```

#### Get Runner Configuration Summary

```bash
# View all runner configuration
terraform output runner_config

# Example output:
# {
#   count        = 1
#   labels       = ["self-hosted", "magalu", "groovy-lsp"]
#   machine_type = "BV4-16-10"
#   name_prefix  = "groovy-lsp-ci"
#   region       = "br-ne1"
#   repo_url     = "https://github.com/albertocavalcante/groovy-lsp"
# }
```

### Connect to Runner via SSH

```bash
# 1. Export SSH key (if not already done)
terraform output -raw generated_ssh_private_key > runner-key.pem
chmod 600 runner-key.pem

# 2. Get runner IP
RUNNER_IP=$(terraform output -json runner_ipv4s | jq -r '.[0]')

# 3. Connect
ssh -i runner-key.pem ubuntu@$RUNNER_IP
```

### Common Errors

#### ❌ `Error: Output "runner_ipv4" not found`

**Cause**: Output name mismatch. The correct output is `runner_ipv4s` (plural).

```bash
# Wrong
terraform output -raw runner_ipv4

# Correct
terraform output -json runner_ipv4s
```

#### ❌ `Error: Unsupported value for raw output`

**Cause**: The `-raw` flag only works for strings, not lists/tuples.

```bash
# Wrong (runner_ipv4s is a list)
terraform output -raw runner_ipv4s

# Correct
terraform output -json runner_ipv4s
```

### Complete Local Workflow Example

```bash
# 1. Navigate to runner directory
cd infra/runner

# 2. Export credentials
export TF_CLOUD_ORGANIZATION="alberto"
export TF_WORKSPACE="groovy-lsp-runner"
export TF_TOKEN_app_terraform_io="<your-tf-cloud-token>"
export TF_VAR_mgc_api_key="<your-magalu-api-key>"
export TF_VAR_github_token="<your-github-pat>"

# 3. Initialize Terraform
terraform init

# 4. Plan infrastructure changes
terraform plan

# 5. Apply changes
terraform apply

# 6. Get runner IP and SSH key
terraform output -json runner_ipv4s | jq -r '.[0]'
terraform output -raw generated_ssh_private_key > runner-key.pem
chmod 600 runner-key.pem

# 7. Connect to runner
ssh -i runner-key.pem ubuntu@$(terraform output -json runner_ipv4s | jq -r '.[0]')

# 8. Cleanup when done
terraform destroy
```
