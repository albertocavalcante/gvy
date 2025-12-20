# =============================================================================
# Required Variables (no defaults - must be provided)
# =============================================================================

variable "github_token" {
  description = "GitHub PAT for runner registration (requires 'repo' scope)"
  type        = string
  sensitive   = true
}

variable "mgc_api_key" {
  description = "Magalu Cloud API Key (requires virtual-machine.read/write, network.read/write scopes)"
  type        = string
  sensitive   = true
}

# =============================================================================
# Optional Variables (have sensible defaults)
# =============================================================================

variable "mgc_region" {
  description = "Magalu Cloud region (br-ne1 = Northeast, br-se1 = Southeast)"
  type        = string
  default     = "br-ne1"

  validation {
    condition     = contains(["br-ne1", "br-se1"], var.mgc_region)
    error_message = "Region must be: br-ne1 (Northeast) or br-se1 (Southeast). Note: br-mgl1 is deprecated."
  }
}

variable "github_repo_url" {
  description = "GitHub repository URL to register the runner with"
  type        = string
  default     = "https://github.com/albertocavalcante/groovy-lsp"
}

variable "machine_type" {
  description = "Magalu Cloud VM machine type (e.g., BV4-16-10 = 4vCPU, 16GB RAM)"
  type        = string
  default     = "BV4-16-10"
}

variable "runner_count" {
  description = "Number of runner instances to provision"
  type        = number
  default     = 1

  validation {
    condition     = var.runner_count >= 1 && var.runner_count <= 5
    error_message = "Runner count must be between 1 and 5"
  }
}

variable "runner_name_prefix" {
  description = "Prefix for runner instance names"
  type        = string
  default     = "groovy-lsp-ci"
}

variable "runner_labels" {
  description = "Labels for targeting runners from CI workflows"
  type        = list(string)
  default     = ["magalu", "groovy-lsp"]
}

variable "runner_image" {
  description = "Runner OS image (friendly name). See locals.tf for valid options."
  type        = string
  default     = "ubuntu-22"
}
