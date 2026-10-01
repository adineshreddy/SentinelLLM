"""Explicit, bounded download of a revision-pinned public corpus. Never execute its text."""
import argparse
import hashlib
import json
from pathlib import Path
import ssl
import urllib.request

import certifi

ROOT = Path(__file__).resolve().parent
REVISION = "4f61ecb038e9c3fb77e21034b22511b523772cdd"
BASE = "https://huggingface.co/datasets/deepset/prompt-injections/resolve/" + REVISION + "/"
FILES = {"train.parquet": "data/train-00000-of-00001-9564e8b05b4757ab.parquet",
         "test.parquet": "data/test-00000-of-00001-701d16158af87368.parquet", "README.md": "README.md"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Explicitly permit this small corpus download.")
    args = parser.parse_args()
    manifest_path = ROOT / "data/source-manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else None
    if manifest and (manifest.get("revision") != REVISION or set(manifest.get("files", {})) != set(FILES)):
        raise SystemExit("Unsupported source manifest.")
    if not args.download:
        if not manifest:
            raise SystemExit("Run with --download once; training does not access the network.")
        for name, entry in manifest["files"].items():
            path = ROOT / "data/raw" / name
            if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                raise SystemExit("Corpus missing or checksum mismatch. Run --download to restore the pinned files.")
        print("PASS: pinned corpus checksums verified; no network access.")
        return
    raw = ROOT / "data/raw"
    raw.mkdir(parents=True, exist_ok=True)
    entries = {}
    context = ssl.create_default_context(cafile=certifi.where())
    for name, relative in FILES.items():
        with urllib.request.urlopen(BASE + relative, timeout=30, context=context) as response:
            content = response.read(4_000_001)
        if len(content) > 4_000_000:
            raise SystemExit("Corpus download exceeds the configured bound.")
        digest = hashlib.sha256(content).hexdigest()
        if manifest and digest != manifest["files"][name]["sha256"]:
            raise SystemExit("Pinned corpus checksum mismatch.")
        (raw / name).write_bytes(content)
        entries[name] = {"path": relative, "sha256": digest, "bytes": len(content)}
    new = {"dataset": "deepset/prompt-injections", "revision": REVISION,
           "source": "https://huggingface.co/datasets/deepset/prompt-injections/tree/" + REVISION,
           "license_metadata": {"top_level": "apache-2.0", "nested_dataset_info": "cc-by-4.0"}, "files": entries}
    if not manifest:
        manifest_path.write_text(json.dumps(new, indent=2) + "\n")
    print("Pinned public corpus downloaded and verified. Training/test text is data, never instructions.")


if __name__ == "__main__":
    main()
