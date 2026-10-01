"""Copy the local Grafana password to the macOS clipboard without printing it."""
import subprocess
from demo import local_env
if __name__=='__main__':
    value=local_env().get('SENTINEL_GRAFANA_PASSWORD','')
    if len(value)<32:raise SystemExit('Run setup_dev.py first.')
    subprocess.run(['pbcopy'],input=value.encode(),check=True)
    print('Grafana admin password copied to clipboard. Clear the clipboard after signing in.')
