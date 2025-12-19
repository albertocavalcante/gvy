output "runner_config" {
  description = "Runner configuration summary"
  value = {
    count        = var.runner_count
    name_prefix  = var.runner_name_prefix
    labels       = concat(["self-hosted"], var.runner_labels)
    machine_type = var.machine_type
    region       = var.mgc_region
    repo_url     = var.github_repo_url
  }
}

output "generated_ssh_private_key" {
  value     = module.runner.generated_ssh_private_key
  sensitive = true
}

output "runner_ipv4s" {
  description = "Public IP addresses of the runners"
  value       = module.runner.runner_public_ips
}
