# Main Infrastructure Configuration
# Magalu Cloud Self-Hosted GitHub Actions Runner

module "runner" {
  source = "github.com/albertocavalcante/magalu-github-runner?ref=1ddc2d9d81f49c70a0f9aa4ee60acd09d09f9d60"

  runner_count                 = var.runner_count
  runner_name_prefix           = var.runner_name_prefix
  github_repository_url        = var.github_repo_url
  github_personal_access_token = var.github_token
  machine_type                 = var.machine_type

  # Labels for targeting from CI workflows
  # NOTE: "self-hosted" is auto-added by GitHub and does not need to be specified here.
  runner_labels = var.runner_labels
}
