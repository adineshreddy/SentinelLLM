"""Reproduce a fixed ML baseline, calibration and threshold using development rows only."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
import sklearn

from corpus import load, allocate

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"services/inspection"))
from app.classifier import TfidfClassifier, tokens, VERSION


def select_threshold(labels, scores, budget=.05):
    # Fixed grid, maximum recall under observed development FPR budget.
    candidates=[]
    for threshold in [i/100 for i in range(1,100)]:
        predicted=scores>=threshold
        tp=int(sum((labels==1)&predicted));fp=int(sum((labels==0)&predicted))
        positives=int(sum(labels==1));negatives=int(sum(labels==0))
        if fp/negatives<=budget:
            candidates.append((tp/positives,-fp/negatives,threshold))
    if not candidates:raise ValueError("No threshold meets development budget")
    return max(candidates)[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=ROOT/'services/inspection/models');args=parser.parse_args()
    rows,hygiene=load();parts=allocate(rows)
    fit,calibration,dev=(parts[n] for n in ('fit','calibration','threshold_dev'))
    vectorizer=TfidfVectorizer(tokenizer=tokens,token_pattern=None,lowercase=False,ngram_range=(1,2),sublinear_tf=True,min_df=2,max_features=5000,norm='l2')
    features=vectorizer.fit_transform([r['text'] for r in fit])
    model=LogisticRegression(C=4.0,class_weight='balanced',solver='lbfgs',max_iter=2000,tol=1e-8,random_state=20261001)
    model.fit(features,[r['label'] for r in fit])
    logits=model.decision_function(vectorizer.transform([r['text'] for r in calibration])).reshape(-1,1)
    calibrator=LogisticRegression(C=100.0,solver='lbfgs',max_iter=2000,tol=1e-8,random_state=20261001)
    calibrator.fit(logits,[r['label'] for r in calibration])
    dev_logits=model.decision_function(vectorizer.transform([r['text'] for r in dev])).reshape(-1,1)
    dev_scores=calibrator.predict_proba(dev_logits)[:,1]
    threshold=select_threshold(np.array([r['label'] for r in dev]),dev_scores)
    split_manifest={'seed':20261001,'hygiene':hygiene,'partitions':{name:[{'id':r['id'],'group_id':r['group_id'],'label':r['label'],'text_sha256':hashlib.sha256(r['text'].encode()).hexdigest()} for r in part] for name,part in parts.items()}}
    fingerprint=hashlib.sha256(json.dumps(split_manifest,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    artifact={'schema_version':1,'algorithm':'tfidf-logistic','category':'prompt_injection','version':VERSION,'training_fingerprint':fingerprint,
              'features':[{'term':term,'idf':float(vectorizer.idf_[i]),'weight':float(model.coef_[0,i])} for i,term in enumerate(vectorizer.get_feature_names_out())],
              'intercept':float(model.intercept_[0]),'calibration':{'slope':float(calibrator.coef_[0,0]),'intercept':float(calibrator.intercept_[0])},'threshold':threshold}
    content=(json.dumps(artifact,ensure_ascii=False,separators=(',',':'),allow_nan=False)+'\n').encode()
    digest=hashlib.sha256(content).hexdigest();args.output.mkdir(parents=True,exist_ok=True)
    artifact_path=args.output/'tfidf-pi-v1.json';artifact_path.write_bytes(content)
    inference=TfidfClassifier.load(artifact_path,digest)
    # Verify exported inference independently against sklearn on all dev data.
    for name,part in parts.items():
        if name=='held_out':continue
        logits=model.decision_function(vectorizer.transform([r['text'] for r in part])).reshape(-1,1)
        scores=calibrator.predict_proba(logits)[:,1]
        if max(abs(float(s)-inference.score(r['text'])) for s,r in zip(scores,part))>1e-10:raise ValueError('Export parity failure')
    manifest={'version':VERSION,'runtime_version':inference.version,'file':artifact_path.name,'sha256':digest,'bytes':len(content),'threshold':threshold,'features':len(artifact['features']),
              'training_fingerprint':fingerprint,'training':{'algorithm':'TF-IDF word unigrams/bigrams; sublinear TF; smooth IDF; L2 norm; balanced logistic regression C=4; sigmoid calibration C=100',
              'threshold_objective':'Maximize recall under observed threshold-dev false-positive rate <= 0.05; break ties by lower FPR then higher threshold; fixed grid 0.01..0.99',
              'sklearn_version':sklearn.__version__,'numpy_version':np.__version__,'export_parity_max_error':1e-10}}
    (args.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (ROOT/'evaluation/data/splits-v1.json').write_text(json.dumps(split_manifest,indent=2)+'\n')
    print('PASS: offline model training, separate calibration/threshold selection, and export parity.')
    print(json.dumps({'splits':{k:dict(Counter(r['label'] for r in v)) for k,v in parts.items()},'features':manifest['features'],'threshold':threshold,'artifact_bytes':len(content)},indent=2))


if __name__=='__main__':main()
