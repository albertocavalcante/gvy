variable "github_token" {
  description = "PAT for registering the runner"
  type        = string
  sensitive   = true
}

variable "mgc_api_key" {
  description = "Magalu Cloud API Key"
  type        = string
  sensitive   = true
}

variable "github_repo_url" {
  description = "URL of the GitHub repository to register the runner with"
  type        = string
  default     = "https://github.com/albertocavalcante/groovy-lsp"
}

variable "machine_type" {
  description = "Magalu Cloud machine type"
  type        = string
  default     = "BV1-1-40"
}
