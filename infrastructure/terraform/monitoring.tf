locals {
  monitors = var.enable_monitoring ? {
    prometheus = {
      image = "prom/prometheus:v3.13.3", port = 9090, uid = 65534, memory = "256Mi", path = "/-/ready"
    }

    grafana = {
      image = "grafana/grafana:13.2.3", port = 3000, uid = 472, memory = "768Mi", path = "/api/health"
    }

    } : {
  }

}

resource "kubernetes_config_map_v1" "monitoring" {
  count = var.enable_monitoring ? 1 : 0
  metadata {
    name      = "monitoring"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  data = {
    "prometheus.yml"         = file("${path.module}/../monitoring/prometheus.yml")
    "alerts.yml"             = file("${path.module}/../monitoring/alerts.yml")
    "datasource.yml"         = file("${path.module}/../monitoring/grafana/provisioning/datasources/prometheus.yml")
    "dashboard-provider.yml" = file("${path.module}/../monitoring/grafana/provisioning/dashboards/sentinel.yml")
    "sentinel.json"          = file("${path.module}/../monitoring/grafana/dashboards/sentinel.json")
  }

}

resource "kubernetes_service_v1" "monitoring" {
  for_each = local.monitors
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  spec {
    selector = {
      app                 = each.key
      "sentinel/workload" = "true"
    }

    port {
      port        = each.value.port
      target_port = each.value.port
    }
  }

}

resource "kubernetes_deployment_v1" "monitoring" {
  for_each = local.monitors
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  spec {
    replicas = 1
    selector {
      match_labels = {
        app = each.key
      }
    }

    template {
      metadata {
        labels = {
          app                 = each.key
          "sentinel/workload" = "true"
        }

        annotations = {
          "sentinel/secret-revision" = tostring(var.secret_revision)
          "sentinel/config-hash"     = sha256(jsonencode(kubernetes_config_map_v1.monitoring[0].data))
        }
      }

      spec {
        service_account_name            = kubernetes_service_account_v1.app.metadata[0].name
        automount_service_account_token = false
        security_context {
          run_as_non_root = true
          run_as_user     = each.value.uid
          fs_group        = each.value.uid
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        container {
          name  = each.key
          image = each.value.image
          args  = each.key == "prometheus" ? ["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.time=24h", "--storage.tsdb.retention.size=256MB"] : null
          security_context {
            run_as_non_root            = true
            allow_privilege_escalation = false
            capabilities {
              drop = ["ALL"]
            }
          }

          resources {
            requests = {
              cpu = "100m", memory = each.value.memory
            }

            limits = {
              cpu = "2", memory = each.value.memory
            }
          }

          dynamic "env" {
            for_each = each.key == "grafana" ? {
              GOMEMLIMIT = "256MiB", GF_SECURITY_ADMIN_PASSWORD__FILE = "/run/secrets/grafana-password", GF_AUTH_ANONYMOUS_ENABLED = "false", GF_USERS_ALLOW_SIGN_UP = "false", GF_ANALYTICS_REPORTING_ENABLED = "false", GF_ANALYTICS_CHECK_FOR_UPDATES = "false", GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES = "false"
              } : {
            }

            content {
              name  = env.key
              value = env.value
            }

          }

          port {
            container_port = each.value.port
          }

          readiness_probe {
            http_get {
              path = each.value.path
              port = each.value.port
            }

            timeout_seconds = 3
            period_seconds  = 5
          }

          liveness_probe {
            http_get {
              path = each.value.path
              port = each.value.port
            }

            initial_delay_seconds = 30
            timeout_seconds       = 3
            period_seconds        = 10
          }

          volume_mount {
            name       = "credentials"
            mount_path = "/run/secrets"
            read_only  = true
          }

          volume_mount {
            name       = "data"
            mount_path = each.key == "prometheus" ? "/prometheus" : "/var/lib/grafana"
          }

          dynamic "volume_mount" {
            for_each = each.key == "prometheus" ? {
              "prometheus.yml" = "/etc/prometheus/prometheus.yml", "alerts.yml" = "/etc/prometheus/alerts.yml"
              } : {
              "datasource.yml" = "/etc/grafana/provisioning/datasources/prometheus.yml", "dashboard-provider.yml" = "/etc/grafana/provisioning/dashboards/sentinel.yml", "sentinel.json" = "/var/lib/grafana/dashboards/sentinel.json"
            }

            content {
              name       = "config"
              mount_path = volume_mount.value
              sub_path   = volume_mount.key
              read_only  = true
            }

          }

        }

        volume {
          name = "credentials"
          secret {
            secret_name = kubernetes_secret_v1.app["monitoring"].metadata[0].name
          }
        }

        volume {
          name = "config"
          config_map {
            name = kubernetes_config_map_v1.monitoring[0].metadata[0].name
          }
        }

        volume {
          name = "data"
          empty_dir {
            size_limit = "512Mi"
          }
        }

      }

    }

  }

  depends_on = [kubernetes_deployment_v1.app]
  timeouts {
    create = "10m"
  }

}
