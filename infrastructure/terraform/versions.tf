terraform {
  required_version = ">= 1.11, < 2.0"
  required_providers {
    kubernetes = {
      source = "hashicorp/kubernetes", version = "3.2.1"
    }

  }

}

provider "kubernetes" {
  config_path    = var.kubeconfig
  config_context = "kind-sentinellm"
}
