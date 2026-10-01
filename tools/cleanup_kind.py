"""Remove only the project-owned disposable cluster; never Compose volumes or another kubecontext."""
import subprocess
from pathlib import Path
from kind_bootstrap import CLUSTER,KUBECONFIG
from deploy_local import ROOT,MODULE,environment,verify_state

def main():
    kind=ROOT/'runtime/bin/kind'
    clusters=subprocess.run([str(kind),'get','clusters'],capture_output=True,text=True,check=True).stdout.splitlines()
    if CLUSTER not in clusters:print('Dedicated cluster already absent.');return
    if not KUBECONFIG.exists():raise SystemExit('Refusing cleanup without project kubeconfig.')
    # Destroy application objects/PVCs via their owner before kind removes cluster infrastructure.
    state=MODULE/'terraform.tfstate'
    if state.exists() and state.stat().st_size:
        subprocess.run([str(ROOT/'runtime/bin/terraform'),'-chdir='+str(MODULE),'destroy','-auto-approve','-input=false','-no-color'],env=environment(monitoring=True),check=True)
        verify_state()
    subprocess.run([str(kind),'delete','cluster','--name',CLUSTER,'--kubeconfig',str(KUBECONFIG)],check=True)
    print('Project kind cluster removed; Compose and other contexts unchanged. Local Kubernetes PVC data has been deleted.')
if __name__=='__main__':main()
