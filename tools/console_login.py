"""Copy a local console password to the macOS clipboard without printing it."""
import argparse
import shutil
import subprocess
from demo import local_env


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--account',choices=['operator','viewer'],default='operator')
    args=parser.parse_args()
    env=local_env()
    password=env.get('SENTINEL_CONSOLE_'+args.account.upper()+'_PASSWORD')
    if not password:
        raise SystemExit('Run python3 tools/setup_dev.py first.')
    if not shutil.which('pbcopy'):
        raise SystemExit('Clipboard helper requires macOS. Retrieve the console account password privately from .env; do not use a gateway API key.')
    subprocess.run(['pbcopy'],input=password.encode(),check=True)
    print('Console '+args.account+' password copied to your clipboard; not printed. Paste into the login form.')
    print('Open http://127.0.0.1:'+env.get('SENTINEL_CONSOLE_PORT','3000')+'. Clear the clipboard after signing in.')


if __name__=='__main__':
    main()
