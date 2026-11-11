"""Three new Compose projects, cached images, retained volumes. Never touches existing projects."""
import argparse, json, os, pathlib, secrets, subprocess, sys, tempfile, time
p=argparse.ArgumentParser()
p.add_argument('--docker',required=True)
p.add_argument('--schema',required=True)
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'benchmarks/results'/('deployment-'+time.strftime('%Y%m%d-%H%M%S'));out.mkdir(parents=True)
private=pathlib.Path(tempfile.mkdtemp(prefix='opslattice-deploy-benchmark-'))
env=private/'benchmark.env'
env.write_text('\n'.join(k+'='+secrets.token_urlsafe(24) for k in ['MYSQL_ROOT_PASSWORD','DB_PASSWORD','ADMIN_INITIAL_PASSWORD']),encoding='utf-8')
records=[]
def command(args,log):
    with log.open('a',encoding='utf-8') as f:
        subprocess.run(args,cwd=root,check=True,stdout=f,stderr=subprocess.STDOUT)
for i in range(3):
    project='opslattice-timing-'+secrets.token_hex(4)
    subnet=101+i;port=18091+i
    login=private/f'login-{i}.sql'
    login.write_text((root/'deploy/003-container-login.sql').read_text(encoding='utf-8').replace('172.30.94.10',f'172.30.{subnet}.10'),encoding='utf-8')
    override=private/f'override-{i}.yaml'
    override.write_text(f'''services:
  mysql:
    volumes:
      - {json.dumps(str(pathlib.Path(a.schema).resolve()).replace(chr(92),'/')+':/docker-entrypoint-initdb.d/001-init.sql:ro')}
      - {json.dumps(str(login).replace(chr(92),'/')+':/docker-entrypoint-initdb.d/003-container-login.sql:ro')}
  backend:
    image: opslattice-benchmark-backend:local
  frontend:
    image: opslattice-benchmark-frontend:local
    ports: !override
      - '127.0.0.1:{port}:80'
    networks:
      ingress:
        ipv4_address: 172.30.{subnet}.10
networks:
  ingress:
    ipam:
      config: !override
        - subnet: 172.30.{subnet}.0/24
''',encoding='utf-8')
    base=[a.docker,'compose','-f',str(root/'compose.yaml'),'-f',str(override),'--env-file',str(env),'-p',project]
    command(base+['config','--quiet'],out/f'config-{i}.log')
    if i==0:
        begin=time.perf_counter();command(base+['--progress','plain','build'],out/'build.log');build_seconds=time.perf_counter()-begin
    try:
        begin=time.perf_counter();command(base+['up','-d','--no-build','--wait','--wait-timeout','300'],out/f'start-{i}.log');cold=time.perf_counter()-begin
        # Reuse the existing actual HTTP/SQL smoke checks, replacing only this test's addresses and paths.
        smoke=(root/'deploy/verify-local.py').read_text(encoding='utf-8')
        smoke=smoke.replace("BASE = 'http://127.0.0.1:18080'",f"BASE = 'http://127.0.0.1:{port}'")
        smoke=smoke.replace("STATE = Path('back/target/container-smoke-state.json')",f'STATE = Path({str(private/f"state-{i}.json")!r})')
        smoke=smoke.replace("[DOCKER, 'compose', '-p', PROJECT, *args]",repr(base)+' + list(args)')
        smoke=smoke.replace("Path('.env')",f'Path({str(env)!r})')
        script=private/f'smoke-{i}.py';script.write_text(smoke,encoding='utf-8')
        command([sys.executable,'-X','utf8',str(script)],out/f'smoke-{i}.log')
        command(base+['stop'],out/f'stop-{i}.log')
        begin=time.perf_counter();command(base+['start','--wait','--wait-timeout','300'],out/f'restart-{i}.log');restart=time.perf_counter()-begin
        command([sys.executable,'-X','utf8',str(script),'--verify-restart'],out/f'persistence-{i}.log')
        records.append({'project':project,'coldEmptyVolumeSeconds':cold,'existingDataRestartSeconds':restart,'httpSmoke':True,'databaseAndUploadsPersistence':True})
        (out/'samples.json').write_text(json.dumps({'buildSecondsExcluded':build_seconds,'runs':records},indent=2),encoding='utf-8')
        print(json.dumps(records[-1]),flush=True)
    finally:
        command(base+['stop'],out/f'final-stop-{i}.log')
print(out)
