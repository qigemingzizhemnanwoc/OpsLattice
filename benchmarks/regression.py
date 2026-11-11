"""Run the bounded regression once; credentials stay in process environment/private logs."""
import argparse,json,os,pathlib,socket,subprocess,tempfile,time
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--maven',required=True);p.add_argument('--java-home',required=True);p.add_argument('--redis-server',required=True);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
env=os.environ.copy()
properties=dict(line.split('=',1) for line in (root/'back/integration.local').read_text().splitlines() if line and not line.startswith('#'))
url=properties['url'].replace('\\:',':').replace('\\=','=')
assert url.split('?')[0]=='jdbc:mysql://localhost:3306/warehouse_management_integration_test'
env.update(PURCHASE_TEST_JDBC_URL=url,PURCHASE_TEST_USERNAME=properties['username'],PURCHASE_TEST_PASSWORD=properties['password'].replace('\\:',':').replace('\\=','='),JAVA_HOME=a.java_home,REDIS_TEST_SERVER=a.redis_server)
env['PATH']=str(pathlib.Path(a.java_home)/'bin')+os.pathsep+env['PATH']
with socket.socket() as probe: assert probe.connect_ex(('127.0.0.1',16379))!=0,'Port occupied; existing process is not ours'
private=pathlib.Path(tempfile.mkdtemp(prefix='opslattice-regression-'))
tests='ContainerAdminInitializerTest,RedisDuplicateIntegrationTest,EmployeeAcceptanceTest,EmployeeDirectoryIntegrationTest,EmployeeReadOnlyTest,EmployeeValidationTest,EmployeeServiceTest,AuthServiceTest,UserManageServiceTest,SalesConcurrencyIntegrationTest,PurchaseStockFlowIntegrationTest,*DeleteTest,PurchaseServiceTest'
started=time.time()
with (private/'redis.log').open('w') as log:
    owned=subprocess.Popen([a.redis_server,'--bind','127.0.0.1','--port','16379','--save','','--appendonly','no'],stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        time.sleep(.5);assert owned.poll() is None
        with (private/'maven.log').open('w',encoding='utf-8') as output:
            run=subprocess.run([a.maven,'-o','-f',str(root/'back/pom.xml'),'-Dtest='+tests,'test'],cwd=root,env=env,stdout=output,stderr=subprocess.STDOUT)
        suites=[]
        for file in (root/'back/target/surefire-reports').glob('TEST-*.xml'):
            if file.stat().st_mtime<started:continue
            e=ET.parse(file).getroot();suites.append({k:e.attrib[k] for k in ['name','tests','failures','errors','skipped','time']})
        summary={'exitCode':run.returncode,'suites':suites,'totals':{k:sum(int(s[k]) for s in suites) for k in ['tests','failures','errors','skipped']}}
        out=root/'benchmarks/results'/('regression-'+time.strftime('%Y%m%d-%H%M%S')+'.json');out.write_text(json.dumps(summary,indent=2),encoding='utf-8')
        print(json.dumps(summary['totals']));print('Private diagnostic log:',private/'maven.log');print(out)
        if run.returncode:raise SystemExit(run.returncode)
    finally:
        owned.terminate();owned.wait(timeout=10)
