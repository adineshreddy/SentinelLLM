output "namespace" {
  value = kubernetes_namespace_v1.app.metadata[0].name
}

output "gateway_replicas" {
  value = var.gateway_replicas
}

output "console_url" {
  value = var.console_origin
}

output "monitoring_enabled" {
  value = var.enable_monitoring
}
