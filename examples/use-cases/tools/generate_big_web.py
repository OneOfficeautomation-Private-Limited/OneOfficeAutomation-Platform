"""Writes the five larger web processes W6-W10: their demo web applications (site/), data and agents.

Each demo application is one HTML page with several screens (no server needed). The agents follow a whole business
process across those screens: rules, loops, waits, pop-ups, errors, downloads and reports.
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def write(rel, text):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


class Flow:
    def __init__(self, fid, name, description):
        self.fid, self.name, self.description, self.vars, self.n = fid, name, description, [], 0

    def var(self, name, typ, direction='local', default=None, description=''):
        v = {'name': name, 'type': typ, 'direction': direction}
        if default is not None:
            v['default'] = default
        if description:
            v['description'] = description
        self.vars.append(v)
        return self

    def s(self, action, args=None, out=None, **extra):
        self.n += 1
        st = {'id': 's%d' % self.n, 'action': action}
        if args:
            st['args'] = args
        if out:
            st['out'] = out
        st.update(extra)
        return st

    def save(self, steps, file):
        write(file, json.dumps({'$schema': 'https://schemas.oneofficeautomation.com/flow/v1.json', 'kind': 'TaskBot', 'id': self.fid, 'name': self.name,
                                'description': self.description, 'variables': self.vars, 'steps': steps}, indent=2, ensure_ascii=False) + '\n')


def common(f):
    f.var('folder', 'String', 'input', 'Use cases', 'Folder of these use cases, relative to the workspace.')
    f.var('headless', 'Boolean', 'input', False, 'Run the browser without a window (for unattended runs).')


def open_site(f, page):
    return f.s('ooa.browser/open', {'url': '${folder}/site/' + page, 'headless': '$headless', 'engine': 'playwright', 'keep_open': False}, description='Open ' + page)


def exists(sel):
    return {'condition': 'ooa.browser/element_exists', 'args': {'selector': sel}}


def visible(sel):
    return {'condition': 'ooa.browser/element_visible', 'args': {'selector': sel}}


def add(var, by='1'):
    return {'variable': var, 'value': {'expr': '$%s + %s' % (var, by)}}


STYLE = '''<style>
body{font:15px system-ui,"Segoe UI",sans-serif;margin:0;color:#1b2230;background:#f4f6fa}header{background:#14325c;color:#fff;padding:12px 24px;display:flex;gap:16px;align-items:center}
header h1{font-size:18px;margin:0;flex:1}main{padding:20px 24px;max-width:980px}.card{background:#fff;border:1px solid #d6dbe5;border-radius:10px;padding:16px 18px;margin-bottom:14px}
label{display:block;margin:10px 0 4px;font-weight:600}input,select,textarea{padding:6px 8px;width:300px;font:inherit}textarea{height:60px}button{margin:10px 8px 0 0;padding:7px 14px;cursor:pointer}
table{border-collapse:collapse;width:100%;background:#fff}td,th{border-bottom:1px solid #e3e6ee;padding:7px 10px;text-align:left}.hidden{display:none!important}
.error{color:#b42318;font-weight:600}.ok{color:#067647;font-weight:700}#toast{position:fixed;right:20px;bottom:20px;background:#14325c;color:#fff;padding:8px 14px;border-radius:8px}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.35);display:grid;place-items:center}.modal>div{background:#fff;padding:18px 20px;border-radius:12px;width:420px}
nav button.on{background:#14325c;color:#fff}
</style>'''

# ======================================================================================== W6 invoice approval portal
write('site/invoice-approvals.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Invoice approvals</title>''' + STYLE + '''</head><body>
<header><h1>Accounts payable: invoice approvals</h1><span id="who"></span></header><main>
<section id="login-view" class="card"><label for="user">User</label><input id="user"><label for="pass">Password</label><input id="pass" type="password"><br><button id="login">Sign in</button>
<p id="login-error" class="error hidden">Enter your user and password.</p></section>
<section id="queue-view" class="hidden">
 <div class="card">Approved <b id="count-approved">0</b> &middot; Rejected <b id="count-rejected">0</b> &middot; Escalated <b id="count-escalated">0</b></div>
 <div class="card"><label for="status-filter">Show</label><select id="status-filter"><option>All</option><option>Pending</option><option>Approved</option><option>Rejected</option><option>Escalated</option></select>
 <table id="queue"><thead><tr><th>Invoice</th><th>Supplier</th><th>Amount</th><th>Status</th><th></th></tr></thead><tbody></tbody></table>
 <p id="empty" class="hidden">Nothing to review.</p>
 <button id="prev-page">Previous</button><span id="page-info"></span><button id="next-page">Next</button></div>
</section>
<section id="detail-view" class="card hidden">
 <h2>Invoice <span id="d-invoice"></span></h2><p>Supplier: <span id="d-supplier"></span></p><p>Amount: <span id="d-amount" data-value=""></span></p><p>Purchase order: <span id="d-po"></span></p>
 <label for="comment">Comment</label><textarea id="comment"></textarea><br>
 <button id="approve">Approve</button><button id="reject">Reject</button><button id="escalate">Escalate</button><button id="back">Back</button>
 <p id="detail-error" class="error hidden">Write a comment first.</p>
</section></main><div id="toast" class="hidden"></div>
<script>
const invoices = [["INV-2001","Acme",1200,"PO-77"],["INV-2002","Globex",8400,"PO-78"],["INV-2003","Initech",300,""],["INV-2004","Umbrella",4999,"PO-80"],
  ["INV-2005","Hooli",5000,"PO-81"],["INV-2006","Stark",15000,""],["INV-2007","Wayne",760,"PO-83"]].map(([id,s,a,po]) => ({id, supplier:s, amount:a, po, status:"Pending", comment:""}));
let page = 0, current = null;
const $ = (id) => document.getElementById(id), size = 4, fmt = (n) => n.toLocaleString("en-US", {minimumFractionDigits: 2});
function show(v) { for (const s of ["login-view","queue-view","detail-view"]) $(s).classList.toggle("hidden", s !== v); }
function render() {
  const f = $("status-filter").value, list = invoices.filter((i) => f === "All" || i.status === f), pages = Math.max(1, Math.ceil(list.length / size));
  page = Math.min(page, pages - 1);
  $("queue").querySelector("tbody").innerHTML = list.slice(page * size, page * size + size).map((i) =>
    "<tr><td>" + i.id + "</td><td>" + i.supplier + "</td><td>" + fmt(i.amount) + "</td><td>" + i.status + "</td><td><button class='open' data-id='" + i.id + "'>Open</button></td></tr>").join("");
  $("empty").classList.toggle("hidden", list.length > 0);
  $("page-info").textContent = " page " + (page + 1) + " of " + pages + " ";
  for (const s of ["Approved","Rejected","Escalated"]) $("count-" + s.toLowerCase()).textContent = invoices.filter((i) => i.status === s).length;
  for (const b of document.querySelectorAll("button.open")) b.onclick = () => open(b.dataset.id);
}
function open(id) {
  current = invoices.find((i) => i.id === id);
  $("d-invoice").textContent = current.id; $("d-supplier").textContent = current.supplier; $("d-amount").textContent = fmt(current.amount);
  $("d-amount").dataset.value = current.amount; $("d-po").textContent = current.po; $("comment").value = ""; $("detail-error").classList.add("hidden");
  setTimeout(() => show("detail-view"), 250);
}
function decide(status) {
  if (!$("comment").value.trim()) { $("detail-error").classList.remove("hidden"); return; }
  current.status = status; current.comment = $("comment").value;
  $("toast").textContent = current.id + " " + status.toLowerCase(); $("toast").classList.remove("hidden");
  setTimeout(() => { show("queue-view"); render(); }, 300);
}
$("login").onclick = () => { if ($("user").value && $("pass").value) { $("who").textContent = $("user").value; show("queue-view"); render(); } else $("login-error").classList.remove("hidden"); };
$("status-filter").onchange = () => { page = 0; render(); };
$("next-page").onclick = () => { page++; render(); }; $("prev-page").onclick = () => { page = Math.max(0, page - 1); render(); };
$("approve").onclick = () => decide("Approved"); $("reject").onclick = () => decide("Rejected"); $("escalate").onclick = () => decide("Escalated");
$("back").onclick = () => { show("queue-view"); render(); };
</script></body></html>
''')

f = Flow('uc_w6_invoice_approval', 'W6 Invoice approval process',
         'Sign in to the approval portal, work through the queue of pending invoices, apply the approval rules to each one and report the decisions.')
common(f)
f.var('portal_user', 'String', 'input', 'ap.clerk', 'Portal user.')
f.var('portal_password', 'String', 'input', 'demo-pass', 'Password of the demo portal (it accepts any). In a real process make this a Credential from the vault.')
f.var('limit', 'Number', 'input', 5000, 'Invoices above this amount go to a manager (escalated).')
f.var('inv', 'String').var('supplier', 'String').var('po', 'String').var('amount_text', 'String').var('amount', 'Number')
f.var('decision', 'String').var('reason', 'String')
f.var('decisions', 'Table', default={'columns': ['Invoice', 'Supplier', 'Amount', 'Decision', 'Reason'], 'rows': []})
f.var('processed', 'Number', 'output', 0, 'Invoices decided by the agent.')
f.var('approved', 'Number', 'output', 0, 'Approved, as counted by the portal.')
f.var('rejected', 'Number', 'output', 0, 'Rejected, as counted by the portal.')
f.var('escalated', 'Number', 'output', 0, 'Escalated, as counted by the portal.')
f.save([
    open_site(f, 'invoice-approvals.html'),
    f.s('ooa.browser/type_text', {'selector': '#user', 'text': '$portal_user'}),
    f.s('ooa.browser/type_text', {'selector': '#pass', 'text': '$portal_password'}),
    f.s('ooa.browser/click', {'selector': '#login'}),
    f.s('ooa.browser/wait_for', {'selector': '#queue-view', 'state': 'visible'}),
    f.s('ooa.browser/select_option', {'selector': '#status-filter', 'option': 'Pending'}, description='Show only pending invoices'),
    f.s('core/loop', {'iterator': 'while', 'condition': exists('#queue tbody tr'), 'max_iterations': 50}, description='Until the queue is empty', children=[
        f.s('ooa.browser/click', {'selector': '#queue tbody tr:first-child button.open'}, description='Open the next pending invoice'),
        f.s('ooa.browser/wait_for', {'selector': '#detail-view', 'state': 'visible'}),
        f.s('ooa.browser/get_text', {'selector': '#d-invoice'}, {'text': 'inv'}),
        f.s('ooa.browser/get_text', {'selector': '#d-supplier'}, {'text': 'supplier'}),
        f.s('ooa.browser/get_text', {'selector': '#d-po'}, {'text': 'po'}),
        f.s('ooa.browser/get_attribute', {'selector': '#d-amount', 'attribute': 'data-value'}, {'value': 'amount_text'}),
        f.s('core/set', {'variable': 'amount', 'value': {'expr': 'toNumber($amount_text)'}}),
        f.s('core/if', {'condition': {'op': 'empty', 'left': '$po'}}, description='Approval rules',
            children=[f.s('core/set', {'variable': 'decision', 'value': 'reject'}), f.s('core/set', {'variable': 'reason', 'value': 'Missing purchase order'})],
            elseIf=[{'condition': {'op': 'gt', 'left': '$amount', 'right': '$limit'}, 'children': [
                f.s('core/set', {'variable': 'decision', 'value': 'escalate'}), f.s('core/set', {'variable': 'reason', 'value': 'Above the approval limit of ${limit}'})]}],
            **{'else': [f.s('core/set', {'variable': 'decision', 'value': 'approve'}), f.s('core/set', {'variable': 'reason', 'value': 'Within limit, matches ${po}'})]}),
        f.s('ooa.browser/type_text', {'selector': '#comment', 'text': '$reason'}),
        f.s('ooa.browser/click', {'selector': '#${decision}'}),
        f.s('ooa.browser/wait_for', {'selector': '#queue-view', 'state': 'visible'}),
        f.s('ooa.datatable/add_row', {'table': '$decisions', 'values': ['$inv', '$supplier', '$amount', '$decision', '$reason']}, {'table': 'decisions'}),
        f.s('core/set', add('processed')),
    ]),
    f.s('ooa.browser/get_text', {'selector': '#count-approved'}, {'text': 'approved'}),
    f.s('ooa.browser/get_text', {'selector': '#count-rejected'}, {'text': 'rejected'}),
    f.s('ooa.browser/get_text', {'selector': '#count-escalated'}, {'text': 'escalated'}),
    f.s('ooa.browser/close'),
    f.s('ooa.csv/write', {'table': '$decisions', 'path': '${folder}/output/invoice-decisions.csv'}),
], 'W6-invoice-approval-process.flow.json')

# ======================================================================================== W7 supplier onboarding wizard
write('site/supplier-onboarding.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Supplier onboarding</title>''' + STYLE + '''</head><body>
<header><h1>Supplier onboarding</h1><button id="start-over">Start over</button></header><main>
<section id="step1" class="card"><h2>1. Company</h2><label for="company">Company name</label><input id="company"><label for="country">Country</label>
<select id="country"><option value="">Choose...</option><option>Germany</option><option>Spain</option><option>Netherlands</option><option>Norway</option></select>
<label for="vat">VAT number</label><input id="vat"><br><button id="next1">Next</button><p id="error1" class="error hidden"></p></section>
<section id="step2" class="card hidden"><h2>2. Bank account</h2><label for="iban">IBAN</label><input id="iban"><label for="bic">BIC</label><input id="bic"><br>
<button id="next2">Next</button><p id="error2" class="error hidden"></p></section>
<section id="step3" class="card hidden"><h2>3. Contact</h2><label for="contact-name">Contact name</label><input id="contact-name"><label for="contact-email">Email</label><input id="contact-email" type="email">
<label><input id="terms" type="checkbox" style="width:auto"> The supplier accepts the purchasing terms</label><button id="submit">Submit</button><p id="error3" class="error hidden"></p></section>
<section id="done" class="card hidden"><h2>Supplier created</h2><p>Supplier number: <span id="supplier-id" class="ok"></span></p><button id="new">Onboard another supplier</button></section>
</main><script>
const $ = (id) => document.getElementById(id); let n = 1000;
function step(s) { for (const x of ["step1","step2","step3","done"]) $(x).classList.toggle("hidden", x !== s); }
function reset() { for (const id of ["company","vat","iban","bic","contact-name","contact-email"]) $(id).value = ""; $("country").value = ""; $("terms").checked = false;
  for (const e of ["error1","error2","error3"]) $(e).classList.add("hidden"); step("step1"); }
function fail(id, msg) { $(id).textContent = msg; $(id).classList.remove("hidden"); }
$("next1").onclick = () => { $("error1").classList.add("hidden");
  if (!$("company").value.trim() || !$("country").value) return fail("error1", "Company and country are required");
  if (!/^[A-Z]{2}[0-9]{8,10}$/.test($("vat").value.trim())) return fail("error1", "VAT number is not valid");
  setTimeout(() => step("step2"), 200); };
$("next2").onclick = () => { $("error2").classList.add("hidden");
  const iban = $("iban").value.replace(/\\s/g, ""); if (iban.length < 15 || !/^[A-Z]{2}[0-9]{2}/.test(iban)) return fail("error2", "IBAN is not valid");
  if (!/^[A-Z0-9]{8}([A-Z0-9]{3})?$/.test($("bic").value.trim())) return fail("error2", "BIC is not valid");
  setTimeout(() => step("step3"), 200); };
$("submit").onclick = () => { $("error3").classList.add("hidden");
  if (!$("contact-email").value.includes("@")) return fail("error3", "Enter the contact's email");
  if (!$("terms").checked) return fail("error3", "The terms must be accepted");
  n++; $("supplier-id").textContent = "SUP-" + n; setTimeout(() => step("done"), 400); };
$("new").onclick = reset; $("start-over").onclick = reset;
</script></body></html>
''')
write('data/suppliers.csv', '''Company,Country,VAT,IBAN,BIC,Contact,Email
Nordwind GmbH,Germany,DE123456789,DE89370400440532013000,COBADEFFXXX,Lena Vogel,lena@nordwind.example
Sol y Mar SL,Spain,ES12345,ES9121000418450200051332,CAIXESBBXXX,Ana Ruiz,ana@solymar.example
Polder BV,Netherlands,NL123456789,NL91ABNA041716,ABNANL2A,Jan de Vries,jan@polder.example
Fjord AS,Norway,NO987654321,NO9386011117947,DNBANOKK,Ola Nordmann,ola@fjord.example
''')

f = Flow('uc_w7_supplier_onboarding', 'W7 Supplier onboarding wizard',
         'Take each supplier from a CSV file through a three-step onboarding wizard; suppliers the portal refuses are logged with the reason; the results go to Excel.')
common(f)
f.var('suppliers', 'Table').var('sup', 'Dictionary').var('msg', 'String').var('err', 'String').var('sid', 'String')
f.var('results', 'Table', default={'columns': ['Company', 'Supplier number', 'Result', 'Reason'], 'rows': []})
f.var('onboarded', 'Number', 'output', 0, 'Suppliers created.')
f.var('failed', 'Number', 'output', 0, 'Suppliers refused by the portal.')
f.var('last_id', 'String', 'output', '', 'Last supplier number.')
f.save([
    f.s('ooa.csv/read', {'path': '${folder}/data/suppliers.csv'}, {'table': 'suppliers'}),
    open_site(f, 'supplier-onboarding.html'),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$suppliers', 'as': 'sup'}, description='Each supplier', children=[
        f.s('core/try', description='Onboard one supplier', children=[
            f.s('ooa.browser/type_text', {'selector': '#company', 'text': '$sup.Company'}),
            f.s('ooa.browser/select_option', {'selector': '#country', 'option': '$sup.Country'}),
            f.s('ooa.browser/type_text', {'selector': '#vat', 'text': '$sup.VAT'}),
            f.s('ooa.browser/click', {'selector': '#next1'}),
            f.s('core/delay', {'seconds': 300, 'unit': 'milliseconds'}),
            f.s('core/if', {'condition': visible('#error1')}, children=[
                f.s('ooa.browser/get_text', {'selector': '#error1'}, {'text': 'msg'}),
                f.s('core/throw', {'code': 'BUSINESS.REFUSED', 'message': '$msg'})]),
            f.s('ooa.browser/wait_for', {'selector': '#step2', 'state': 'visible'}),
            f.s('ooa.browser/type_text', {'selector': '#iban', 'text': '$sup.IBAN'}),
            f.s('ooa.browser/type_text', {'selector': '#bic', 'text': '$sup.BIC'}),
            f.s('ooa.browser/click', {'selector': '#next2'}),
            f.s('core/delay', {'seconds': 300, 'unit': 'milliseconds'}),
            f.s('core/if', {'condition': visible('#error2')}, children=[
                f.s('ooa.browser/get_text', {'selector': '#error2'}, {'text': 'msg'}),
                f.s('core/throw', {'code': 'BUSINESS.REFUSED', 'message': '$msg'})]),
            f.s('ooa.browser/wait_for', {'selector': '#step3', 'state': 'visible'}),
            f.s('ooa.browser/type_text', {'selector': '#contact-name', 'text': '$sup.Contact'}),
            f.s('ooa.browser/type_text', {'selector': '#contact-email', 'text': '$sup.Email'}),
            f.s('ooa.browser/element_action', {'selector': '#terms', 'action': 'check'}),
            f.s('ooa.browser/click', {'selector': '#submit'}),
            f.s('ooa.browser/wait_for', {'selector': '#done', 'state': 'visible'}),
            f.s('ooa.browser/get_text', {'selector': '#supplier-id'}, {'text': 'sid'}),
            f.s('ooa.datatable/add_row', {'table': '$results', 'values': ['$sup.Company', '$sid', 'Onboarded', '']}, {'table': 'results'}),
            f.s('core/set', add('onboarded')),
            f.s('core/set', {'variable': 'last_id', 'value': '$sid'}),
            f.s('ooa.browser/click', {'selector': '#new'}),
        ], catch=[{'codes': ['BUSINESS.*'], 'message_to': 'err', 'children': [
            f.s('ooa.datatable/add_row', {'table': '$results', 'values': ['$sup.Company', '', 'Refused', '$err']}, {'table': 'results'}),
            f.s('core/set', add('failed')),
            f.s('ooa.browser/click', {'selector': '#start-over'})]}]),
    ]),
    f.s('ooa.browser/close'),
    f.s('ooa.excel/create_workbook', {'path': '${folder}/output/supplier-onboarding.xlsx', 'sheet': 'Onboarding', 'overwrite': True, 'session': 'report'}),
    f.s('ooa.excel/write_table', {'table': '$results', 'session': 'report'}),
    f.s('ooa.excel/close', {'session': 'report', 'save': True}),
], 'W7-supplier-onboarding-wizard.flow.json')

# ======================================================================================== W8 price comparison over three shops
shops = {
    'a': ('Shop A', [('Desk lamp', '€24.99'), ('Office chair', '€149.00'), ('USB-C hub', '€39.90'), ('Monitor arm', '€59.00')]),
    'b': ('Shop B', [('Desk lamp', '23,49 EUR'), ('Office chair', '155,00 EUR'), ('USB-C hub', '41,00 EUR')]),
    'c': ('Shop C', [('Desk lamp', '25.00'), ('Office chair', '139.90')]),
}
write('site/shop-a.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Shop A</title>''' + STYLE + '''</head><body><header><h1>Shop A</h1></header><main class="card">
<input id="search" placeholder="Search"> <button id="go">Search</button><div id="out"></div></main><script>
const items = ''' + json.dumps(shops['a'][1]) + ''';
document.getElementById("go").onclick = () => { const q = document.getElementById("search").value.trim().toLowerCase(), out = document.getElementById("out");
  out.innerHTML = "Searching..."; setTimeout(() => { const hits = items.filter(([n]) => n.toLowerCase().includes(q));
  out.innerHTML = hits.length ? "<table id='results'><thead><tr><th>Name</th><th>Price</th></tr></thead><tbody>" + hits.map(([n, p]) => "<tr><td>" + n + "</td><td>" + p + "</td></tr>").join("") + "</tbody></table>" : "<p id='none'>No products found.</p>"; }, 300); };
</script></body></html>
''')
write('site/shop-b.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Shop B</title>''' + STYLE + '''<style>.product{display:inline-block;border:1px solid #ccd;border-radius:8px;padding:10px;margin:8px}</style></head><body>
<header><h1>Shop B</h1></header><main class="card"><form onsubmit="find(); return false"><input name="query" placeholder="What are you looking for?"></form><div id="grid"></div></main><script>
const items = ''' + json.dumps(shops['b'][1]) + ''';
function find() { const q = document.querySelector("input[name=query]").value.trim().toLowerCase(), g = document.getElementById("grid"); g.innerHTML = "";
  setTimeout(() => { const hits = items.filter(([n]) => n.toLowerCase().includes(q));
  g.innerHTML = hits.length ? hits.map(([n, p]) => "<div class='product'><div class='title'>" + n + "</div><div class='price'>" + p + "</div></div>").join("") : "<p class='no-results'>Nothing matches your search.</p>"; }, 300); }
</script></body></html>
''')
write('site/shop-c.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Shop C</title>''' + STYLE + '''</head><body><header><h1>Shop C</h1></header><main class="card">
<input id="find"> <button id="find-btn">Find</button><ul id="hits"></ul><p class="empty hidden">No match.</p></main><script>
const items = ''' + json.dumps(shops['c'][1]) + ''';
document.getElementById("find-btn").onclick = () => { const q = document.getElementById("find").value.trim().toLowerCase();
  setTimeout(() => { const hits = items.filter(([n]) => n.toLowerCase().includes(q));
  document.getElementById("hits").innerHTML = hits.map(([n, p]) => "<li data-price='" + p + "'>" + n + "</li>").join("");
  document.querySelector(".empty").classList.toggle("hidden", hits.length > 0); }, 300); };
</script></body></html>
''')
write('data/price-products.csv', 'Product\nDesk lamp\nOffice chair\nUSB-C hub\n')
write('data/last-prices.csv', 'Product,LastBest\nDesk lamp,24.99\nOffice chair,139.90\nUSB-C hub,35.00\n')

f = Flow('uc_w8_price_comparison', 'W8 Price comparison across three shops',
         'Search every product in three web shops with different page layouts, keep the cheapest offer, compare it with last week and report the changes.')
common(f)
f.var('products', 'Table').var('last', 'Table').var('p', 'Dictionary').var('shop', 'String').var('raw', 'String').var('clean', 'String')
f.var('price', 'Number').var('best', 'Number').var('best_shop', 'String').var('found', 'Boolean').var('at', 'Number').var('previous', 'Number').var('changed', 'Boolean')
f.var('shops', 'List', default=['Shop A', 'Shop B', 'Shop C'])
f.var('report', 'Table', default={'columns': ['Product', 'Cheapest shop', 'Best price', 'Last best', 'Changed'], 'rows': []})
f.var('compared', 'Number', 'output', 0, 'Products compared.')
f.var('changes', 'Number', 'output', 0, 'Products whose best price changed.')
f.var('total_best', 'Number', 'output', 0, 'Sum of the best prices.')
f.var('chair_shop', 'String', 'output', '', 'Cheapest shop for the office chair.')
f.save([
    f.s('ooa.csv/read', {'path': '${folder}/data/price-products.csv'}, {'table': 'products'}),
    f.s('ooa.csv/read', {'path': '${folder}/data/last-prices.csv', 'detect_types': True}, {'table': 'last'}),
    open_site(f, 'shop-a.html'),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$products', 'as': 'p'}, description='Each product', children=[
        f.s('core/set', {'variable': 'best', 'value': 999999}),
        f.s('core/set', {'variable': 'best_shop', 'value': ''}),
        f.s('core/loop', {'iterator': 'list', 'over': '$shops', 'as': 'shop'}, description='Each shop', children=[
            f.s('core/set', {'variable': 'raw', 'value': ''}),
            f.s('core/if', {'condition': {'op': 'eq', 'left': '$shop', 'right': 'Shop A'}}, description='Each shop has its own page layout', children=[
                f.s('ooa.browser/navigate', {'url': '${folder}/site/shop-a.html'}),
                f.s('ooa.browser/type_text', {'selector': '#search', 'text': '$p.Product'}),
                f.s('ooa.browser/click', {'selector': '#go'}),
                f.s('ooa.browser/wait_for', {'selector': '#results tbody tr, #none', 'state': 'visible'}),
                f.s('core/if', {'condition': exists('#results tbody tr')}, children=[
                    f.s('ooa.browser/get_text', {'selector': '#results tbody tr:first-child td:nth-child(2)'}, {'text': 'raw'})])],
                elseIf=[{'condition': {'op': 'eq', 'left': '$shop', 'right': 'Shop B'}, 'children': [
                    f.s('ooa.browser/navigate', {'url': '${folder}/site/shop-b.html'}),
                    f.s('ooa.browser/type_text', {'selector': 'input[name=query]', 'text': '$p.Product', 'press_enter': True}),
                    f.s('ooa.browser/wait_for', {'selector': '.product, .no-results', 'state': 'visible'}),
                    f.s('core/if', {'condition': exists('.product')}, children=[
                        f.s('ooa.browser/get_text', {'selector': '.product:first-child .price'}, {'text': 'raw'})])]}],
                **{'else': [
                    f.s('ooa.browser/navigate', {'url': '${folder}/site/shop-c.html'}),
                    f.s('ooa.browser/type_text', {'selector': '#find', 'text': '$p.Product'}),
                    f.s('ooa.browser/click', {'selector': '#find-btn'}),
                    f.s('ooa.browser/wait_for', {'selector': '#hits li, .empty:not(.hidden)', 'state': 'visible'}),
                    f.s('core/if', {'condition': exists('#hits li')}, children=[
                        f.s('ooa.browser/get_attribute', {'selector': '#hits li:first-child', 'attribute': 'data-price'}, {'value': 'raw'})])]}),
            f.s('core/if', {'condition': {'op': 'notEmpty', 'left': '$raw'}}, description='The shop sells it: compare its price', children=[
                f.s('ooa.string/replace', {'text': '$raw', 'find': '[^0-9,.]', 'replace_with': '', 'regex': True}, {'result': 'clean'}),
                f.s('ooa.string/replace', {'text': '$clean', 'find': ',', 'replace_with': '.'}, {'result': 'clean'}),
                f.s('core/set', {'variable': 'price', 'value': {'expr': 'toNumber($clean)'}}),
                f.s('core/if', {'condition': {'op': 'lt', 'left': '$price', 'right': '$best'}}, children=[
                    f.s('core/set', {'variable': 'best', 'value': '$price'}), f.s('core/set', {'variable': 'best_shop', 'value': '$shop'})])]),
        ]),
        f.s('ooa.datatable/find', {'table': '$last', 'value': '$p.Product', 'column': 'Product'}, {'found': 'found', 'first_row': 'at'}),
        f.s('core/set', {'variable': 'previous', 'value': 0}),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$found', 'right': True}}, children=[
            f.s('ooa.datatable/get_value', {'table': '$last', 'row': '$at', 'column': 'LastBest'}, {'value': 'previous'})]),
        f.s('core/set', {'variable': 'changed', 'value': {'expr': '$best != $previous'}}),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$changed', 'right': True}}, children=[f.s('core/set', add('changes'))]),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$p.Product', 'right': 'Office chair'}}, children=[f.s('core/set', {'variable': 'chair_shop', 'value': '$best_shop'})]),
        f.s('ooa.datatable/add_row', {'table': '$report', 'values': ['$p.Product', '$best_shop', '$best', '$previous', '$changed']}, {'table': 'report'}),
        f.s('core/set', add('total_best', '$best')),
        f.s('core/set', add('compared')),
    ]),
    f.s('ooa.browser/close'),
    f.s('ooa.csv/write', {'table': '$report', 'path': '${folder}/output/price-report.csv'}),
], 'W8-price-comparison-three-shops.flow.json')

# ======================================================================================== W9 leave requests (tabs, modal, balances)
write('site/leave-manager.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Leave manager</title>''' + STYLE + '''</head><body>
<header><h1>Team leave</h1><nav><button id="tab-requests" class="on">Requests</button><button id="tab-balances">Balances</button><button id="tab-dashboard">Dashboard</button></nav></header>
<main><section id="requests" class="card"><table id="requests-table"><thead><tr><th>Employee</th><th>Type</th><th>Days</th><th>Status</th><th></th></tr></thead><tbody></tbody></table></section>
<section id="balances" class="card hidden"><table id="balances-table"><thead><tr><th>Employee</th><th>Remaining</th></tr></thead><tbody>
<tr><td>Ann</td><td>10</td></tr><tr><td>Bo</td><td>2</td></tr><tr><td>Cy</td><td>15</td></tr><tr><td>Dee</td><td>0</td></tr><tr><td>Eve</td><td>8</td></tr></tbody></table></section>
<section id="dashboard" class="card hidden">Approved: <b id="d-approved"></b> &middot; Declined: <b id="d-declined"></b> &middot; Pending: <b id="d-pending"></b></section></main>
<div id="modal" class="modal hidden" role="dialog" aria-modal="true"><div><h2>Review request</h2><p><b id="m-employee"></b>: <span id="m-type"></span>, <span id="m-days"></span> days</p>
<label for="m-reason">Note to the employee</label><textarea id="m-reason"></textarea><br><button id="m-approve">Approve</button><button id="m-decline">Decline</button><button id="m-close">Close</button></div></div>
<script>
const $ = (id) => document.getElementById(id);
const reqs = [["Eve","Holiday",2,"Approved"],["Ann","Holiday",5,"Pending"],["Bo","Holiday",3,"Pending"],["Cy","Holiday",15,"Pending"],["Dee","Sick",1,"Pending"],["Ann","Holiday",6,"Pending"]]
  .map(([e,t,d,s], i) => ({i, employee:e, type:t, days:d, status:s, note:""}));
let open = null;
function tab(name) { for (const t of ["requests","balances","dashboard"]) { $(t).classList.toggle("hidden", t !== name); $("tab-" + t).classList.toggle("on", t === name); } render(); }
function render() {
  $("requests-table").querySelector("tbody").innerHTML = reqs.map((r) => "<tr><td>" + r.employee + "</td><td>" + r.type + "</td><td>" + r.days + "</td><td>" + r.status + "</td><td>" +
    (r.status === "Pending" ? "<button class='review' data-i='" + r.i + "'>Review</button>" : "") + "</td></tr>").join("");
  for (const b of document.querySelectorAll("button.review")) b.onclick = () => review(+b.dataset.i);
  for (const s of ["Approved","Declined","Pending"]) $("d-" + s.toLowerCase()).textContent = reqs.filter((r) => r.status === s).length;
}
function review(i) { open = reqs[i]; $("m-employee").textContent = open.employee; $("m-type").textContent = open.type; $("m-days").textContent = open.days; $("m-reason").value = "";
  setTimeout(() => $("modal").classList.remove("hidden"), 250); }
function decide(s) { open.status = s; open.note = $("m-reason").value; $("modal").classList.add("hidden"); setTimeout(render, 200); }
$("m-approve").onclick = () => decide("Approved"); $("m-decline").onclick = () => decide("Declined"); $("m-close").onclick = () => $("modal").classList.add("hidden");
for (const t of ["requests","balances","dashboard"]) $("tab-" + t).onclick = () => tab(t);
render();
</script></body></html>
''')

f = Flow('uc_w9_leave_requests', 'W9 Leave requests against remaining balances',
         'Read the remaining leave per employee, review each pending request in its pop-up, approve it when enough days are left (updating the balance) or decline it with a note, then check the dashboard.')
common(f)
f.var('balances', 'Table').var('who', 'String').var('days_text', 'String').var('days', 'Number').var('left', 'Number').var('at', 'Number').var('found', 'Boolean')
f.var('d_approved', 'Number').var('d_declined', 'Number').var('d_pending', 'Number')
f.var('approved', 'Number', 'output', 0, 'Requests approved by the agent.')
f.var('declined', 'Number', 'output', 0, 'Requests declined by the agent.')
f.var('ann_left', 'Number', 'output', 0, 'Days Ann has left after the approvals.')
f.var('dashboard_matches', 'Boolean', 'output', False, 'The portal dashboard shows the same numbers.')
f.save([
    open_site(f, 'leave-manager.html'),
    f.s('ooa.browser/click', {'selector': '#tab-balances'}),
    f.s('ooa.browser/extract_table', {'selector': '#balances-table'}, {'table': 'balances'}),
    f.s('ooa.datatable/change_column_type', {'table': '$balances', 'column': 'Remaining', 'type': 'Number'}, {'table': 'balances'}),
    f.s('ooa.browser/click', {'selector': '#tab-requests'}),
    f.s('core/loop', {'iterator': 'while', 'condition': exists('#requests-table button.review'), 'max_iterations': 50}, description='Each pending request', children=[
        f.s('ooa.browser/click', {'selector': '#requests-table button.review'}),
        f.s('ooa.browser/wait_for', {'selector': '#modal', 'state': 'visible'}),
        f.s('ooa.browser/get_text', {'selector': '#m-employee'}, {'text': 'who'}),
        f.s('ooa.browser/get_text', {'selector': '#m-days'}, {'text': 'days_text'}),
        f.s('core/set', {'variable': 'days', 'value': {'expr': 'toNumber($days_text)'}}),
        f.s('ooa.datatable/find', {'table': '$balances', 'value': '$who', 'column': 'Employee'}, {'found': 'found', 'first_row': 'at'}),
        f.s('core/set', {'variable': 'left', 'value': 0}),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$found', 'right': True}}, children=[
            f.s('ooa.datatable/get_value', {'table': '$balances', 'row': '$at', 'column': 'Remaining'}, {'value': 'left'})]),
        f.s('core/if', {'condition': {'op': 'le', 'left': '$days', 'right': '$left'}}, description='Enough days left?', children=[
            f.s('ooa.datatable/set_value', {'table': '$balances', 'row': '$at', 'column': 'Remaining', 'value': {'expr': '$left - $days'}}, {'table': 'balances'}),
            f.s('ooa.browser/type_text', {'selector': '#m-reason', 'text': 'Approved. ${days} days booked.'}),
            f.s('ooa.browser/click', {'selector': '#m-approve'}),
            f.s('core/set', add('approved'))],
            **{'else': [
                f.s('ooa.browser/type_text', {'selector': '#m-reason', 'text': 'Not enough leave left (${left} days).'}),
                f.s('ooa.browser/click', {'selector': '#m-decline'}),
                f.s('core/set', add('declined'))]}),
        f.s('ooa.browser/wait_for', {'selector': '#modal', 'state': 'hidden'}),
        f.s('core/delay', {'seconds': 300, 'unit': 'milliseconds'}),
    ]),
    f.s('ooa.browser/click', {'selector': '#tab-dashboard'}),
    f.s('ooa.browser/get_text', {'selector': '#d-approved'}, {'text': 'd_approved'}),
    f.s('ooa.browser/get_text', {'selector': '#d-declined'}, {'text': 'd_declined'}),
    f.s('ooa.browser/get_text', {'selector': '#d-pending'}, {'text': 'd_pending'}),
    f.s('core/set', {'variable': 'dashboard_matches', 'value': {'expr': '$d_approved == $approved + 1 && $d_declined == $declined && $d_pending == 0'}},
        description='Eve was approved before the agent started'),
    f.s('ooa.datatable/find', {'table': '$balances', 'value': 'Ann', 'column': 'Employee'}, {'first_row': 'at'}),
    f.s('ooa.datatable/get_value', {'table': '$balances', 'row': '$at', 'column': 'Remaining'}, {'value': 'ann_left'}),
    f.s('ooa.browser/close'),
    f.s('ooa.csv/write', {'table': '$balances', 'path': '${folder}/output/leave-balances.csv'}),
], 'W9-leave-requests-review.flow.json')

# ======================================================================================== W10 order fulfilment with lines, polling and download
write('site/order-admin.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Order admin</title>''' + STYLE + '''</head><body>
<header><h1>Shop admin: orders</h1></header><main>
<section id="orders-view" class="card"><button id="new-order">New order</button><table id="orders"><thead><tr><th>Order</th><th>Customer</th><th>Total</th><th>Status</th></tr></thead><tbody></tbody></table></section>
<section id="order-form" class="card hidden"><h2>New order</h2><label for="customer">Customer</label><input id="customer"><div id="lines"></div>
<button id="add-line">Add product line</button><button id="create">Create order</button><button id="cancel">Cancel</button><p id="form-error" class="error hidden"></p></section>
<section id="order-detail" class="card hidden"><h2>Order <span id="o-number"></span></h2><p>Status: <b id="o-status"></b></p><p>Total: <span id="o-total"></span></p>
<p><a id="invoice" class="hidden" href="#">Download invoice</a></p><button id="back-to-orders">Back to orders</button></section></main>
<script>
const $ = (id) => document.getElementById(id);
const catalog = {"Desk lamp": 24.50, "Office chair": 149.00, "Keyboard": 54.00, "Monitor arm": 59.00};
const orders = []; let n = 5000;
function view(v) { for (const s of ["orders-view","order-form","order-detail"]) $(s).classList.toggle("hidden", s !== v); }
function list() { $("orders").querySelector("tbody").innerHTML = orders.map((o) => "<tr><td>" + o.id + "</td><td>" + o.customer + "</td><td>" + o.total.toFixed(2) + "</td><td>" + o.status + "</td></tr>").join(""); }
$("new-order").onclick = () => { $("customer").value = ""; $("lines").innerHTML = ""; $("form-error").classList.add("hidden"); view("order-form"); };
$("add-line").onclick = () => { const d = document.createElement("div"); d.className = "line";
  d.innerHTML = "<select class='product'><option value=''>Product...</option>" + Object.keys(catalog).map((p) => "<option>" + p + "</option>").join("") + "</select> <input class='qty' type='number' min='1' value='1' style='width:80px'>";
  $("lines").appendChild(d); };
$("cancel").onclick = () => view("orders-view");
$("create").onclick = () => {
  const lines = [...document.querySelectorAll("#lines .line")].map((l) => ({product: l.querySelector(".product").value, qty: +l.querySelector(".qty").value}));
  if (!$("customer").value.trim() || !lines.length || lines.some((l) => !l.product || l.qty < 1)) { $("form-error").textContent = "Customer and product lines are required"; $("form-error").classList.remove("hidden"); return; }
  const o = {id: "ORD-" + (++n), customer: $("customer").value.trim(), lines, status: "Processing"};
  o.total = lines.reduce((s, l) => s + catalog[l.product] * l.qty, 0); orders.push(o); list();
  $("o-number").textContent = o.id; $("o-status").textContent = o.status; $("o-total").textContent = o.total.toFixed(2); $("invoice").classList.add("hidden"); view("order-detail");
  setTimeout(() => { o.status = "Ready"; $("o-status").textContent = "Ready"; list();
    const text = "Invoice for order " + o.id + "\\nCustomer: " + o.customer + "\\n" + o.lines.map((l) => l.product + " x " + l.qty + " = " + (catalog[l.product] * l.qty).toFixed(2)).join("\\n") + "\\nTotal: " + o.total.toFixed(2) + "\\n";
    const a = $("invoice"); a.href = URL.createObjectURL(new Blob([text], {type: "text/plain"})); a.download = o.id + ".txt"; a.classList.remove("hidden"); }, 1500);
};
$("back-to-orders").onclick = () => { view("orders-view"); list(); };
</script></body></html>
''')
write('data/orders-to-create.json', json.dumps([
    {'customer': 'Acme', 'expected_total': 198.00, 'lines': [{'product': 'Desk lamp', 'qty': 2}, {'product': 'Office chair', 'qty': 1}]},
    {'customer': 'Globex', 'expected_total': 186.50, 'lines': [{'product': 'Keyboard', 'qty': 3}, {'product': 'Desk lamp', 'qty': 1}]},
], indent=2) + '\n')

f = Flow('uc_w10_order_fulfilment', 'W10 Order fulfilment with invoice check',
         'Create each order from a JSON file with its product lines, wait until the shop has processed it, download the invoice and check its total against the expected total.')
common(f)
f.var('orders_json', 'String').var('orders', 'List').var('o', 'Dictionary').var('line', 'Dictionary').var('k', 'Number').var('status', 'String')
f.var('invoice_path', 'String').var('invoice_text', 'String').var('match', 'String').var('total_text', 'String').var('total', 'Number').var('order_no', 'String')
f.var('order_list', 'Table')
f.var('created', 'Number', 'output', 0, 'Orders created.')
f.var('verified', 'Number', 'output', 0, 'Invoices whose total matches the expected total.')
f.var('grand_total', 'Number', 'output', 0, 'Sum of the invoice totals.')
f.var('ready_orders', 'Number', 'output', 0, 'Orders shown as Ready in the order list.')
f.save([
    f.s('ooa.file/read_text', {'path': '${folder}/data/orders-to-create.json'}, {'text': 'orders_json'}),
    f.s('ooa.json/parse', {'text': '$orders_json'}, {'value': 'orders'}),
    open_site(f, 'order-admin.html'),
    f.s('core/loop', {'iterator': 'list', 'over': '$orders', 'as': 'o'}, description='Each order', children=[
        f.s('ooa.browser/click', {'selector': '#new-order'}),
        f.s('ooa.browser/wait_for', {'selector': '#order-form', 'state': 'visible'}),
        f.s('ooa.browser/type_text', {'selector': '#customer', 'text': '$o.customer'}),
        f.s('core/set', {'variable': 'k', 'value': 0}),
        f.s('core/loop', {'iterator': 'list', 'over': '$o.lines', 'as': 'line'}, description='Each product line', children=[
            f.s('ooa.browser/click', {'selector': '#add-line'}),
            f.s('core/set', add('k')),
            f.s('ooa.browser/select_option', {'selector': '#lines .line:nth-child(${k}) select.product', 'option': '$line.product'}),
            f.s('ooa.browser/type_text', {'selector': '#lines .line:nth-child(${k}) input.qty', 'text': '${line.qty}'}),
        ]),
        f.s('ooa.browser/click', {'selector': '#create'}),
        f.s('ooa.browser/wait_for', {'selector': '#order-detail', 'state': 'visible'}),
        f.s('ooa.browser/get_text', {'selector': '#o-number'}, {'text': 'order_no'}),
        f.s('core/set', {'variable': 'status', 'value': ''}),
        f.s('core/loop', {'iterator': 'while', 'condition': {'op': 'ne', 'left': '$status', 'right': 'Ready'}, 'max_iterations': 60}, description='Wait until the shop has processed it', children=[
            f.s('core/delay', {'seconds': 250, 'unit': 'milliseconds'}),
            f.s('ooa.browser/get_text', {'selector': '#o-status'}, {'text': 'status'}),
        ]),
        f.s('ooa.browser/download', {'selector': '#invoice', 'folder': '${folder}/output/invoices', 'timeout_ms': 15000}, {'path': 'invoice_path'}),
        f.s('ooa.file/read_text', {'path': '$invoice_path'}, {'text': 'invoice_text'}),
        f.s('ooa.string/find', {'text': '$invoice_text', 'find': 'Total:\\s*[0-9.]+', 'regex': True}, {'match': 'match'}),
        f.s('ooa.string/replace', {'text': '$match', 'find': '^Total:\\s*', 'replace_with': '', 'regex': True}, {'result': 'total_text'}),
        f.s('core/set', {'variable': 'total', 'value': {'expr': 'toNumber($total_text)'}}),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$total', 'right': '$o.expected_total'}}, children=[f.s('core/set', add('verified'))],
            **{'else': [f.s('core/log', {'message': 'Invoice ${order_no}: total ${total} differs from the expected ${o.expected_total}', 'level': 'warning'})]}),
        f.s('core/set', add('grand_total', '$total')),
        f.s('core/set', add('created')),
        f.s('ooa.browser/click', {'selector': '#back-to-orders'}),
    ]),
    f.s('ooa.browser/extract_table', {'selector': '#orders'}, {'table': 'order_list'}),
    f.s('ooa.datatable/filter', {'table': '$order_list', 'column': 'Status', 'operator': 'eq', 'value': 'Ready'}, {'count': 'ready_orders'}),
    f.s('ooa.browser/close'),
    f.s('ooa.csv/write', {'table': '$order_list', 'path': '${folder}/output/created-orders.csv'}),
], 'W10-order-fulfilment-invoices.flow.json')

# ======================================================================================== test cases
U, W = '../../examples/use-cases', {'folder': '.', 'headless': True}


def case(code, name, flow, inputs, expect, **kw):
    c = {'command': 'use-case/' + code, 'name': name, 'flow': U + '/' + flow + '.flow.json', 'copy': U, 'inputs': inputs, 'expect': expect, 'requires': 'browser'}
    c.update(kw)
    return c


cases = [
    case('W6', 'seven invoices decided by the approval rules', 'W6-invoice-approval-process', W,
         {'processed': 7, 'approved': 4, 'rejected': 2, 'escalated': 1},
         expectFiles={'output/invoice-decisions.csv': True}),
    case('W7', 'two suppliers onboarded, two refused with the reason', 'W7-supplier-onboarding-wizard', W, {'onboarded': 2, 'failed': 2, 'last_id': 'SUP-1002'},
         expectFiles={'output/supplier-onboarding.xlsx': True}),
    case('W8', 'cheapest offers across three shop layouts', 'W8-price-comparison-three-shops', W, {'compared': 3, 'changes': 2, 'total_best': 203.29, 'chair_shop': 'Shop C'},
         expectFiles={'output/price-report.csv': True}),
    case('W9', 'leave approved only while days are left', 'W9-leave-requests-review', W, {'approved': 2, 'declined': 3, 'ann_left': 5, 'dashboard_matches': True},
         expectFiles={'output/leave-balances.csv': True}),
    case('W10', 'orders created, processed and their invoices checked', 'W10-order-fulfilment-invoices', W, {'created': 2, 'verified': 2, 'grand_total': 384.5, 'ready_orders': 2},
         expectFiles={'output/invoices/ORD-5001.txt': True, 'output/invoices/ORD-5002.txt': True}),
]
p = os.path.join(ROOT, '..', '..', 'platform', 'cli', 'src', 'test', 'resources', 'command-samples', '21-big-web-processes.json')
with open(p, 'w', encoding='utf-8', newline='\n') as fh:
    json.dump(cases, fh, indent=1)
print('written W6-W10 and', len(cases), 'test cases')
