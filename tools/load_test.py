"""Bounded local mock-only closed-loop benchmark. Metadata-only results, no hosted calls."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import copy
import json
import math
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import threading
import time
import urllib.request
import urllib.error
from demo import local_env

ROOT=Path(__file__).resolve().parents[1]

def percentile(values,q):
    ordered=sorted(values)
    return ordered[max(0,math.ceil(len(ordered)*q)-1)] if ordered else None

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--requests',type=int,default=100);parser.add_argument('--concurrency',type=int,nargs='+',default=[1,2,8]);parser.add_argument('--output',type=Path,default=ROOT/'runtime/phase6-load.json');args=parser.parse_args()
    if not 10<=args.requests<=200 or not all(1<=c<=32 for c in args.concurrency) or len(args.concurrency)>4:parser.error('Use 10–200 requests and up to four concurrency levels of 1–32.')
    env=local_env()
    if env.get('SENTINEL_PROVIDER')!='mock' or os.getenv('SENTINEL_PROVIDER','mock')!='mock':raise SystemExit('Benchmark requires mock configuration.')
    # Verify the running container too; a stale .env must not authorize hosted generation.
    mode=subprocess.run(['docker','compose','exec','-T','gateway','printenv','SENTINEL_PROVIDER'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip()
    if mode!='mock':raise SystemExit('Running gateway must use mock provider.')
    base='http://127.0.0.1:'+env.get('SENTINEL_PORT','8080')
    def call(path,body=None,key='SENTINEL_BENCH_KEY',method=None,text=False):
        request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+env[key],'Content-Type':'application/json'},method=method)
        try:
            with urllib.request.urlopen(request,timeout=12) as response:return response.status,response.read().decode() if text else json.load(response)
        except urllib.error.HTTPError as response:return response.code,response.read().decode() if text else json.load(response)
    def update(config):
        candidate=copy.deepcopy(config);candidate['expected_revision']=candidate.pop('revision');candidate['policy']['version']=candidate['expected_revision']+1;candidate['registry']['version']=candidate['expected_revision']+1
        status,current=call('/api/v1/management/config',candidate,'SENTINEL_BENCH_OPERATOR_KEY','PUT');assert status==200,'Benchmark policy update failed';return current
    status,original=call('/api/v1/management/config',key='SENTINEL_BENCH_OPERATOR_KEY');assert status==200 and original['policy']['tenant_id']=='benchmark'
    candidate=copy.deepcopy(original);candidate['policy']['limits']['requests_per_window']=1000;candidate['policy']['limits']['window_seconds']=1
    applied=False;stop=threading.Event();resource_samples=[]
    def sample():
        while not stop.is_set():
            proc=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}'],capture_output=True,text=True,timeout=10)
            rows=[]
            for line in proc.stdout.splitlines():
                item=json.loads(line)
                if item['Name'].startswith('sentinellm-'):
                    rows.append({k:item[k] for k in ('Name','CPUPerc','MemUsage')})
            resource_samples.append(rows)
            stop.wait(1)
    def totals():
        status,scrape=call('/internal/metrics',key='SENTINEL_METRICS_KEY',text=True);assert status==200
        result={}
        for line in scrape.splitlines():
            match=re.match(r'sentinel_dependency_duration_seconds_(sum|count)\{([^}]+)\}\s+([0-9.eE+-]+)',line)
            if match:
                labels=dict(re.findall(r'(\w+)="([^"]*)"',match[2]));k=labels['dependency']+'/'+labels['result']+'/'+match[1];result[k]=float(match[3])
        return result
    report={'format_version':1,'provider':'mock','classifier_mode':subprocess.run(['docker','compose','exec','-T','inspection','printenv','SENTINEL_CLASSIFIER_MODE'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip(),'timestamp_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'hardware':{'os':platform.platform(),'architecture':platform.machine(),'logical_cpus':os.cpu_count()},'method':'closed-loop stdlib urllib; nearest-rank percentiles; client latency includes loopback, serialization and durable state','runs':[]}
    try:
        report['benchmark_policy']=update(candidate)['policy'];applied=True
        for key,command in {'cpu':['sysctl','-n','machdep.cpu.brand_string'],'ram_bytes':['sysctl','-n','hw.memsize'],'docker':['docker','version','--format','{{.Server.Version}}'],'compose':['docker','compose','version','--short'],'gateway_java':['docker','compose','exec','-T','gateway','java','-version'],'inspector_python':['docker','compose','exec','-T','inspection','python','--version'],'redis':['docker','compose','exec','-T','redis','redis-server','--version'],'postgres':['docker','compose','exec','-T','postgres','postgres','--version'],'prometheus':['docker','compose','exec','-T','prometheus','prometheus','--version']}.items():
            proc=subprocess.run(command,cwd=ROOT,capture_output=True,text=True,timeout=10);report['hardware' if key in ('cpu','ram_bytes') else 'versions']=report.get('hardware' if key in ('cpu','ram_bytes') else 'versions',{});report['hardware' if key in ('cpu','ram_bytes') else 'versions'][key]=(proc.stdout+proc.stderr).strip()
        sampler=threading.Thread(target=sample,daemon=True);sampler.start()
        workloads={
            'preview':('/api/v1/inspect',{'segments':[{'id':'p0','stage':'prompt','path':'/text','text':'How do I reset my account password?'}]}),
            'chat':('/v1/chat/completions',{'model':env['NAVIGATOR_LLM_MODEL'],'messages':[{'role':'user','content':'How do I reset my account password?'}]}),
            'tool':('/api/v1/tools/execute',{'tool_id':'kb.search','arguments':{'query':'password reset'}}),
        }
        for name,(path,payload) in workloads.items():
            for concurrency in args.concurrency:
                for _ in range(5):assert call(path,payload)[0]==200,'Warmup failed'
                before=totals();start=time.perf_counter()
                def once(_):
                    began=time.perf_counter()
                    try:status,_body=call(path,payload)
                    except (TimeoutError,urllib.error.URLError):status=0
                    return status,(time.perf_counter()-began)*1000
                with ThreadPoolExecutor(max_workers=concurrency) as pool:responses=list(pool.map(once,range(args.requests)))
                elapsed=time.perf_counter()-start;after=totals();counts=Counter(s for s,_ in responses);ok=[ms for s,ms in responses if s==200];latency=[ms for _,ms in responses]
                dependencies={}
                for kind in ('inspection','provider','tool','audit','state','redis'):
                    seconds=sum(after.get(kind+'/'+r+'/sum',0)-before.get(kind+'/'+r+'/sum',0) for r in ('success','failure','rejected'))
                    calls=sum(after.get(kind+'/'+r+'/count',0)-before.get(kind+'/'+r+'/count',0) for r in ('success','failure','rejected'))
                    if calls:dependencies[kind]={'calls':int(calls),'mean_call_ms':seconds*1000/calls,'amortized_ms_per_attempt':seconds*1000/args.requests}
                run={'workload':name,'concurrency':concurrency,'attempts':args.requests,'duration_seconds':elapsed,'attempts_per_second':args.requests/elapsed,'successful_per_second':len(ok)/elapsed,'statuses':dict(counts),'all_latency_ms':{f'p{int(q*100)}':percentile(latency,q) for q in (.5,.95,.99)},'success_latency_ms':{f'p{int(q*100)}':percentile(ok,q) for q in (.5,.95,.99)},'mean_client_ms':statistics.mean(latency),'dependencies':dependencies}
                run['unattributed_client_ms_per_attempt']=run['mean_client_ms']-sum(d['amortized_ms_per_attempt'] for d in dependencies.values())
                report['runs'].append(run);print(name+' c='+str(concurrency)+' success='+str(len(ok))+'/'+str(args.requests)+' p95='+str(round(run['all_latency_ms']['p95'],2))+'ms')
    finally:
        stop.set()
        if 'sampler' in locals():sampler.join(timeout=12)
        if applied:
            status,current=call('/api/v1/management/config',key='SENTINEL_BENCH_OPERATOR_KEY');assert status==200
            restore=copy.deepcopy(original);restore['revision']=current['revision'];update(restore)
    report['resources_docker_stats']=resource_samples
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n');print('Metadata-only benchmark saved to '+str(args.output))
if __name__=='__main__':main()
