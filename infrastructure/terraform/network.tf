locals {
  traffic = {
    postgres = {
      port = 5432, sources = ["gateway", "migrate"], targets = {
      }
    }

    redis = {
      port = 6379, sources = ["gateway"], targets = {
      }
    }

    migrate = {
      port = 0, sources = [], targets = {
        postgres = 5432
      }
    }

    gateway = {
      port = 8080, sources = ["console", "prometheus"], targets = {
        postgres = 5432, redis = 6379, inspection = 8000, mcp-adapter = 8100
      }
    }

    inspection = {
      port = 8000, sources = ["gateway"], targets = {
      }
    }

    mcp-adapter = {
      port = 8100, sources = ["gateway"], targets = {
      }
    }

    console = {
      port = 3000, sources = [], targets = {
        gateway = 8080
      }
    }

    prometheus = {
      port = 9090, sources = ["grafana"], targets = {
        gateway = 8080
      }
    }

    grafana = {
      port = 3000, sources = [], targets = {
        prometheus = 9090
      }
    }

  }

}

resource "kubernetes_network_policy_v1" "default_deny" {
  metadata {
    name      = "default-deny"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  spec {
    pod_selector {
    }

    policy_types = ["Ingress", "Egress"]
  }

}

resource "kubernetes_network_policy_v1" "app" {
  for_each = local.traffic
  metadata {
    name      = "allow-${each.key}"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  spec {
    pod_selector {
      match_labels = {
        app = each.key
      }
    }

    policy_types = ["Ingress", "Egress"]
    dynamic "ingress" {
      for_each = length(each.value.sources) > 0 ? [1] : []
      content {
        dynamic "from" {
          for_each = toset(each.value.sources)
          content {
            pod_selector {
              match_labels = {
                app = from.value
              }
            }
          }
        }

        ports {
          protocol = "TCP"
          port     = tostring(each.value.port)
        }

      }

    }

    egress {
      to {
        namespace_selector {
          match_labels = {
            "kubernetes.io/metadata.name" = "kube-system"
          }
        }

        pod_selector {
          match_labels = {
            "k8s-app" = "kube-dns"
          }
        }

      }

      ports {
        protocol = "UDP"
        port     = "53"
      }

      ports {
        protocol = "TCP"
        port     = "53"
      }

    }

    dynamic "egress" {
      for_each = each.value.targets
      content {
        to {
          pod_selector {
            match_labels = {
              app = egress.key
            }
          }
        }

        ports {
          protocol = "TCP"
          port     = tostring(egress.value)
        }

      }

    }

  }

}
