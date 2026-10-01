import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("setup_dev",ROOT/"tools/setup_dev.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


def values(path):return dict(line.split("=",1) for line in path.read_text().splitlines() if "=" in line and not line.startswith("#"))


def test_setup_is_private_idempotent_and_separates_credentials(tmp_path):
    path=tmp_path/".env";assert module.configure(path,"gpt-oss-120b")
    first=path.read_text();env=values(path)
    keys=[env[k] for k in env if k.endswith("KEY") and k!="NAVIGATOR_API_KEY"]
    keys += [env[k] for k in env if k.endswith("PASSWORD")]
    assert len(keys)==len(set(keys)) and all(len(k)>=32 for k in keys)
    assert not module.configure(path,"different-model")
    assert path.read_text()==first and path.stat().st_mode & 0o777==0o600
    identities=json.loads(env["SENTINEL_APPLICATION_KEYS_JSON"])
    assert len(identities)==7 and len({i["tenant_id"] for i in identities})==3


def test_setup_preserves_existing_provider_values(tmp_path):
    path=tmp_path/".env";path.write_text("SENTINEL_PROVIDER=navigator\nSENTINEL_HOSTED_ENABLED=true\nNAVIGATOR_API_KEY=synthetic-not-a-real-api-key\nNAVIGATOR_LLM_MODEL=custom-model\n")
    module.configure(path,"gpt-oss-120b");env=values(path)
    assert env["NAVIGATOR_API_KEY"]=="synthetic-not-a-real-api-key"
    assert env["SENTINEL_HOSTED_ENABLED"]=="true" and env["SENTINEL_PROVIDER"]=="navigator"
    assert env["NAVIGATOR_LLM_MODEL"]=="custom-model"


def test_setup_replaces_empty_console_passwords_only(tmp_path):
    path=tmp_path/".env";path.write_text("SENTINEL_CONSOLE_OPERATOR_PASSWORD=\nSENTINEL_CONSOLE_VIEWER_PASSWORD=\nNAVIGATOR_API_KEY=\n")
    module.configure(path,"gpt-oss-120b");env=values(path)
    assert len(env["SENTINEL_CONSOLE_OPERATOR_PASSWORD"])>=32
    assert len(env["SENTINEL_CONSOLE_VIEWER_PASSWORD"])>=32
    assert env["SENTINEL_CONSOLE_OPERATOR_PASSWORD"]!=env["SENTINEL_CONSOLE_VIEWER_PASSWORD"]
    assert env["NAVIGATOR_API_KEY"]==""


def test_blank_template_generates_monitoring_and_benchmark_credentials(tmp_path):
    path=tmp_path/".env";path.write_text((ROOT/".env.example").read_text())
    module.configure(path,"gpt-oss-120b");env=values(path)
    assert all(len(env[name])>=32 for name in ("SENTINEL_METRICS_KEY","SENTINEL_GRAFANA_PASSWORD","SENTINEL_BENCH_KEY","SENTINEL_BENCH_OPERATOR_KEY","SENTINEL_INSPECTION_KEY"))
    identities=json.loads(env["SENTINEL_APPLICATION_KEYS_JSON"])
    assert not any(i["sha256"]==__import__('hashlib').sha256(env["SENTINEL_METRICS_KEY"].encode()).hexdigest() for i in identities)
