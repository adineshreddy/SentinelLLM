"""Evaluate frozen models on held-out rows; never select thresholds or modify weights."""
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time

from corpus import load, allocate
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'services/inspection'))
from app.classifier import TfidfClassifier, sigmoid
from app.main import COMPILED, inspect_segments


def metrics(labels,predictions):
    tp=sum(y==1 and p for y,p in zip(labels,predictions));fp=sum(y==0 and p for y,p in zip(labels,predictions))
    tn=sum(y==0 and not p for y,p in zip(labels,predictions));fn=sum(y==1 and not p for y,p in zip(labels,predictions))
    precision=tp/(tp+fp) if tp+fp else 0;recall=tp/(tp+fn) if tp+fn else 0
    return {'tp':tp,'fp':fp,'tn':tn,'fn':fn,'precision':precision,'recall':recall,'f1':2*precision*recall/(precision+recall) if precision+recall else 0,
            'false_positive_rate':fp/(fp+tn) if fp+tn else 0,'accuracy':(tp+tn)/len(labels),'n':len(labels)}


def interval(success,total):
    if not total:return [0,1]
    z=1.96;p=success/total;denom=1+z*z/total;center=(p+z*z/(2*total))/denom;half=z*math.sqrt(p*(1-p)/total+z*z/(4*total*total))/denom
    return [max(0,center-half),min(1,center+half)]


def probability_metrics(labels,scores):
    brier=statistics.mean((s-y)**2 for s,y in zip(scores,labels));ece=0
    for i in range(10):
        selected=[(s,y) for s,y in zip(scores,labels) if i/10<=s<(i+1)/10 or i==9 and s==1]
        if selected:ece+=len(selected)/len(labels)*abs(statistics.mean(s for s,_ in selected)-statistics.mean(y for _,y in selected))
    return {'brier_score':brier,'ece_10_equal_width_bins':ece}


def rules(text):return [rule for rule,category,_,pattern in COMPILED if category in ('prompt_injection','jailbreak') and pattern.search(text)]


def run(model,rows):
    samples=[]
    for r in rows:
        score=model.score(r['text']);rule_ids=rules(r['text']);ml=score>=model.threshold
        samples.append({'id':r['id'],'label':r['label'],'score':score,'rule_ids':rule_ids,'ml_positive':ml,'combined_positive':bool(rule_ids) or ml,'chars':len(r['text'])})
    labels=[r['label'] for r in samples]
    results={name:metrics(labels,[bool(r[field]) for r in samples]) for name,field in [('rules_only','rule_ids'),('ml_only','ml_positive'),('rules_plus_ml','combined_positive')]}
    for result in results.values():
        result['recall_wilson_95']=interval(result['tp'],result['tp']+result['fn']);result['false_positive_rate_wilson_95']=interval(result['fp'],result['fp']+result['tn'])
    results['calibration']=probability_metrics(labels,[r['score'] for r in samples])
    return {'metrics':results,'samples':samples,'combined_false_negatives':[r['id'] for r in samples if r['label']==1 and not r['combined_positive']],
            'combined_false_positives':[r['id'] for r in samples if r['label']==0 and r['combined_positive']]}


def percentile(values,p):return sorted(values)[min(len(values)-1,math.ceil(p*len(values))-1)]


def hardware():
    result={'python':platform.python_version(),'platform':platform.system(),'machine':platform.machine(),'logical_cpus':os.cpu_count()}
    if platform.system()=='Darwin':
        for key,sysctl in [('cpu_model','machdep.cpu.brand_string'),('memory_bytes','hw.memsize')]:
            try:
                value=subprocess.run(['sysctl','-n',sysctl],check=True,capture_output=True,text=True,timeout=2).stdout.strip()
                result[key]=int(value) if key=='memory_bytes' else value
            except (OSError,ValueError,subprocess.SubprocessError):result[key]=None
    return result


def main():
    model_dir=ROOT/'services/inspection/models';manifest=json.loads((model_dir/'manifest.json').read_text())
    started=time.perf_counter();model=TfidfClassifier.load(model_dir/manifest['file'],manifest['sha256']);load_ms=(time.perf_counter()-started)*1000
    rows,hygiene=load();parts=allocate(rows);committed=json.loads((ROOT/'evaluation/data/splits-v1.json').read_text())
    for name,part in parts.items():
        expected=committed['partitions'][name]
        records=[{'id':r['id'],'group_id':r['group_id'],'label':r['label'],'text_sha256':hashlib.sha256(r['text'].encode()).hexdigest()} for r in part]
        if records!=expected:raise ValueError('Frozen split mismatch')
    fingerprint=hashlib.sha256(json.dumps(committed,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    artifact=json.loads((model_dir/manifest['file']).read_text())
    if fingerprint!=manifest['training_fingerprint'] or fingerprint!=artifact['training_fingerprint']:raise ValueError('Model/split provenance mismatch')
    held=run(model,parts['held_out']);dev=run(model,parts['threshold_dev'])
    artifact=json.loads((model_dir/manifest['file']).read_text());uncalibrated=TfidfClassifier(model.vocabulary,model.intercept,1.0,0.0,model.threshold)
    held['metrics']['uncalibrated_probability_metrics']=probability_metrics([r['label'] for r in parts['held_out']],[uncalibrated.score(r['text']) for r in parts['held_out']])
    challenge=json.loads((ROOT/'evaluation/fixtures/phase5-challenge-v1.json').read_text())
    challenge_result=run(model,[dict(c) for c in challenge['cases']])
    times=[];combined_times=[]
    for r in parts['held_out'][:20]:model.score(r['text'])
    for _ in range(5):
        for r in parts['held_out']:
            started=time.perf_counter();model.score(r['text']);times.append((time.perf_counter()-started)*1000)
            started=time.perf_counter();inspect_segments('00000000-0000-4000-8000-000000000001',[{'id':'s0','stage':'prompt','path':'/text','text':r['text']}],model);combined_times.append((time.perf_counter()-started)*1000)
    stress=' '.join(['support documentation policy reference']*500)[:16384]
    started=time.perf_counter();inspect_segments('00000000-0000-4000-8000-000000000001',[{'id':'s'+str(i),'text':stress} for i in range(10)],model);bounded_ms=(time.perf_counter()-started)*1000
    report={'report_version':'phase5-v1','source_manifest':json.loads((ROOT/'evaluation/data/source-manifest.json').read_text()),'model_manifest':manifest,
            'splits':{name:{'rows':len(part),'labels':dict(Counter(r['label'] for r in part)),'groups':len({r['group_id'] for r in part})} for name,part in parts.items()},'hygiene':hygiene,
            'threshold_dev':dev,'held_out':held,'engineering_challenge':challenge_result,
            'latency':{'environment':hardware(),
                       'timing':'perf_counter; 20 ML warm-up samples; 5 sequential repetitions of 116 held-out texts; excludes HTTP/Java/provider/database and training',
                       'classifier_load_ms':load_ms,'ml_only_ms':{'p50':percentile(times,.5),'p95':percentile(times,.95),'p99':percentile(times,.99)},
                       'rules_plus_ml_ms':{'p50':percentile(combined_times,.5),'p95':percentile(combined_times,.95),'p99':percentile(combined_times,.99)},
                       'bounded_workload':{'segments':10,'chars_per_segment':len(stress),'total_utf8_bytes':10*len(stress.encode()),'elapsed_ms':bounded_ms}},
            'limits':['Small public corpus with a sparse dataset card and mixed language/domain content. Binary injection labels do not cover all risks.',
                      'Threshold budget is an observed development rate, not a population guarantee. Confidence intervals reflect small sample counts.',
                      'Exact/near duplicate grouping limits measured overlap, but cannot establish semantic family independence or eliminate contamination of the public benchmark.',
                      'Classifier is lexical, not a transformer; quoted security discussion, multilingual/encoded attacks and dilution remain important limitations.',
                      'Challenge cases are engineering-authored diagnostic fixtures, not an independent real-world benchmark. Neither scores nor mock generation establish universal protection.']}
    dest=ROOT/'evaluation/reports/phase5-v1.json';dest.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
    print(json.dumps({'held_out':held['metrics'],'engineering_challenge':challenge_result['metrics'],'latency':report['latency']},indent=2))


if __name__=='__main__':main()
