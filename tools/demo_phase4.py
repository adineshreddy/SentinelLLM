"""Run mock-only console demos through an authenticated operator session."""
import argparse
import http.cookiejar
import json
import urllib.error
import urllib.request
from demo import local_env


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed',action='store_true',help='Append four synthetic preview events.')
    parser.add_argument('--reset',action='store_true',help='Append reviewed demo defaults as a new revision; retain history.')
    args=parser.parse_args()
    env=local_env()
    if env.get('SENTINEL_PROVIDER','mock')!='mock':
        raise SystemExit('Repeatable console demo requires mock mode. No hosted calls made.')
    base='http://127.0.0.1:'+env.get('SENTINEL_CONSOLE_PORT','3000')
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf=''
    def call(route,payload=None):
        req=urllib.request.Request(base+route,data=None if payload is None else json.dumps(payload).encode(),headers={'Content-Type':'application/json','Origin':base,'X-CSRF-Token':csrf})
        try:
            with opener.open(req,timeout=45) as r:return json.load(r)
        except urllib.error.HTTPError as r:
            try:code=json.load(r).get('error',{}).get('code','REQUEST_FAILED')
            except Exception:code='REQUEST_FAILED'
            raise SystemExit('Console request failed: '+code) from None
    session=call('/api/login',{'username':'operator','password':env['SENTINEL_CONSOLE_OPERATOR_PASSWORD']});csrf=session['csrf']
    try:
        if args.reset:
            config=call('/api/config');reset=call('/api/demo/reset',{'expected_revision':config['revision']})
            print('PASS: reviewed demo defaults appended at revision '+str(reset['revision'])+'; history retained.')
        if args.seed:
            results=call('/api/demo/seed',{})['results']
            assert len(results)==4 and all(r['status']==200 for r in results)
            for r in results:print(r['label']+': '+r['data']['decision']['action'])
            print('PASS: four synthetic previews appended to the audit feed.')
        if not args.seed and not args.reset:
            result=call('/api/demo/support',{})
            assert len(result['steps'])==3 and all(s['status']==200 for s in result['steps']), 'Workflow blocked; inspect active policy and quotas in the console.'
            for s in result['steps']:print('PASS: '+s['label']+' — '+s.get('operation_id',''))
    finally:
        call('/api/logout',{})
    print('No hosted calls made. Console credentials and gateway keys were not printed.')


if __name__=='__main__':
    main()
