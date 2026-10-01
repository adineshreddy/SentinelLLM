"""Create only the dedicated local cluster and its policy-capable CNI; preserve other contexts."""
import hashlib
from pathlib import Path
import subprocess
import time
from install_deploy_tools import ROOT

CLUSTER='sentinellm'
NODE='kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed'
CALICO='3.32.2'
DIGEST='4e372754e4e1c3a61cf7c0ceb9bc9fff2f78aa6575dcadcb5ea4e716bb00dffa'
KUBECONFIG=ROOT/'runtime/kind/kubeconfig'

def kubectl(*args,**kwargs):
    return subprocess.run(['kubectl','--kubeconfig',str(KUBECONFIG),'--context','kind-'+CLUSTER,*args],check=True,**kwargs)

def main():
    folder=KUBECONFIG.parent;folder.mkdir(parents=True,exist_ok=True);folder.chmod(0o700)
    kind=ROOT/'runtime/bin/kind'
    if not kind.exists():raise SystemExit('Run tools/install_deploy_tools.py first.')
    clusters=subprocess.run([str(kind),'get','clusters'],capture_output=True,text=True,check=True).stdout.splitlines()
    if CLUSTER not in clusters:
        subprocess.run([str(kind),'create','cluster','--name',CLUSTER,'--image',NODE,'--config',str(ROOT/'infrastructure/kind/cluster.yaml'),'--kubeconfig',str(KUBECONFIG)],check=True)
    elif not KUBECONFIG.exists():raise SystemExit('Existing named cluster has no project kubeconfig. Recover it explicitly; do not silently adopt another cluster.')
    KUBECONFIG.chmod(0o600)
    operator=folder/'tigera-operator.yaml'
    subprocess.run(['curl','-fsSL','--max-time','120',f'https://raw.githubusercontent.com/projectcalico/calico/v{CALICO}/manifests/tigera-operator.yaml','-o',str(operator)],check=True)
    if hashlib.sha256(operator.read_bytes()).hexdigest()!=DIGEST:raise SystemExit('Calico operator manifest digest mismatch')
    kubectl('apply','--server-side','--field-manager=sentinel-bootstrap','-f',str(operator))
    deadline=time.monotonic()+120
    while True:
        result=subprocess.run(['kubectl','--kubeconfig',str(KUBECONFIG),'--context','kind-'+CLUSTER,'get','crd/installations.operator.tigera.io'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if result.returncode==0:break
        if time.monotonic()>deadline:raise SystemExit('Calico CRDs did not become available')
        time.sleep(2)
    kubectl('wait','--for=condition=Established','crd/installations.operator.tigera.io','--timeout=120s')
    kubectl('apply','--server-side','--field-manager=sentinel-bootstrap','-f',str(ROOT/'infrastructure/kind/calico-installation.yaml'))
    kubectl('wait','--for=condition=Ready','nodes','--all','--timeout=300s')
    kubectl('rollout','status','daemonset/calico-node','-n','calico-system','--timeout=300s')
    print('Dedicated kind cluster ready with Calico. Application namespace/resources are owned by Terraform.')
if __name__=='__main__':main()
