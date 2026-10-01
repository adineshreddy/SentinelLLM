locals {
  namespace    = "sentinellm"
  secret_names = toset(["postgres", "redis", "migrate", "gateway", "inspection", "mcp-adapter", "console", "monitoring"])
  secret_data = {
    postgres = {
      POSTGRES_PASSWORD = var.credentials.SENTINEL_DB_OWNER_PASSWORD, SENTINEL_DB_APP_PASSWORD = var.credentials.SENTINEL_DB_APP_PASSWORD
    }

    redis = {
      SENTINEL_REDIS_PASSWORD = var.credentials.SENTINEL_REDIS_PASSWORD
    }

    migrate = {
      SENTINEL_DB_OWNER_PASSWORD = var.credentials.SENTINEL_DB_OWNER_PASSWORD
    }

    gateway = {
      for key in ["SENTINEL_APPLICATION_KEYS_JSON", "SENTINEL_DB_APP_PASSWORD", "SENTINEL_INSPECTION_KEY", "SENTINEL_MCP_KEY", "SENTINEL_REDIS_PASSWORD", "SENTINEL_METRICS_KEY"] : key => var.credentials[key]
    }

    inspection = {
      SENTINEL_INSPECTION_KEY = var.credentials.SENTINEL_INSPECTION_KEY
    }

    mcp-adapter = {
      SENTINEL_MCP_KEY = var.credentials.SENTINEL_MCP_KEY
    }

    console = {
      for key in ["SENTINEL_CONSOLE_OPERATOR_PASSWORD", "SENTINEL_CONSOLE_VIEWER_PASSWORD", "SENTINEL_OPERATOR_KEY", "SENTINEL_VIEWER_KEY", "SENTINEL_DEMO_KEY"] : key => var.credentials[key]
    }

    monitoring = {
      metrics-key = var.credentials.SENTINEL_METRICS_KEY, grafana-password = var.credentials.SENTINEL_GRAFANA_PASSWORD
    }

  }

  settings = {
    gateway = {
      SENTINEL_STATE_MODE    = "postgres", SENTINEL_DB_URL = "jdbc:postgresql://postgres:5432/sentinel", SENTINEL_DB_USER = "sentinel_app",
      SENTINEL_REDIS_HOST    = "redis", SENTINEL_BIND_ADDRESS = "0.0.0.0", SENTINEL_PROVIDER = "mock", SENTINEL_HOSTED_ENABLED = "false",
      SENTINEL_TOOL_MODE     = "mcp", SENTINEL_INSPECTION_URL = "http://inspection:8000/internal/v1/inspect", SENTINEL_MCP_BRIDGE_URL = "http://mcp-adapter:8100/internal/v1/tools/execute",
      SENTINEL_MOCK_RESPONSE = "SentinelLLM mock approved request.",
      SENTINEL_HTTP_CAPACITY = "32", SENTINEL_AUDIT_PATH = "/app/runtime/audit.jsonl"
    }

    inspection = {
      SENTINEL_CLASSIFIER_MODE = "off", PYTHONDONTWRITEBYTECODE = "1"
    }

    mcp-adapter = {
      SENTINEL_DEMO_TOOL_TEXT = "Reset your demo password from account settings."
    }

    console = {
      SENTINEL_CONSOLE_ORIGIN = var.console_origin, SENTINEL_CONSOLE_GATEWAY_URL = "http://gateway:8080", SENTINEL_PROVIDER = "mock", NAVIGATOR_LLM_MODEL = var.model_name
    }

  }

  apps = {
    gateway = {
      port = 8080, memory = "640Mi", cpu = "200m", uid = 10001, readiness = "/health/ready", config = local.settings.gateway
    }

    inspection = {
      port = 8000, memory = "384Mi", cpu = "100m", uid = 10001, readiness = "/health/live", config = local.settings.inspection
    }

    mcp-adapter = {
      port = 8100, memory = "256Mi", cpu = "100m", uid = 10001, readiness = "/health/live", config = local.settings.mcp-adapter
    }

    console = {
      port = 3000, memory = "192Mi", cpu = "50m", uid = 1000, readiness = "/health/live", config = local.settings.console
    }

  }

}

resource "kubernetes_namespace_v1" "app" {
  metadata {
    name = local.namespace
    labels = {
      "app.kubernetes.io/part-of" = "sentinellm", "pod-security.kubernetes.io/enforce" = "restricted", "pod-security.kubernetes.io/enforce-version" = "v1.36", "pod-security.kubernetes.io/audit" = "restricted", "pod-security.kubernetes.io/warn" = "restricted"
    }

  }

}

resource "kubernetes_service_account_v1" "app" {
  metadata {
    name      = "sentinel"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  automount_service_account_token = false
}

resource "kubernetes_secret_v1" "app" {
  for_each = local.secret_names
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  data_wo          = local.secret_data[each.key]
  data_wo_revision = var.secret_revision
}

resource "kubernetes_config_map_v1" "app" {
  for_each = local.apps
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  data = each.value.config
}

resource "kubernetes_config_map_v1" "postgres_init" {
  metadata {
    name      = "postgres-init"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  data = {
    "01-roles.sql" = file("${path.module}/../postgres/01-roles.sql")
  }

}

resource "kubernetes_service_v1" "app" {
  for_each = merge({
    for name, app in local.apps : name => app.port
    }, {
    postgres = 5432, redis = 6379
    }
  )
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  spec {
    selector = {
      app                 = each.key
      "sentinel/workload" = "true"
    }

    type = "ClusterIP"
    port {
      port        = each.value
      target_port = each.value
    }

  }

}

resource "kubernetes_deployment_v1" "app" {
  for_each = local.apps
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  spec {
    replicas = each.key == "gateway" ? var.gateway_replicas : 1
    selector {
      match_labels = {
        app = each.key
      }
    }

    strategy {
      type = "RollingUpdate"
      rolling_update {
        max_surge       = "1"
        max_unavailable = "0"
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
          "sentinel/config-hash"     = sha256(jsonencode(each.value.config))
        }
      }

      spec {
        service_account_name             = kubernetes_service_account_v1.app.metadata[0].name
        automount_service_account_token  = false
        termination_grace_period_seconds = each.key == "gateway" ? 100 : 30
        security_context {
          run_as_non_root = true
          run_as_user     = each.value.uid
          fs_group        = each.value.uid
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        container {
          name              = each.key
          image             = "sentinellm-${each.key}:${var.image_tag}"
          image_pull_policy = "Never"
          port {
            container_port = each.value.port
          }

          env_from {
            config_map_ref {
              name = kubernetes_config_map_v1.app[each.key].metadata[0].name
            }
          }

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.app[each.key].metadata[0].name
            }
          }

          security_context {
            run_as_non_root            = true
            allow_privilege_escalation = false
            read_only_root_filesystem  = true
            capabilities {
              drop = ["ALL"]
            }
          }

          resources {
            requests = {
              cpu = each.value.cpu, memory = each.value.memory
            }

            limits = {
              cpu = "2", memory = each.value.memory
            }
          }

          volume_mount {
            name       = "tmp"
            mount_path = "/tmp"
          }

          dynamic "volume_mount" {
            for_each = each.key == "gateway" ? [1] : []
            content {
              name       = "runtime"
              mount_path = "/app/runtime"
            }
          }

          startup_probe {
            http_get {
              path = "/health/live"
              port = each.value.port
              dynamic "http_header" {
                for_each = each.key == "console" ? [1] : []
                content {
                  name  = "Host"
                  value = trimsuffix(trimprefix(var.console_origin, "http://"), "/")
                }
              }
            }

            period_seconds    = 5
            failure_threshold = 60
          }

          readiness_probe {
            http_get {
              path = each.value.readiness
              port = each.value.port
              dynamic "http_header" {
                for_each = each.key == "console" ? [1] : []
                content {
                  name  = "Host"
                  value = trimsuffix(trimprefix(var.console_origin, "http://"), "/")
                }
              }
            }

            period_seconds  = 5
            timeout_seconds = 3
          }

          liveness_probe {
            http_get {
              path = "/health/live"
              port = each.value.port
              dynamic "http_header" {
                for_each = each.key == "console" ? [1] : []
                content {
                  name  = "Host"
                  value = trimsuffix(trimprefix(var.console_origin, "http://"), "/")
                }
              }
            }

            period_seconds  = 10
            timeout_seconds = 3
          }

        }

        volume {
          name = "tmp"
          empty_dir {
            size_limit = "64Mi"
          }
        }

        dynamic "volume" {
          for_each = each.key == "gateway" ? [1] : []
          content {
            name = "runtime"
            empty_dir {
              size_limit = "16Mi"
            }
          }
        }

      }

    }

  }

  depends_on = [kubernetes_job_v1.migrate, kubernetes_network_policy_v1.app, kubernetes_stateful_set_v1.database]
  timeouts {
    create = "10m"
    update = "10m"
    delete = "3m"
  }

}
