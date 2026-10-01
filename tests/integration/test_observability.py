"""Sequential, opt-in local Phase 6 failure and monitoring exercises. Never a hosted provider."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
import re
import subprocess
import time
import urllib.request
import urllib.error
import pytest
from test_compose import ROOT, environment, compose, call, chat

pytestmark=pytest.mark.skipif(os.getenv('SENTINEL_COMPOSE_TESTS')!='1',reason='Opt-in local Compose integration')

def scrape(key='SENTINEL_METRICS_KEY'):
    env=environment();headers={} if key is None else {'Authorization':'Bearer '+env[key]}
    request=urllib.request.Request('http://127.0.0.1:'+env.get('SENTINEL_PORT','8080')+'/internal/metrics',headers=headers)
    try:
        with urllib.request.urlopen(request,timeout=5) as response:return response.status,response.read().decode()
    except urllib.error.HTTPError as response:return response.code,response.read().decode()

def metric(name,dependency):
    status,text=scrape();assert status==200
    matches=re.findall(r'^'+re.escape(name)+r'\{dependency="'+dependency+r'"\}\s+([0-9.eE+-]+)',text,re.M)
    return sum(map(float,matches))

def benchmark_chat():return call(chat(),key_name='SENTINEL_BENCH_KEY')

def test_monitoring_auth_privacy_and_real_prometheus_target():
    assert scrape(None)[0]==401
    assert scrape('SENTINEL_DEMO_KEY')[0]==401
    assert scrape('SENTINEL_OPERATOR_KEY')[0]==401
    assert call(chat(),key_name='SENTINEL_METRICS_KEY')[0]==401
    status,text=scrape();assert status==200 and 'jvm_memory_used_bytes' in text
    forbidden=['tenant_id=','operation_id=','application_id=','alice@example','Authorization','prompt_text','secret-model']
    assert not any(item in text for item in forbidden)
    with urllib.request.urlopen('http://127.0.0.1:9090/api/v1/targets',timeout=5) as response:targets=json.load(response)['data']['activeTargets']
    assert any(t['labels']['job']=='sentinel-gateway' and t['health']=='up' for t in targets)

def test_inspection_outage_is_bounded_and_never_dispatches_provider():
    before=metric('sentinel_dependency_calls_total','provider');compose('stop','inspection')
    try:
        start=time.monotonic();status,result=benchmark_chat()
        assert status==503 and result['error']['code']=='DEPENDENCY_UNAVAILABLE'
        assert time.monotonic()-start<8
        assert metric('sentinel_dependency_calls_total','provider')==before
    finally:compose('up','-d','--wait','inspection')
    deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        if benchmark_chat()[0]==200:return
        time.sleep(.2)
    pytest.fail('Inspection recovery did not restore new requests',pytrace=False)

def test_redis_outage_fails_closed_before_provider_and_recovers():
    before=metric('sentinel_dependency_calls_total','provider');compose('stop','redis')
    try:
        start=time.monotonic();status,result=benchmark_chat()
        assert status==503 and result['error']['code']=='DEPENDENCY_UNAVAILABLE'
        assert time.monotonic()-start<8
        assert metric('sentinel_dependency_calls_total','provider')==before
    finally:compose('up','-d','--wait','redis')
    deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        if benchmark_chat()[0]==200:return
        time.sleep(.2)
    pytest.fail('Redis recovery did not restore requests',pytrace=False)

def sql(statement):
    subprocess.run(['docker','compose','exec','-T','postgres','psql','-U','sentinel_owner','-d','sentinel','-v','ON_ERROR_STOP=1','-c',statement],cwd=ROOT,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

def test_audit_insert_failure_blocks_provider_and_leaves_failed_trace():
    before=metric('sentinel_dependency_calls_total','provider')
    sql('REVOKE INSERT ON audit_events FROM sentinel_app;')
    try:
        status,result=benchmark_chat();assert status==503
        assert metric('sentinel_dependency_calls_total','provider')==before
        operation=result['error']['operation_id']
        status,trace=call(path='/api/v1/management/operations/'+operation,key_name='SENTINEL_BENCH_OPERATOR_KEY')
        assert status==200 and trace['operation']['status']=='failed'
        assert trace['operation']['external_state']=='none'
    finally:sql('GRANT INSERT ON audit_events TO sentinel_app;')
    assert benchmark_chat()[0]==200

def delayed_gateway(delay):
    env=dict(os.environ,SENTINEL_PROVIDER='mock',SENTINEL_HOSTED_ENABLED='false',SENTINEL_CLASSIFIER_MODE='off',SENTINEL_MOCK_DELAY_MS=str(delay))
    proc=subprocess.run(['docker','compose','up','-d','--wait','gateway'],cwd=ROOT,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if proc.returncode:pytest.fail('Mock-latency gateway did not start',pytrace=False)

def test_provider_concurrency_rejects_excess_without_queued_dispatch():
    delayed_gateway(1000)
    try:
        before=metric('sentinel_dependency_calls_total','provider')
        with ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(lambda _:benchmark_chat(),range(8)))
        successes=sum(status==200 for status,_ in results)
        assert 1<=successes<=2 and sum(status==429 for status,_ in results)==8-successes
        assert metric('sentinel_dependency_calls_total','provider')-before==successes
    finally:delayed_gateway(0)

def test_graceful_shutdown_drains_dispatched_request():
    delayed_gateway(2000)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(benchmark_chat);deadline=time.monotonic()+8
            while metric('sentinel_dependency_active','provider')<1:
                assert time.monotonic()<deadline;time.sleep(.05)
            compose('kill','-s','SIGTERM','gateway')
            status,result=pending.result(timeout=10)
            assert status==200 and result['sentinel']['provider_mode']=='mock'
        compose('up','-d','--wait','gateway')
        status,trace=call(path='/api/v1/management/operations/'+result['sentinel']['operation_id'],key_name='SENTINEL_BENCH_OPERATOR_KEY')
        assert status==200 and trace['operation']['status']=='completed' and trace['operation']['external_state']=='returned'
    finally:delayed_gateway(0)
