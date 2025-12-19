# Main Infrastructure Configuration
# Magalu Cloud Self-Hosted GitHub Actions Runner

# To update the module ref to the latest commit from main:
# NEW_REF=$(gh api /repos/albertocavalcante/magalu-github-runner/commits/main --jq .sha) && \
# sed -i '' "s/ref=47708af56676a76c24823a4644f91db3f91e9421[a-f0-9]*/ref=${NEW_REF}/" infra/runner/main.tf

module "runner" {
  source = "github.com/albertocavalcante/magalu-github-runner?ref=47708af56676a76c24823a4644f91db3f91e9421"

  runner_count                 = var.runner_count
  runner_name_prefix           = var.runner_name_prefix
  github_repository_url        = var.github_repo_url
  github_personal_access_token = var.github_token
  machine_type                 = var.machine_type

  # Labels for targeting from CI workflows
  # NOTE: "self-hosted" is auto-added by GitHub and does not need to be specified here.
  runner_labels = var.runner_labels
}
