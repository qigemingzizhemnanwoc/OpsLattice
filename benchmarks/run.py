"""Local-only benchmark runner. Own containers; no existing database or volume cleanup.
Requires compiled Maven test classes and their surefire classpath (run regression first).
"""
import argparse, hashlib, json, os, pathlib, secrets, subprocess, tempfile, time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--docker', required=True)
p.add_argument('--java-home', required=True)
p.add_argument('--schema', required=True, help='Reviewed schema/seed SQL; executed ONLY in newly owned container')
p.add_argument('--mode', choices=['employee', 'redis', 'stock'], required=True)
p.add_argument('--label', default='baseline')
a = p.parse_args()
out = ROOT / 'benchmarks/results' / (time.strftime('%Y%m%d-%H%M%S') + '-' + a.mode + '-' + a.label)
out.mkdir(parents=True)
build = ROOT / 'back/target/benchmark'
build.mkdir(exist_ok=True)
original = subprocess.check_output(['git', 'show', 'b0abf68:back/src/main/java/org/example/back/service/EmployeeService.java'], cwd=ROOT)
legacy = original.decode('utf-8').replace('public class EmployeeService {', 'public class LegacyEmployeeBenchmarkService {')
(build / 'LegacyEmployeeBenchmarkService.java').write_text(legacy, encoding='utf-8')
xml = next((ROOT / 'back/target/surefire-reports').glob('TEST-*.xml'))
cp = next(x.attrib['value'] for x in ET.parse(xml).findall('./properties/property') if x.attrib['name'] == 'java.class.path')
subprocess.run([str(pathlib.Path(a.java_home)/'bin/javac.exe'), '-encoding','UTF-8','-cp',cp,'-d',str(build),str(build/'LegacyEmployeeBenchmarkService.java'),str(ROOT/'benchmarks/PerformanceBenchmark.java')], check=True)
name = 'opslattice-bench-' + secrets.token_hex(5)
env = os.environ.copy()
env['MYSQL_ROOT_PASSWORD'] = secrets.token_urlsafe(30)
env['BENCH_PASSWORD'] = env['MYSQL_ROOT_PASSWORD']
env['BENCH_OUT'] = str(out)
env['BENCH_MODE'] = a.mode
env['BENCH_LABEL'] = a.label
def docker(*args, **kw):
    return subprocess.run([a.docker,*args], check=True, capture_output=True, **kw)
try:
    # Docker chooses an unused loopback host port. No host MySQL connection is used.
    if a.mode != 'redis':
        docker('run','-d','--name',name,'-p','127.0.0.1::3306','-e','MYSQL_ROOT_PASSWORD','mysql:8.0',env=env)
        env['BENCH_PORT'] = docker('port',name,'3306').stdout.decode().strip().rsplit(':',1)[1]
        for _ in range(120):
            r=subprocess.run([a.docker,'exec',name,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "SELECT 1"'],capture_output=True)
            if r.returncode == 0: break
            time.sleep(1)
        else: raise RuntimeError('Owned MySQL startup timed out')
        docker('exec','-i',name,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot',input=pathlib.Path(a.schema).read_bytes())
        version=docker('exec',name,'mysql','--version').stdout.decode().strip()
    else:
        docker('run','-d','--name',name,'-p','127.0.0.1::6379','redis:7-alpine','redis-server','--save','','--appendonly','no')
        env['BENCH_REDIS_PORT']=docker('port',name,'6379').stdout.decode().strip().rsplit(':',1)[1]
        version=docker('exec',name,'redis-server','--version').stdout.decode().strip()
    sources=['back/src/main/java/org/example/back/service/EmployeeService.java','back/src/main/resources/mapper/SysEmployeeMapper.xml','back/src/main/java/org/example/back/common/aspect/PreventDuplicateSubmitAspect.java','benchmarks/PerformanceBenchmark.java']
    meta={'historical_commit':'b0abf68','historical_service_sha256':hashlib.sha256(original).hexdigest(),'mode':a.mode,'label':a.label,'component':version,'container':name,'schema_sha256':hashlib.sha256(pathlib.Path(a.schema).read_bytes()).hexdigest(),'sources':{s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in sources}}
    (out/'environment.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
    with (out/'runner.log').open('w',encoding='utf-8') as log:
        result=subprocess.run([str(pathlib.Path(a.java_home)/'bin/java.exe'),'-Xms512m','-Xmx2g','-cp',str(build)+os.pathsep+cp,'org.example.back.service.PerformanceBenchmark'],env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode: raise RuntimeError('Benchmark failed; inspect runner.log (never counted as a passing run)')
    print(out)
finally:
    # Stop only our own container. Retain it and its data for review; never remove volumes.
    subprocess.run([a.docker,'stop',name],capture_output=True)
