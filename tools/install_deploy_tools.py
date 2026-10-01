"""Install pinned local deployment CLIs under ignored runtime/bin, verifying publisher checksums."""
import argparse
import hashlib
from pathlib import Path
import platform
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
KIND='0.33.0'
TERRAFORM='1.16.4'

def download(url,path):
    subprocess.run(['curl','--fail','--silent','--show-error','--location','--proto','=https','--tlsv1.2','--max-time','120',url,'--output',str(path)],check=True)

def install():
    system={'Darwin':'darwin','Linux':'linux'}.get(platform.system());arch={'arm64':'arm64','aarch64':'arm64','x86_64':'amd64'}.get(platform.machine())
    if not system or not arch:raise SystemExit('Supported hosts: macOS/Linux, ARM64/AMD64.')
    target=ROOT/'runtime/bin';target.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='sentinel-cli-') as temp:
        folder=Path(temp)
        name=f'kind-{system}-{arch}';binary=folder/name;checks=folder/'kind.sha256'
        base=f'https://github.com/kubernetes-sigs/kind/releases/download/v{KIND}/'
        download(base+name,binary);download(base+name+'.sha256sum',checks)
        expected=checks.read_text().split()[0]
        if hashlib.sha256(binary.read_bytes()).hexdigest()!=expected:raise SystemExit('kind checksum mismatch')
        (target/'kind').write_bytes(binary.read_bytes());(target/'kind').chmod(0o755)
        name=f'terraform_{TERRAFORM}_{system}_{arch}.zip';archive=folder/name;checks=folder/'terraform.sha256'
        base=f'https://releases.hashicorp.com/terraform/{TERRAFORM}/'
        download(base+name,archive);download(base+f'terraform_{TERRAFORM}_SHA256SUMS',checks)
        expected=next(line.split()[0] for line in checks.read_text().splitlines() if line.split()[-1]==name)
        if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:raise SystemExit('Terraform checksum mismatch')
        with zipfile.ZipFile(archive) as zipped:(target/'terraform').write_bytes(zipped.read('terraform'))
        (target/'terraform').chmod(0o755)
    print(f'Checksum-verified kind {KIND} and Terraform {TERRAFORM} installed in runtime/bin.')
if __name__=='__main__':install()
