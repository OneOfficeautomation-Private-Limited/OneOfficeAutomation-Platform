"""Stand-in for the ODBC helper in tests: the same JSON-lines protocol, backed by SQLite (which also uses ? parameters)."""
import base64, json, re, sqlite3, sys

# the bridge speaks UTF-8 both ways; Windows would otherwise read and write the pipes in the ANSI code page
sys.stdin.reconfigure(encoding='utf-8')
sys.stdout.reconfigure(encoding='utf-8')

conn = None
in_tx = False

def val(v):
    if isinstance(v, dict):
        if v.get('t') == 'bytes': return base64.b64decode(v['v'])
        return v.get('v')
    return v

def cell(v):
    if isinstance(v, bytes): return base64.b64encode(v).decode()
    if isinstance(v, float): return repr(v)
    return v

def kind(values):
    for v in values:
        if v is None: continue
        if isinstance(v, bool): return 'Boolean'
        if isinstance(v, int): return 'Int64'
        if isinstance(v, float): return 'Double'
        if isinstance(v, bytes): return 'Byte[]'
        return 'String'
    return 'String'

def rows(cur, maxrows):
    names = [d[0] for d in cur.description]
    data = cur.fetchall()
    if maxrows: data = data[:maxrows]
    cols = [{'name': n, 'type': kind([r[i] for r in data]), 'db': 'TEXT'} for i, n in enumerate(names)]
    return {'ok': True, 'kind': 'rows', 'columns': cols, 'rows': [[cell(v) for v in r] for r in data]}

def table(names, data):
    return {'ok': True, 'kind': 'rows', 'columns': [{'name': n, 'type': 'String', 'db': 'VARCHAR'} for n in names], 'rows': data}

for line in sys.stdin:
    stop = False
    try:
        r = json.loads(line)
        op = r['op']
        if op == 'open':
            m = re.search(r'(?i)database=([^;]+)', r['cs'])
            if 'Driver={Missing}' in r['cs']: raise Exception('ERROR [IM002] [Microsoft][ODBC Driver Manager] Data source name not found and no default driver specified')
            conn = sqlite3.connect(m.group(1), isolation_level=None)
            resp = {'ok': True, 'driver': 'fake.dll', 'version': sqlite3.sqlite_version, 'database': m.group(1), 'bits': 64}
        elif op in ('query', 'run'):
            cur = conn.execute(r['sql'], [val(p) for p in r.get('params') or []])
            resp = rows(cur, r.get('max') or 0) if cur.description else {'ok': True, 'kind': 'count', 'count': cur.rowcount}
        elif op == 'exec':
            cur = conn.execute(r['sql'], [val(p) for p in r.get('params') or []])
            resp = {'ok': True, 'kind': 'count', 'count': cur.rowcount}
        elif op == 'batch':
            resp = {'ok': True, 'counts': [conn.execute(r['sql'], [val(p) for p in ps]).rowcount for ps in r['rows']]}
        elif op == 'begin':
            conn.execute('BEGIN'); in_tx = True; resp = {'ok': True}
        elif op in ('commit', 'rollback'):
            if in_tx: conn.execute(op.upper()); in_tx = False
            resp = {'ok': True}
        elif op == 'tables':
            resp = table(['TABLE_CAT', 'TABLE_SCHEM', 'TABLE_NAME', 'TABLE_TYPE'],
                         [[None, None, n, 'TABLE' if t == 'table' else 'VIEW'] for n, t in conn.execute("SELECT name, type FROM sqlite_master WHERE type IN ('table','view') ORDER BY name")])
        elif op == 'columns':
            resp = table(['TABLE_NAME', 'COLUMN_NAME', 'TYPE_NAME', 'COLUMN_SIZE', 'NULLABLE', 'ORDINAL_POSITION'],
                         [[r['table'], c[1], c[2], None, 0 if c[3] else 1, c[0] + 1] for c in conn.execute('PRAGMA table_info(%s)' % r['table'])])
        elif op == 'close':
            if conn: conn.close()
            resp = {'ok': True}; stop = True
        else:
            resp = {'ok': False, 'error': 'unknown operation ' + op}
    except Exception as e:
        resp = {'ok': False, 'error': str(e)}
    sys.stdout.write('@@' + json.dumps(resp) + '\n'); sys.stdout.flush()
    if stop: break
