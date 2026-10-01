# ADR 0005 — Dedicated local cluster and explicit resource ownership

Status: Accepted, Phase 7.

Use a named kind cluster with its own private kubeconfig and Calico enforcing standard Kubernetes NetworkPolicy. kind owns the cluster, bootstrap owns Calico, and Terraform owns application resources. Verification creates separate ephemeral probes; Pod recovery relies on existing controllers. This prevents competing declarative owners and accidental use of another current kubecontext.

Terraform Kubernetes provider 3.2.1 write-only Secret fields consume ephemeral credential variables. No generated credential values are retained in plans/state; actual state/backups are checked. State and kubeconfig remain private because they contain operational metadata and cluster access. Local Kubernetes Secrets are not encrypted at rest by this design.

Keep all Services private and expose the console only through loopback port-forward. Mock generation and rule-only detection keep local/CI validation independent of provider keys, external inference budgets and model availability. Deny arbitrary Pod egress, with DNS and required internal paths explicitly allowed.

One node, local persistent volumes and optional ephemeral monitoring keep the project free and reproducible on a development machine. They do not provide multi-node availability, durable cloud backups or production deployment evidence. Standard public GitHub Actions runs build and verify the stack without publishing images or creating cloud resources.
