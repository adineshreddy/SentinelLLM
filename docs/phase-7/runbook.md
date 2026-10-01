# Local deployment and recovery runbook

All commands target the dedicated `kind-sentinellm` context. Do not apply these commands to another cluster. Compose data is independent of Kubernetes PVCs.

## Inspect an unhealthy deployment

```sh
kubectl --kubeconfig runtime/kind/kubeconfig --context kind-sentinellm -n sentinellm get pods,pvc,services
kubectl --kubeconfig runtime/kind/kubeconfig --context kind-sentinellm -n sentinellm get events --sort-by=.lastTimestamp
kubectl --kubeconfig runtime/kind/kubeconfig --context kind-sentinellm -n sentinellm logs deployment/gateway --tail=80
python3 tools/deploy_local.py plan --monitoring
```

Logs and state are private operational material; inspect locally before sharing. Resolve image pull/disk/memory errors first. Applications wait for the owner-credential Flyway Job. Gateway readiness requires PostgreSQL/Redis; inspector/MCP health does not imply a live hosted provider. Fix declarative settings in Terraform and apply through the helper. Do not patch Terraform-owned objects with standalone manifests.

## Replace failed runtime Pods

```sh
python3 tools/check_kubernetes.py --exercise-recovery
```

This records a completed synthetic operation, replaces PostgreSQL, Redis and gateway Pods sequentially, waits for controllers to restore readiness, then checks that the same completed operation survives. PVCs and controllers remain intact. In-flight tool outcomes can remain uncertain; inspect tenant-scoped operation evidence and do not blindly retry side-effecting tools. Console sessions are memory-resident and users must log in again after console replacement.

For a gateway code correction, rebuild images, use a new immutable image tag in Terraform, and create/apply a reviewed plan. The supplied helper intentionally fixes the demo tag to `phase7`; for development using that tag, clean recreation is the repeatable route. Rebuilding a mutable local tag alone does not trigger Deployment replacement.

## Credentials

`secret_revision` controls write-only Secret delivery because Terraform cannot compare old secret values. Increment it when delivering changed credentials. A revision change rolls Terraform-managed workloads through their Pod template annotation. Credential rotation still requires coordinated role/key updates; simultaneous credential changes are not guaranteed to be outage-free. PostgreSQL role passwords are initialized only on an empty data directory; changing an environment variable does not rotate a stored database role. Perform an explicit coordinated database role rotation for retained data, or recreate this disposable synthetic deployment. Do not claim production zero-downtime rotation.

Never print Secrets, `.env`, kubeconfig or state in a public demo. Do not enable Terraform debug logging with credential-bearing configuration. The helper removes Terraform log environment variables.

## Interrupted apply or lost state

Keep the private state and lock files. Inspect current Pods/events and rerun plan/apply using the same explicit context. If another Terraform process is still running, finish or stop it before retrying; never unlock an active operation. Do not import or delete another project's objects.

If a disposable initial deployment has no usable state and no data to retain, delete only its dedicated kind cluster and recreate it. For a populated deployment with lost state, preserve cluster/PVC data and recover state or review targeted imports before making changes. The automated cleanup assumes disposable project data and is not a production recovery procedure.

## Remove and recreate the disposable deployment

**This deletes the project's Kubernetes PVC data.** It is appropriate for this synthetic local demo, not for preserving user data.

```sh
python3 tools/cleanup_kind.py
python3 tools/kind_bootstrap.py
python3 tools/deploy_local.py images
python3 tools/deploy_local.py apply --monitoring
python3 tools/check_kubernetes.py
```

Cleanup destroys application resources through Terraform before kind removes the named cluster. It does not delete Docker Compose volumes or use the default kubecontext. The provider initialization/cache and ignored empty state can be reused. Calico and node images may remain cached in Docker. Port-forwards exit when the cluster is removed; restart them after recreation.
