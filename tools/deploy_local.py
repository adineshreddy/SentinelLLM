"""Operate Terraform against only the project kind context; keep secrets out of arguments and files."""
import argparse
import json
import os
import platform
from pathlib import Path
import subprocess
from setup_dev import configure, ROOT
from kind_bootstrap import KUBECONFIG, CLUSTER

MODULE=ROOT/'infrastructure/terraform'
SECRETS=['SENTINEL_DB_OWNER_PASSWORD','SENTINEL_DB_APP_PASSWORD','SENTINEL_REDIS_PASSWORD','SENTINEL_APPLICATION_KEYS_JSON','SENTINEL_INSPECTION_KEY','SENTINEL_MCP_KEY','SENTINEL_METRICS_KEY','SENTINEL_GRAFANA_PASSWORD','SENTINEL_CONSOLE_OPERATOR_PASSWORD','SENTINEL_CONSOLE_VIEWER_PASSWORD','SENTINEL_OPERATOR_KEY','SENTINEL_VIEWER_KEY','SENTINEL_DEMO_KEY']

def environment(replicas=1,monitoring=False,revision=1):
    values=dict(line.split('=',1) for line in (ROOT/'.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
    env=dict(os.environ)
    for name in ('TF_LOG','TF_LOG_CORE','TF_LOG_PROVIDER','TF_LOG_PATH'):env.pop(name,None)
    env.update(TF_VAR_credentials=json.dumps({key:values[key] for key in SECRETS}),TF_VAR_kubeconfig=str(KUBECONFIG),TF_VAR_gateway_replicas=str(replicas),TF_VAR_enable_monitoring=str(monitoring).lower(),TF_VAR_secret_revision=str(revision),TF_VAR_model_name=values['NAVIGATOR_LLM_MODEL'],TF_IN_AUTOMATION='1')
    return env

def verify_state(required=True):
    values=dict(line.split('=',1) for line in (ROOT/'.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
    paths=list(MODULE.glob('*.tfstate*'))
    if required and not any(path.stat().st_size for path in paths):raise SystemExit('No completed Terraform state available to inspect.')
    # Write-only secret values must not be present even in private local state/backups.
    for path in paths:
        path.chmod(0o600)
        text=path.read_text()
        if any(value and len(value)>=32 and (value in text or json.dumps(value)[1:-1] in text) for key,value in values.items() if key in SECRETS):raise SystemExit('Secret material appeared in Terraform state; do not commit it.')
    print('Terraform state checked: no configured application secret values present.')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['images','init','validate','plan','apply','destroy','check-state']);parser.add_argument('--replicas',type=int,choices=[1,2],default=1);parser.add_argument('--monitoring',action='store_true');parser.add_argument('--secret-revision',type=int,default=1);args=parser.parse_args()
    if args.action=='images':
        for service in ['gateway','inspection','mcp-adapter','console']:
            subprocess.run(['docker','build','--provenance=false','--tag',f'sentinellm-{service}:phase7','--file',str(ROOT/f'services/{service}/Dockerfile'),str(ROOT)],check=True)
        images=[f'sentinellm-{name}:phase7' for name in ['gateway','inspection','mcp-adapter','console']]
        architecture='arm64' if platform.machine() in ('arm64','aarch64') else 'amd64'
        archive=ROOT/'runtime/kind/images.tar'
        # Docker's containerd store may retain absent multi-architecture manifests. Export only this host's platform.
        subprocess.run(['docker','image','save','--platform','linux/'+architecture,'--output',str(archive),*images],check=True)
        try:subprocess.run([str(ROOT/'runtime/bin/kind'),'load','image-archive','--name',CLUSTER,str(archive)],check=True)
        finally:archive.unlink(missing_ok=True)
        return
    if args.action=='check-state':verify_state();return
    if not KUBECONFIG.exists():raise SystemExit('Run kind_bootstrap.py first.')
    subprocess.run(['kubectl','--kubeconfig',str(KUBECONFIG),'--context','kind-'+CLUSTER,'get','node','sentinellm-control-plane','-o','name'],check=True,stdout=subprocess.DEVNULL)
    configure(ROOT/'.env','gpt-oss-120b')
    env=environment(args.replicas,args.monitoring,args.secret_revision)
    command=[str(ROOT/'runtime/bin/terraform'),'-chdir='+str(MODULE),args.action,'-no-color']
    if args.action in ('apply','destroy'):command.append('-auto-approve')
    if args.action in ('plan','apply','destroy'):command.append('-input=false')
    subprocess.run(command,check=True,env=env)
    if args.action in ('plan','apply','destroy'):verify_state(required=args.action!='plan')
if __name__=='__main__':main()
