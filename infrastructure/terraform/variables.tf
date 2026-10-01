variable "kubeconfig" {
  type        = string
  description = "Dedicated project kubeconfig; never the ambient current context."
}

variable "credentials" {
  type        = map(string)
  sensitive   = true
  ephemeral   = true
  description = "Write-only Kubernetes secret values supplied in the Terraform process environment."
}

variable "secret_revision" {
  type        = number
  default     = 1
  description = "Increment for credential rotation, then roll affected pods."
  validation {
    condition     = var.secret_revision >= 1
    error_message = "Revision must be positive."
  }

}

variable "image_tag" {
  type    = string
  default = "phase7"
  validation {
    condition     = can(regex("^[a-zA-Z0-9_.-]+$", var.image_tag))
    error_message = "Use a local image tag."
  }

}

variable "gateway_replicas" {
  type    = number
  default = 1
  validation {
    condition     = contains([1, 2], var.gateway_replicas)
    error_message = "Local demonstration supports one or two gateway replicas."
  }

}

variable "enable_monitoring" {
  type    = bool
  default = false
}

variable "console_origin" {
  type    = string
  default = "http://127.0.0.1:13000"
  validation {
    condition     = can(regex("^http://127\\.0\\.0\\.1:[0-9]+$", var.console_origin))
    error_message = "Use loopback HTTP for this local deployment."
  }

}

variable "model_name" {
  type    = string
  default = "gpt-oss-120b"
}
