# Main Infrastructure Configuration
# Magalu Cloud Self-Hosted GitHub Actions Runner

# TODO: Pin to a fixed commit hash once the magalu-github-runner module stabilizes.
# Currently tracking the main branch for active development.
#
# To update the module ref to the latest commit from main:
# NEW_REF=$(gh api /repos/albertocavalcante/magalu-github-runner/commits/main --jq .sha) && \
# sed -i '' "s/ref=[a-f0-9]*/ref=${NEW_REF}/" infra/runner/main.tf

module "runner" {
  source = "github.com/albertocavalcante/magalu-github-runner?ref=main"

  runner_count                 = var.runner_count
  runner_name_prefix           = var.runner_name_prefix
  github_repository_url        = var.github_repo_url
  github_personal_access_token = var.github_token
  machine_type                 = var.machine_type
  image                        = local.resolved_image

  # Labels for targeting from CI workflows
  # NOTE: "self-hosted" is auto-added by GitHub and does not need to be specified here.
  runner_labels = var.runner_labels
}

# Validate runner_image against the image_map keys (DRY - single source of truth in locals.tf)
check "runner_image_valid" {
  assert {
    condition     = can(local.image_map[var.runner_image])
    error_message = "Invalid runner_image \"${var.runner_image}\". Must be one of: ${join(", ", keys(local.image_map))}."
  }
}
