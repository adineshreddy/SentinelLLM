"""Real PostgreSQL/Redis Java checks in disposable private test containers; no model calls."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1]


def docker(*args):
    result=subprocess.run(["docker",*args],cwd=ROOT,check=True,capture_output=True,text=True)
    return result.stdout.strip()


def main():
    suffix=secrets.token_hex(6)
    pg="sentinel-state-test-pg-"+suffix
    redis="sentinel-state-test-redis-"+suffix
    owner,app,password=(secrets.token_urlsafe(48) for _ in range(3))
    containers=[]
    with tempfile.TemporaryDirectory(prefix="sentinel-state-tests-") as directory:
        folder=Path(directory);pg_env=folder/"postgres.env";redis_env=folder/"redis.env"
        pg_env.write_text(f"POSTGRES_USER=sentinel_owner\nPOSTGRES_DB=sentinel\nPOSTGRES_PASSWORD={owner}\nSENTINEL_DB_APP_PASSWORD={app}\n")
        redis_env.write_text(f"SENTINEL_REDIS_PASSWORD={password}\n")
        pg_env.chmod(0o600);redis_env.chmod(0o600)
        try:
            docker("run","-d","--name",pg,"--memory","256m","--env-file",str(pg_env),"-p","127.0.0.1::5432","-v",str(ROOT/"infrastructure/postgres/01-roles.sql")+":/docker-entrypoint-initdb.d/01-roles.sql:ro","postgres:17-alpine");containers.append(pg)
            docker("run","-d","--name",redis,"--memory","128m","--env-file",str(redis_env),"-p","127.0.0.1::6379","redis:7.2-alpine","sh","-c",'exec redis-server --requirepass "$SENTINEL_REDIS_PASSWORD" --maxmemory 64mb --maxmemory-policy noeviction');containers.append(redis)
            for _ in range(120):
                try:
                    docker("exec",pg,"pg_isready","-U","sentinel_owner","-d","sentinel")
                    docker("exec",redis,"sh","-c",'REDISCLI_AUTH="$SENTINEL_REDIS_PASSWORD" redis-cli ping')
                    break
                except subprocess.CalledProcessError:time.sleep(.25)
            else:raise RuntimeError("Test dependencies did not become ready")
            # pg_isready can answer during bootstrap; wait until the restricted role exists.
            for _ in range(120):
                try:
                    exists=docker("exec",pg,"psql","-U","sentinel_owner","-d","sentinel","-Atc","SELECT count(*) FROM pg_roles WHERE rolname='sentinel_app'")
                    if exists=="1":break
                except subprocess.CalledProcessError:pass
                time.sleep(.25)
            else:raise RuntimeError("Test role bootstrap failed")
            time.sleep(.5)
            pg_port=docker("port",pg,"5432/tcp").rsplit(":",1)[1];redis_port=docker("port",redis,"6379/tcp").rsplit(":",1)[1]
            env=dict(os.environ,SENTINEL_STATE_TESTS="1",SENTINEL_TEST_DB_URL=f"jdbc:postgresql://127.0.0.1:{pg_port}/sentinel",SENTINEL_TEST_DB_OWNER_PASSWORD=owner,SENTINEL_TEST_DB_APP_PASSWORD=app,SENTINEL_TEST_REDIS_PORT=redis_port,SENTINEL_TEST_REDIS_PASSWORD=password)
            print("Running isolated PostgreSQL/Redis checks. Credentials remain private.",flush=True)
            result=subprocess.run([str(ROOT/"services/gateway/mvnw"),"-q","-f",str(ROOT/"services/gateway/pom.xml"),"-Dtest=StateIntegrationTest","test"],cwd=ROOT,env=env)
            if result.returncode:raise SystemExit(result.returncode)
        finally:
            for container in reversed(containers):
                subprocess.run(["docker","rm","-f",container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    print("State checks passed; disposable containers removed.")


if __name__=="__main__":main()
