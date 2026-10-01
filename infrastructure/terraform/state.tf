locals {
  databases = {
    postgres = {
      image = "postgres:17-alpine", port = 5432, uid = 70, gid = 70, memory = "256Mi", size = "1Gi", mount = "/var/lib/postgresql/data", env = {
        POSTGRES_USER = "sentinel_owner", POSTGRES_DB = "sentinel", PGDATA = "/var/lib/postgresql/data/pgdata"
      }, probe        = ["pg_isready", "-U", "sentinel_owner", "-d", "sentinel"]
    }

    redis = {
      image = "redis:7.2-alpine", port = 6379, uid = 999, gid = 1000, memory = "128Mi", size = "256Mi", mount = "/data", env = {
      }, probe = ["sh", "-c", "REDISCLI_AUTH=\"$SENTINEL_REDIS_PASSWORD\" redis-cli ping | grep -q PONG"]
    }

  }

}

resource "kubernetes_persistent_volume_claim_v1" "database" {
  for_each = local.databases
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  wait_until_bound = false
  spec {
    access_modes       = ["ReadWriteOnce"]
    storage_class_name = "standard"
    resources {
      requests = {
        storage = each.value.size
      }
    }
  }

}

resource "kubernetes_stateful_set_v1" "database" {
  for_each = local.databases
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  spec {
    service_name = each.key
    replicas     = 1
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
        }
      }

      spec {
        automount_service_account_token = false
        service_account_name            = kubernetes_service_account_v1.app.metadata[0].name
        security_context {
          run_as_non_root = true
          run_as_user     = each.value.uid
          run_as_group    = each.value.gid
          fs_group        = each.value.gid
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        container {
          name              = each.key
          image             = each.value.image
          image_pull_policy = "IfNotPresent"
          command           = each.key == "redis" ? ["sh", "-c", "exec redis-server --requirepass \"$SENTINEL_REDIS_PASSWORD\" --appendonly yes --appendfsync always --maxmemory 64mb --maxmemory-policy noeviction"] : null
          env_from {
            secret_ref {
              name = kubernetes_secret_v1.app[each.key].metadata[0].name
            }
          }

          dynamic "env" {
            for_each = each.value.env
            content {
              name  = env.key
              value = env.value
            }
          }

          port {
            container_port = each.value.port
          }

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
              cpu = "1", memory = each.value.memory
            }
          }

          readiness_probe {
            exec {
              command = each.value.probe
            }

            period_seconds  = 5
            timeout_seconds = 3
          }

          liveness_probe {
            exec {
              command = each.value.probe
            }

            initial_delay_seconds = 30
            period_seconds        = 10
            timeout_seconds       = 3
          }

          volume_mount {
            name       = "data"
            mount_path = each.value.mount
          }

          dynamic "volume_mount" {
            for_each = each.key == "postgres" ? [1] : []
            content {
              name       = "init"
              mount_path = "/docker-entrypoint-initdb.d"
              read_only  = true
            }
          }

        }

        volume {
          name = "data"
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim_v1.database[each.key].metadata[0].name
          }
        }

        dynamic "volume" {
          for_each = each.key == "postgres" ? [1] : []
          content {
            name = "init"
            config_map {
              name = kubernetes_config_map_v1.postgres_init.metadata[0].name
            }
          }
        }

      }

    }

  }

  depends_on = [kubernetes_network_policy_v1.app]
  timeouts {
    create = "10m"
    update = "10m"
  }

}

resource "kubernetes_job_v1" "migrate" {
  metadata {
    name      = "schema-${var.image_tag}"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  wait_for_completion = true
  spec {
    backoff_limit = 2
    template {
      metadata {
        labels = {
          app = "migrate"
        }
      }

      spec {
        service_account_name            = kubernetes_service_account_v1.app.metadata[0].name
        automount_service_account_token = false
        restart_policy                  = "Never"
        security_context {
          run_as_non_root = true
          run_as_user     = 10001
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }

        container {
          name              = "migrate"
          image             = "sentinellm-gateway:${var.image_tag}"
          image_pull_policy = "Never"
          args              = ["--migrate"]
          env {
            name  = "SENTINEL_DB_URL"
            value = "jdbc:postgresql://postgres:5432/sentinel"
          }

          env {
            name  = "SENTINEL_DB_MIGRATION_USER"
            value = "sentinel_owner"
          }

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.app["migrate"].metadata[0].name
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
              cpu = "100m", memory = "256Mi"
            }

            limits = {
              cpu = "1", memory = "256Mi"
            }
          }

          volume_mount {
            name       = "tmp"
            mount_path = "/tmp"
          }

        }

        volume {
          name = "tmp"
          empty_dir {
            size_limit = "64Mi"
          }
        }

      }

    }

  }

  depends_on = [kubernetes_stateful_set_v1.database]
  timeouts {
    create = "5m"
  }

}
