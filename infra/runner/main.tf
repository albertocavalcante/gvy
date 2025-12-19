terraform {
  required_version = ">= 1.5.7"

  backend "remote" {
    hostname     = "app.terraform.io"
    organization = "alberto"

    workspaces {
      name = "groovy-lsp-runner"
    }
  }

  required_providers {
    mgc = {
      source  = "magalucloud/mgc"
      version = "0.41.0"
    }
  }
}

provider "mgc" {
  api_key = var.mgc_api_key
  region  = "br-ne1" // NorthEast 1
}

module "runner" {
  source = "github.com/albertocavalcante/magalu-github-runner?ref=1ddc2d9d81f49c70a0f9aa4ee60acd09d09f9d60"

  runner_count                 = 1
  runner_name_prefix           = "groovy-lsp-ci"
  github_repository_url        = var.github_repo_url
  github_personal_access_token = var.github_token
  machine_type                 = var.machine_type

  # Labels for targeting from CI workflows
  # NOTE: "self-hosted" is auto-added by GitHub and does not need to be specified here.
  runner_labels = ["magalu", "groovy-lsp"]
}
