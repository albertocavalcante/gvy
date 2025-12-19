# Terraform and Provider Configuration
# Magalu Cloud Self-Hosted Runner Infrastructure

terraform {
  cloud {
    # Organization and workspace configured via environment variables:
    # TF_CLOUD_ORGANIZATION = "alberto"
    # TF_WORKSPACE = "groovy-lsp-runner"
    # This allows flexible workspace selection in different environments
  }

  required_providers {
    mgc = {
      source  = "magalucloud/mgc"
      version = "~> 0.41.0"
    }
  }

  required_version = ">= 1.11.0"
}

# Configure the Magalu Cloud provider
provider "mgc" {
  api_key = var.mgc_api_key
  region  = var.mgc_region
}
