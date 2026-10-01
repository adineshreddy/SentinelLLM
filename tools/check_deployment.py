"""Offline invariants for safe deployment and CI configuration; no application secrets required."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
def main():
    workflow=(ROOT/'.github/workflows/ci.yml').read_text()
    references=re.findall(r'uses:\s*([^\s]+)',workflow)
    assert len(references)>=5 and all(re.fullmatch(r'actions/[a-z-]+@[0-9a-f]{40}',ref) for ref in references),'Pin first-party actions to exact commits'
    assert 'contents: read' in workflow and 'persist-credentials: false' in workflow
    assert 'pull_request_target' not in workflow and 'secrets.' not in workflow
    assert 'github.event.repository.private' in workflow,'Private repository hosted runs need explicit dispatch'
    text='\n'.join(p.read_text() for p in (ROOT/'infrastructure/terraform').glob('*.tf'))
    assert 'ephemeral' in text and 'data_wo' in text and 'data_wo_revision' in text
    assert 'kind-sentinellm' in text and 'config_path' in text
    assert 'default-deny' in text and '["Ingress", "Egress"]' in text
    assert 'LoadBalancer' not in text and 'NodePort' not in text
    assert 'SENTINEL_PROVIDER' in text and '"mock"' in text and 'NAVIGATOR_API_KEY' not in text
    assert 'automount_service_account_token = false' in text and 'run_as_non_root' in text
    assert 'secrets' in (ROOT/'.dockerignore').read_text() and 'runtime/' in (ROOT/'.gitignore').read_text()
    print('PASS: pinned/read-only CI, private-run cost guard, dedicated context, write-only secrets, mock-only config, private services and network-policy invariants.')
if __name__=='__main__':main()
