"""Local Compose smoke test only. Keeps its synthetic records for restart verification.
Run from repository root; DOCKER_EXE optionally selects the Docker executable.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = 'http://127.0.0.1:18080'
PROJECT = 'ecs-stage4-20261106'
DOCKER = os.environ.get('DOCKER_EXE', 'docker')
STATE = Path('back/target/container-smoke-state.json')
token = None

def request(path, body=None, method=None):
    headers = {'Content-Type': 'application/json', 'Origin': BASE}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return json.load(error)

def ok(path, body=None, method=None):
    result = request(path, body, method)
    assert result.get('code') == 200, (path, result.get('code'), result.get('msg'))
    return result.get('data')

def compose(*args, input=None):
    result = subprocess.run([DOCKER, 'compose', '-p', PROJECT, *args], input=input, text=True, encoding='utf-8', stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert result.returncode == 0, 'Compose verification command failed'
    return result.stdout.strip()

def sql(query):
    return compose('exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" -N -B warehouse_management', input=query)

with urllib.request.urlopen(BASE, timeout=15) as page:
    assert page.status == 200 and b'<html' in page.read().lower()
assert request('/api/health/ready')['status'] == 'UP'
assert request('/api/system/employees/page').get('code') == 401
settings = dict(line.split('=', 1) for line in Path('.env').read_text().splitlines() if line and not line.startswith('#'))
login = ok('/api/auth/login', {'username': 'superadmin', 'password': settings['ADMIN_INITIAL_PASSWORD']})
token = login['token']
ok('/api/auth/userinfo')
ok('/api/system/employees/page?pageNum=1&pageSize=5')
assert sql('SELECT COUNT(*) FROM sys_user WHERE username<>\'superadmin\' AND status=1;') == '0'

if '--verify-restart' in sys.argv:
    state = json.loads(STATE.read_text())
    assert sql(f"SELECT stock FROM base_goods WHERE id={state['goodsId']};") == '10'
    assert sql(f"SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id={state['goodsId']};") == '2'
    assert compose('exec', '-T', 'backend', 'cat', '/app/uploads/stage4-verification.txt') == state['marker']
    print('PASS: restart preserves admin login, database records, stock flows and uploads volume')
else:
    marker = 'stage4-' + uuid.uuid4().hex
    ok('/api/base/goods', {'goodsName': marker, 'supplierId': 1, 'purchasePrice': 10, 'salePrice': 20, 'stock': 10, 'unit': 'piece', 'status': 1})
    goods_id = int(sql(f"SELECT id FROM base_goods WHERE goods_name='{marker}';"))
    body = {'goodsId': goods_id, 'quantity': 2, 'unitPrice': 20, 'remark': marker}
    ok('/api/business/sales', body)
    duplicate = request('/api/business/sales', body)
    assert duplicate.get('code') != 200, 'Duplicate sale unexpectedly passed'
    assert sql(f'SELECT stock FROM base_goods WHERE id={goods_id};') == '8'
    sale_id = int(sql(f'SELECT id FROM biz_sales WHERE goods_id={goods_id};'))
    assert sql(f'SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id={goods_id};') == '1'
    ok(f'/api/business/sales/{sale_id}', method='DELETE')
    assert sql(f'SELECT stock FROM base_goods WHERE id={goods_id};') == '10'
    assert sql(f'SELECT SUM(change_quantity) FROM biz_stock_flow WHERE goods_id={goods_id};') == '0'
    assert sql(f'SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id={goods_id};') == '2'
    compose('exec', '-T', 'backend', 'sh', '-c', 'printf %s "$1" > /app/uploads/stage4-verification.txt', 'sh', marker)
    STATE.write_text(json.dumps({'goodsId': goods_id, 'marker': marker}), encoding='utf-8')
    print('PASS: SPA, proxy, readiness, login, authorization, employee query, sale dedup, stock reversal and flow reconciliation')
