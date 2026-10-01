"""Generate local credentials and add missing phase credentials without revealing them."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import tempfile

ROOT=Path(__file__).resolve().parents[1]


def configure(path, model):
    existing=path.read_text() if path.exists() else ""
    values=dict(line.split("=",1) for line in existing.splitlines() if line and not line.startswith("#") and "=" in line)
    changed=False
    def ensure(name,value):
        nonlocal changed
        if name not in values:
            values[name]=value;changed=True
        return values[name]
    ensure("SENTINEL_PROVIDER","mock")
    ensure("SENTINEL_CLASSIFIER_MODE","off")
    ensure("SENTINEL_HOSTED_ENABLED","false")
    ensure("NAVIGATOR_BASE_URL","https://api.ai.it.ufl.edu/v1")
    ensure("NAVIGATOR_LLM_MODEL",model)
    ensure("NAVIGATOR_API_KEY","")
    ensure("SENTINEL_PORT","8080")
    ensure("SENTINEL_CONSOLE_PORT","3000")
    for name in ("SENTINEL_CONSOLE_OPERATOR_PASSWORD","SENTINEL_CONSOLE_VIEWER_PASSWORD"):
        if not values.get(name):
            values[name]=secrets.token_urlsafe(48);changed=True
    for name in ("SENTINEL_INSPECTION_KEY","SENTINEL_MCP_KEY","SENTINEL_DB_OWNER_PASSWORD","SENTINEL_DB_APP_PASSWORD","SENTINEL_REDIS_PASSWORD","SENTINEL_METRICS_KEY","SENTINEL_GRAFANA_PASSWORD"):
        if not values.get(name):
            values[name]=secrets.token_urlsafe(48);changed=True
    identities=json.loads(values.get("SENTINEL_APPLICATION_KEYS_JSON") or "[]")
    tenant=identities[0]["tenant_id"] if identities else "demo"
    models=identities[0]["models"] if identities else [values["NAVIGATOR_LLM_MODEL"]]
    other="other-demo" if tenant!="other-demo" else "isolated-demo"
    for name,owner,app,roles in [
        ("SENTINEL_DEMO_KEY",tenant,"support-demo",["support_agent"]),
        ("SENTINEL_OPERATOR_KEY",tenant,"management-operator",["operator"]),
        ("SENTINEL_VIEWER_KEY",tenant,"management-viewer",["viewer"]),
        ("SENTINEL_OTHER_DEMO_KEY",other,"support-demo",["support_agent"]),
        ("SENTINEL_OTHER_OPERATOR_KEY",other,"management-operator",["operator"]),
        ("SENTINEL_BENCH_KEY","benchmark","load-client",["support_agent"]),
        ("SENTINEL_BENCH_OPERATOR_KEY","benchmark","load-operator",["operator"]),
    ]:
        if not values.get(name):
            values[name]=secrets.token_urlsafe(48);changed=True
        key=values[name]
        digest=hashlib.sha256(key.encode()).hexdigest()
        if not any(i["sha256"]==digest for i in identities):
            identities.append({"sha256":digest,"tenant_id":owner,"application_id":app,"roles":roles,"models":models});changed=True
    encoded=json.dumps(identities,separators=(",",":"))
    if values.get("SENTINEL_APPLICATION_KEYS_JSON")!=encoded:values["SENTINEL_APPLICATION_KEYS_JSON"]=encoded;changed=True
    if changed:
        lines=[];seen=set()
        for line in existing.splitlines():
            if line and not line.startswith("#") and "=" in line:
                name=line.split("=",1)[0]
                if name in seen:raise ValueError("Duplicate environment variable")
                seen.add(name);lines.append(name+"="+values[name])
            else:lines.append(line)
        lines += [name+"="+value for name,value in values.items() if name not in seen]
        fd,temp=tempfile.mkstemp(prefix=".sentinel-env-",dir=path.parent)
        try:
            os.fchmod(fd,0o600)
            with os.fdopen(fd,"w") as handle:handle.write("\n".join(lines)+"\n")
            os.replace(temp,path)
        finally:
            if os.path.exists(temp):os.unlink(temp)
    path.chmod(0o600)
    return changed


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--model",default="gpt-oss-120b");args=parser.parse_args()
    if not args.model or len(args.model)>128 or any(c in args.model for c in "\r\n$'"):parser.error("Invalid model identifier")
    configure(ROOT/".env",args.model)
    print("Private local credentials ready. Existing values retained; missing database, Redis, and management credentials added. No credentials displayed.")


if __name__=="__main__":main()
