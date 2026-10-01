"""Generate container-readable credentials inside private, ignored host directories."""
from pathlib import Path
from setup_dev import configure, ROOT

def main():
    configure(ROOT/'.env','gpt-oss-120b')
    values=dict(line.split('=',1) for line in (ROOT/'.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
    root=ROOT/'secrets';root.mkdir(exist_ok=True);root.chmod(0o700)
    directory=root/'monitoring';directory.mkdir(exist_ok=True);directory.chmod(0o700)
    for name,key in [('metrics-key','SENTINEL_METRICS_KEY'),('grafana-password','SENTINEL_GRAFANA_PASSWORD')]:
        value=values[key]
        if len(value)<32 or not all(c.isalnum() or c in '_-' for c in value):raise SystemExit('Invalid monitoring credential; no files written for this credential.')
        path=directory/name
        path.write_text(value);path.chmod(0o644)
    print('Monitoring credential files prepared in private ignored directories. No secrets displayed.')
if __name__=='__main__':main()
