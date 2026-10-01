"""Mock-only management, policy revision, and durable operation trace demonstration."""
import copy
import json
import urllib.error
import urllib.request
from demo import local_env


def main():
    env=local_env()
    if env.get("SENTINEL_PROVIDER","mock")!="mock":raise SystemExit("This demonstration requires mock mode.")
    base="http://127.0.0.1:"+env.get("SENTINEL_PORT","8080")
    def call(path,payload=None,key="SENTINEL_OPERATOR_KEY",method=None):
        request=urllib.request.Request(base+path,data=None if payload is None else json.dumps(payload).encode(),headers={"Authorization":"Bearer "+env[key],"Content-Type":"application/json"},method=method)
        try:
            with urllib.request.urlopen(request,timeout=10) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as response:return response.code,json.load(response)
    def change(config):
        result=copy.deepcopy(config);result["expected_revision"]=result.pop("revision");result["policy"]["version"]=result["expected_revision"]+1;result["registry"]["version"]=result["expected_revision"]+1;return result
    status,original=call("/api/v1/management/config")
    assert status==200
    assert call("/api/v1/management/config",key="SENTINEL_DEMO_KEY")[0]==403
    assert call("/api/v1/management/config",change(original),key="SENTINEL_VIEWER_KEY",method="PUT")[0]==403
    print("PASS: application cannot manage policies; viewer cannot update them")
    request={"model":env["NAVIGATOR_LLM_MODEL"],"messages":[{"role":"user","content":"Contact alice@example.test"}]}
    status,response=call("/v1/chat/completions",request,key="SENTINEL_DEMO_KEY")
    assert status==200 and response["sentinel"]["input_action"]=="redact", "Demo requires the default email-redaction policy"
    operation=response["sentinel"]["operation_id"]
    status,trace=call("/api/v1/management/operations/"+operation,key="SENTINEL_VIEWER_KEY")
    assert status==200 and trace["operation"]["status"]=="completed"
    assert "alice@example.test" not in json.dumps(trace)
    assert call("/api/v1/management/operations/"+operation,key="SENTINEL_OTHER_OPERATOR_KEY")[0]==404
    print("PASS: durable metadata-only trace is visible only to its tenant")
    candidate=change(original);candidate["policy"]["actions"]["pii"]["prompt"]="deny"
    applied=False
    try:
        status,current=call("/api/v1/management/config",candidate,method="PUT");assert status==200;applied=True
        assert call("/v1/chat/completions",request,key="SENTINEL_DEMO_KEY")[0]==403
        assert call("/api/v1/management/config",candidate,method="PUT")[0]==409
        print("PASS: policy update changes real enforcement; stale update rejected")
        assert call("/api/v1/management/config/history?limit=5")[0]==200
        print("PASS: version history records the actor and configuration")
    finally:
        if applied:
            status,current=call("/api/v1/management/config");assert status==200
            restore=change(current);restore["policy"]=copy.deepcopy(original["policy"]);restore["registry"]=copy.deepcopy(original["registry"]);restore["policy"]["version"]=restore["expected_revision"]+1;restore["registry"]["version"]=restore["expected_revision"]+1
            status,_=call("/api/v1/management/config",restore,method="PUT");assert status==200
    print("PASS: original policy restored as a new revision; history preserved")
    print(json.dumps(trace,indent=2))


if __name__=="__main__":main()
