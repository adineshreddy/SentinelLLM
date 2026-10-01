"""Corpus hygiene and split allocation. No fitting or threshold selection uses test rows."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import unicodedata

ROOT = Path(__file__).resolve().parent


def normalized(text):
    return " ".join(re.findall(r"\w+", unicodedata.normalize("NFKC", text).casefold()))


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def family(text):
    words = normalized(text).split()
    return {tuple(words[i:i+3]) for i in range(len(words)-2)} if len(words) >= 6 else set()


def groups(rows):
    parent = list(range(len(rows)))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    shingles = [family(r["text"]) for r in rows]
    norm = [normalized(r["text"]) for r in rows]
    for i in range(len(rows)):
        for j in range(i):
            a, b = shingles[i], shingles[j]
            same = norm[i] == norm[j]
            if not same and a and b and min(len(a),len(b)) / max(len(a),len(b)) >= .8:
                same = len(a & b) / len(a | b) >= .8
            if same:
                parent[find(i)] = find(j)
    identifiers = {}
    for i, row in enumerate(rows):
        identifiers.setdefault(find(i), []).append(row["id"])
    return [digest("|".join(sorted(identifiers[find(i)]))) for i in range(len(rows))]


def load():
    import pyarrow.parquet as parquet
    manifest = json.loads((ROOT/"data/source-manifest.json").read_text())
    rows = []
    for split in ("train", "test"):
        path = ROOT / "data/raw" / (split+".parquet")
        if digest_bytes(path.read_bytes()) != manifest["files"][split+".parquet"]["sha256"]:
            raise ValueError("Corpus checksum mismatch")
        for index, row in enumerate(parquet.read_table(path).to_pylist()):
            if type(row.get("label")) is not int or row["label"] not in (0,1) or not isinstance(row.get("text"),str):
                raise ValueError("Invalid corpus row")
            rows.append({"id":split+"-"+str(index),"source_split":split,"text":row["text"],"label":row["label"]})
    counts = Counter(r["source_split"] for r in rows)
    excluded = []
    labels = {}
    for row in rows:
        labels.setdefault(normalized(row["text"]),set()).add(row["label"])
    valid = []
    seen = set()
    for row in rows:
        norm = normalized(row["text"])
        reason = None
        if not norm or len(row["text"]) > 16384:reason="outside_runtime_bounds"
        elif len(labels[norm]) > 1:reason="conflicting_exact_labels"
        elif (row["source_split"],norm) in seen:reason="within_split_exact_duplicate"
        if reason:excluded.append({"id":row["id"],"reason":reason});continue
        seen.add((row["source_split"],norm));valid.append(row)
    group_ids = groups(valid)
    for row,g in zip(valid,group_ids):row["group_id"]=g
    test_groups = {r["group_id"] for r in valid if r["source_split"]=="test"}
    clean = []
    for row in valid:
        if row["source_split"]=="train" and row["group_id"] in test_groups:
            excluded.append({"id":row["id"],"reason":"exact_or_near_duplicate_of_official_test"})
        else:clean.append(row)
    return clean,{"original_counts":dict(counts),"excluded":excluded,"similarity":"NFKC/casefold/word normalization; word-trigram Jaccard >= 0.8 connected components; short texts exact-normalized only"}


def digest_bytes(content):return hashlib.sha256(content).hexdigest()


def allocate(rows, seed=20261001):
    import numpy as np
    from sklearn.model_selection import GroupShuffleSplit
    pool = [r for r in rows if r["source_split"]=="train"]
    official_test = [r for r in rows if r["source_split"]=="test"]
    ids = np.arange(len(pool));family_ids=[r["group_id"] for r in pool]
    fit, dev = next(GroupShuffleSplit(n_splits=1,test_size=.4,random_state=seed).split(ids,groups=family_ids))
    calibrate, threshold = next(GroupShuffleSplit(n_splits=1,test_size=.5,random_state=seed+1).split(dev,groups=[family_ids[i] for i in dev]))
    partitions={"fit":[pool[i] for i in fit],"calibration":[pool[dev[i]] for i in calibrate],"threshold_dev":[pool[dev[i]] for i in threshold],"held_out":[*official_test]}
    for name, part in partitions.items():
        if {r["label"] for r in part}!={0,1}:raise ValueError("Both classes required in "+name)
    names=list(partitions)
    for i,name in enumerate(names):
        for other in names[:i]:
            if {r["group_id"] for r in partitions[name]} & {r["group_id"] for r in partitions[other]}:raise ValueError("Split leakage")
    return partitions
