# Local values for the runner infrastructure
# Maps user-friendly image names to actual Magalu Cloud image identifiers

locals {
  # Image name mapping: friendly name -> Magalu Cloud image name
  # Available images verified via: mgc virtual-machine images list
  image_map = {
    "ubuntu-22" = "cloud-ubuntu-22.04 LTS"
    "ubuntu-24" = "cloud-ubuntu-24.04 LTS"
    "debian-12" = "cloud-debian-12 LTS"
    "debian-13" = "cloud-debian-13 LTS"
    "rocky-9"   = "cloud-rocky-09"
    "oracle-8"  = "cloud-oraclelinux-8"
    "oracle-9"  = "cloud-oraclelinux-9"
  }

  # Resolved image name for the module
  resolved_image = local.image_map[var.runner_image]
}
