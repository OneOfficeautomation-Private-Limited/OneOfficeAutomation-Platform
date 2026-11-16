"""RW01 Customer support: public research -> ServiceNow -> Salesforce -> Jira -> Microsoft 365 (child agents)."""
from common import Child, Main, cred, evidence_var, fault_setup, graph, jira, save_evidence, sf, sn

FOLDER = "RW01 Customer support"

# JavaScript run in the page for each public site: up to three passages, as one object.
JS_BOOKS = ("(() => { const m = document.querySelector('.product_main'); const rows = {}; "
            "document.querySelectorAll('table.table-striped tr').forEach(r => rows[r.querySelector('th').textContent.trim()] = r.querySelector('td').textContent.trim()); "
            "const d = document.querySelector('#product_description + p'); "
            "return {title: m.querySelector('h1').textContent.trim(), passages: ["
            "'Price ' + m.querySelector('.price_color').textContent.trim(), "
            "rows['Availability'] || '', (d ? d.textContent.trim().slice(0, 240) : '')], upc: rows['UPC'] || ''}; })()")
JS_WIKI = ("((q) => { const rows = Array.from(document.querySelectorAll('table.wikitable tbody tr')); "
           "const r = rows.find(tr => { const a = tr.querySelector('td a'); return a && a.textContent.trim() === q; }); "
           "if (!r) return {title: q, passages: [], upc: ''}; const c = Array.from(r.querySelectorAll('td')).map(td => td.textContent.trim().replace(/\\[.*?\\]/g, '')); "
           "return {title: q, passages: ['Population (1 July 2023): ' + c[1], 'Population (1 July 2022): ' + c[2], 'Change: ' + c[3]], upc: ''}; })(%s)")
JS_QUOTES = ("((q) => Array.from(document.querySelectorAll('.quote')).filter(d => d.querySelector('.author').textContent.trim() === q)"
             ".slice(0, 3).map(d => d.querySelector('.text').textContent.trim()))(%s)")
JS_BODY = "document.body.innerText.slice(0, 600)"


def b2_public_research():
    c = Child("RW01", 2, "Public knowledge research",
              "Opens allow-listed public pages (Books to Scrape, Wikipedia, Quotes to Scrape) for each support case, "
              "extracts up to three passages, records URL, title, time and a screenshot, compares the facts with the "
              "internal knowledge base and rejects page text that tries to give the agent instructions.",
              "10 product/reference queries", data_file="RW01/queries.csv")
    f = c.f
    for name, typ, default in [("allowed", "String", ""), ("host", "String", ""), ("qjs", "String", ""), ("page", "Dictionary", None),
                               ("passages", "List", None), ("facts", "String", ""), ("url", "String", ""),
                               ("title", "String", ""), ("captured", "String", ""), ("shot", "String", ""),
                               ("injected", "Number", 0), ("kb", "List", None), ("kb_text", "String", ""),
                               ("score", "Number", 0), ("freshness", "String", ""), ("verdict", "String", ""),
                               ("evidence", "Table", {"columns": ["key", "case", "site", "url", "title", "captured", "facts", "kb_match", "verdict", "screenshot"], "rows": []}),
                               ("index_file", "String", "")]:
        f.var(name, typ, "local", default)
    f.var("evidence_file", "String", "output", "", "Evidence packets: one row per query with URL, title, time and facts.")
    for n, t, d in [("csv_text", "String", ""), ("js_summary", "Any", None), ("wa_ok", "Boolean", False), ("wa_answer", "String", "")]:
        f.var(n, t, "local", d)
    c.start()
    with c.prepare("Read the 10 queries, the allowed sites and the internal knowledge base, and open the browser."):
        f.act("ooa.global_values/get", desc="Read the allow-list of hosts (global value rw_allowed_hosts)",
              name="rw_allowed_hosts", default="books.toscrape.com,quotes.toscrape.com,en.wikipedia.org,127.0.0.1",
              type="String", out={"value": "allowed"})
        f.set("index_file", "${out_dir}/internal-kb.index", desc="Where the internal knowledge index goes")
        f.act("ooa.knowledge/build_index", desc="Index the internal knowledge base (words search, no AI model)",
              files=["${base}/data/RW01/internal_kb"], index_file="$index_file$", out={"passages": "kb_text"})
        f.act("ooa.folder/create", desc="Folder for the screenshots", path="${out_dir}/evidence")
        f.act("ooa.browser/open", desc="Open Edge (visible, so you can watch)", browser="edge", engine="playwright",
              headless={"expr": "not $watch$"}, url="about:blank", keep_open=False)
    with c.records("For each query: check the site is allowed, open the page, wait for it, extract passages, keep the "
                   "evidence, look for injected instructions, compare with the internal knowledge base, score and save.",
                   summary="Site: ${rec.site}\nQuery: ${rec.query}\nFacts: ${facts}\nVerdict: ${verdict}"):
        f.expr("qjs", "jsonStringify($rec.query$)", desc="The query as a JavaScript string")
        f.comment("Policy first: the page's host must be on the allow-list before the browser goes there.")
        f.act("ooa.string/extract_text", desc="Take the host out of the address", text="$rec.url$", after="://", before="/",
              out={"result": "host"})
        f.expr("host", "first(split($host$, ':'))", desc="Without the port")
        with f.if_({"op": "contains", "left": "$allowed$", "right": "$host$", "ignoreCase": True}, desc="Is the host allowed?") as ok:
            f.act("ooa.browser/navigate", desc="Open the page", url="$rec.url$")
        with f.else_(ok):
            f.act("core/throw", desc="Refuse a host that is not allowed", code="POLICY.HOST_NOT_ALLOWED",
                  message="${host} is not on the allow-list")
        with f.switch("$rec.site$", desc="Extract up to three passages; each site has its own page layout") as sw:
            with f.case(sw, "books"):
                f.act("ooa.browser/click", desc="Open the book's page", selector='a[title="${rec.query}"]', then_wait_for="page_load")
                f.act("ooa.browser/wait_for", desc="Wait for the product details", selector=".product_main")
                f.act("ooa.browser/run_javascript", desc="Read price, stock and description", script=JS_BOOKS, out={"result": "page"})
            with f.case(sw, "wikipedia"):
                f.act("ooa.browser/wait_for", desc="Wait for the population table", selector="table.wikitable")
                f.act("ooa.browser/run_javascript", desc="Read the country's population row",
                      script=JS_WIKI % "${qjs}",
                      out={"result": "page"})
            with f.case(sw, "quotes"):
                f.act("ooa.browser/wait_for", desc="Wait until the JavaScript has drawn the quotes", selector=".quote")
                f.act("ooa.browser/run_javascript", desc="Read up to three quotes by the author",
                      script=JS_QUOTES % "${qjs}",
                      out={"result": "passages"})
                f.set("page", {"title": "$rec.query$", "passages": "$passages$", "upc": ""}, desc="Same shape as the other sites")
            with f.default(sw):
                f.act("ooa.browser/wait_for", desc="Wait for the page body", selector="body")
                f.act("ooa.browser/run_javascript", desc="Read the page text", script=JS_BODY, out={"result": "facts"})
                f.set("page", {"title": "$rec.query$", "passages": ["$facts$"], "upc": ""}, desc="Same shape as the other sites")
        f.comment("Evidence the workbook asks for: URL, title, time and a screenshot without credentials.")
        f.act("ooa.browser/page_info", desc="Read the page's address and title", out={"url": "url", "title": "title"})
        f.expr("captured", "formatDate(now(), '%Y-%m-%dT%H:%M:%SZ')", desc="Time of capture (UTC)")
        f.act("ooa.browser/screenshot", desc="Screenshot of the page", path="${out_dir}/evidence/${rec.key}.png")
        f.expr("facts", "join($page.passages$, ' | ')", desc="Join the passages into one text")
        f.act("ooa.string/replace", desc="Normalize spaces and line breaks", text="$facts$", find="\\s+", replace_with=" ",
              regex=True, out={"result": "facts"})
        f.comment("Page text is untrusted: text that tries to instruct the agent is rejected, never followed.")
        f.act("ooa.string/count_occurrences", desc="Look for injected instructions", text={"expr": "lower($facts$)"},
              find="ignore all previous instructions", match_case=False, out={"count": "injected"})
        with f.if_("$injected$ > 0", desc="Injected instructions found?"):
            f.act("ooa.log/write", desc="Log the rejected page", path="$log_file$", level="WARN",
                  message="${rec.key}: page text tries to instruct the agent; passages rejected")
            f.act("core/throw", desc="Reject the page", code="POLICY.PROMPT_INJECTION",
                  message="The page ${url} contains instructions for the agent; its passages are not used")
        f.act("ooa.knowledge/search", desc="Find what the internal knowledge base says", index_file="$index_file$",
              query="${rec.query} ${rec.topic}", top_k=1, out={"results": "kb"})
        f.expr("kb_text", "if(len($kb$) > 0, get(first($kb$), 'text'), '')", desc="Best internal passage")
        f.act("ooa.fuzzy/similarity", desc="How close are the key public fact and internal knowledge?", text1={"expr": "coalesce(first($page.passages$), $facts$)"},
              text2="$kb_text$", algorithm="token_set", out={"score": "score"})
        f.expr("freshness", "if($rec.site == 'wikipedia', 'yearly', if($rec.site == 'books', 'live catalogue', 'static'))",
               desc="Score freshness by source")
        f.expr("verdict", "if(isEmpty($kb_text$), 'no internal answer', if($score >= 40, 'agrees with KB', 'hold for review: differs from KB'))",
               desc="Compare with the internal knowledge base")
        f.act("ooa.datatable/add_row", desc="Add the evidence packet", table="$evidence$",
              values={"key": "$rec.key$", "case": "$rec.case_key$", "site": "$rec.site$", "url": "$url$", "title": "$title$",
                      "captured": "$captured$", "facts": "$facts$", "kb_match": "$score$", "verdict": "$verdict$",
                      "screenshot": "${out_dir}/evidence/${rec.key}.png"}, out={"table": "evidence"})
        f.set("rec_note", "${verdict} (${freshness})", desc="Note for the results table")
    with c.finish():
        f.act("ooa.browser/close", desc="Close the browser", what="session")
        f.set("evidence_file", "${out_dir}/evidence.csv", desc="Name of the evidence file")
        f.act("ooa.datatable/write_to_file", desc="Save the evidence packets", table="$evidence$", path="$evidence_file$")
        f.act("ooa.file/write_text", desc="A small JavaScript that sums up the verdicts", path="${out_dir}/summary.js", text='// Summarizes the research verdicts (JSON lines on standard input). Works with Node.js and with Windows\' JScript.\nvar text = (typeof WScript !== \'undefined\') ? WScript.StdIn.ReadAll() : require(\'fs\').readFileSync(0, \'utf8\');\nvar lines = text.split(/\\r?\\n/), agree = 0, hold = 0, none = 0;\nfor (var i = 1; i < lines.length; i++) {\n  if (!lines[i]) continue;\n  if (lines[i].indexOf(\'agrees with KB\') >= 0) agree++; else if (lines[i].indexOf(\'hold for review\') >= 0) hold++; else none++;\n}\nvar out = \'{"agree": \' + agree + \', "hold": \' + hold + \', "other": \' + none + \'}\';\nif (typeof WScript !== \'undefined\') WScript.Echo(out); else console.log(out);\n')
        f.act("ooa.datatable/to_csv_text", desc="The evidence as text", table="$evidence$", out={"text": "csv_text"})
        f.act("ooa.script/run_javascript", desc="Sum up the verdicts (JavaScript: Node.js, or Windows JScript)", file="${out_dir}/summary.js", input="$csv_text$",
              out={"json": "js_summary"})
        f.act("ooa.log/write", desc="Log the summary", path="$log_file$", message="Verdicts: ${jsonStringify($js_summary$)}")
        with f.try_(desc="Web goal agent: the model works the public catalogue page itself (stand-in model, live site)") as wa:
            f.act("ooa.browser/open", desc="A browser session for the web goal agent", url="https://books.toscrape.com/", session="webagent",
                  headless={"expr": "not $watch$"}, engine="playwright", keep_open=True)
            f.act("ooa.web_agent/run", desc="Ask the web goal agent to read the catalogue's first book", start_url="https://books.toscrape.com/",
                  goal="Open the catalogue and report the title and price of the first book listed.", allowed_sites=["books.toscrape.com"], max_steps=3,
                  model="qa-scripted", api_key=cred("ai"), base_url="${standin}/v1", session="webagent", out={"success": "wa_ok", "answer": "wa_answer"})
            f.act("ooa.browser/close", desc="Close its browser", session="webagent", cont=True)
        with f.catch(wa, message_to="wa_answer"):
            f.act("ooa.log/write", desc="Note the web agent's problem", path="$log_file$", level="WARN", message="Web goal agent: ${wa_answer}")
        f.act("ooa.log/write", desc="Log the web agent's answer", path="$log_file$", message="Web goal agent (${wa_ok}): ${wa_answer}")
    return c



MAILBOX = "qa-support@ooa-qa.test"
EMAIL_RE = r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}"
PHONE_RE = r"[+]?[0-9][0-9 ()-]{7,}[0-9]"


def b1_case_intake():
    c = Child("RW01", 1, "Case intake and dedup",
              "Reads the QA support mailbox, normalizes each message, masks personal data, finds the customer, drops duplicates by "
              "their content hash, classifies type and severity with a weighted rule table, routes malformed mail and checks each message off.",
              "10 tagged case e-mails", record_key="id")
    f = c.f
    for n, t, d in [("email", "Dictionary", None), ("subject", "String", ""), ("body", "String", ""), ("masked", "String", ""),
                    ("account", "String", ""), ("customer", "String", ""), ("tier", "String", ""), ("hash", "String", ""),
                    ("hashes", "List", []), ("rule", "Dictionary", None), ("best", "Number", 0), ("rtype", "String", ""),
                    ("severity", "String", ""), ("cases", "Table", None), ("customers", "Table", None), ("rules", "Table", None),
                    ("rules_list", "List", None), ("case_key", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "case", "received", "customer", "tier", "type", "severity", "hash", "masked_body"])
    c.start()
    with c.prepare("Read the 10 oldest messages of the QA support mailbox (Microsoft 365 stand-in), the customers and the weighted rule table."):
        fault_setup(f, "graph")
        f.act("ooa.microsoft365/list_mail", desc="List the support mailbox's messages", folder="inbox", max_results=10, oldest_first=True,
              out={"table": "records"}, **graph(MAILBOX))
        f.act("ooa.csv/read", desc="Read the cases (sender to account)", path="${base}/data/RW01/cases.csv", out={"table": "cases"})
        f.act("ooa.csv/read", desc="Read the customers", path="${base}/data/RW01/customers.csv", out={"table": "customers"})
        f.act("ooa.csv/read", desc="Read the weighted rule table", path="${base}/data/RW01/rules.csv", out={"table": "rules"})
        f.act("ooa.datatable/to_records", desc="Rules as a list to go through", table="$rules$", out={"records": "rules_list"})
    with c.records("Per message: read it, normalize, mask personal data, find the customer, hash and drop duplicates, classify, "
                   "route malformed mail, record the case and check the message off.", summary="${subject}\nCustomer: ${customer}\nType ${rtype}, severity ${severity}"):
        f.act("ooa.microsoft365/get_mail", desc="Read the message", id="$rec.id$", mark_read=False, body_as_text=True, out={"email": "email"}, **graph(MAILBOX))
        with f.if_("isEmpty(trim(coalesce($email.subject, ''))) or isEmpty(trim(coalesce($email.body, '')))", desc="Malformed (no subject or body)?"):
            f.act("ooa.microsoft365/move_mail", desc="Route it to the Malformed folder", id="$rec.id$", to_folder="malformed", **graph(MAILBOX))
            f.act("core/throw", desc="Route the message", code="ROUTE.MALFORMED", message="Message ${rec.id} has no subject or body; moved to Malformed")
        f.act("ooa.string/trim", desc="Normalize the subject", text="$email.subject$", out={"result": "subject"})
        f.act("ooa.string/trim", desc="Normalize the body", text="$email.body$", out={"result": "body"})
        f.comment("Personal data never goes into logs or evidence: e-mail addresses, phone numbers and card numbers are masked.")
        f.act("ooa.string/replace", desc="Mask e-mail addresses", text="$body$", find=EMAIL_RE, replace_with="[email]", regex=True, out={"result": "masked"})
        f.act("ooa.string/replace", desc="Mask phone and card numbers", text="$masked$", find=PHONE_RE, replace_with="[number]", regex=True, out={"result": "masked"})
        f.act("ooa.datatable/lookup", desc="Find the case by its subject", table="$cases$", column="subject",
              value={"expr": "replace($subject$, 'Re: ', '')"}, return_column="key", if_not_found="", out={"value": "case_key"})
        f.act("ooa.datatable/lookup", desc="Find the sender's account", table="$cases$", column="from", value="${email.from}", return_column="account",
              if_not_found="", out={"value": "account"})
        f.act("ooa.datatable/lookup", desc="Resolve the synthetic customer id", table="$customers$", column="account", value="$account$",
              return_column="customer_id", if_not_found="UNKNOWN", out={"value": "customer"})
        f.act("ooa.datatable/lookup", desc="Account tier (for the SLA)", table="$customers$", column="account", value="$account$",
              return_column="tier", if_not_found="Bronze", out={"value": "tier"})
        f.act("ooa.crypto/hash_text", desc="Content hash for duplicate checks", text={"expr": "lower($body$)"}, out={"hash": "hash"})
        with f.if_({"op": "contains", "left": "$hashes$", "right": "$hash$"}, desc="Seen this content before?"):
            f.act("ooa.microsoft365/move_mail", desc="Route the duplicate", id="$rec.id$", to_folder="duplicates", **graph(MAILBOX))
            f.act("core/throw", desc="Route the duplicate", code="ROUTE.DUPLICATE", message="Message ${rec.id} repeats an earlier case (same content)")
        f.act("ooa.list/add_item", desc="Remember the hash", list="$hashes$", item="$hash$", out={"list": "hashes"})
        f.comment("Classify with the weighted rule table (a rule decision, not a model guess): the heaviest matching keyword wins.")
        f.set("best", 0, desc="No rule yet")
        f.set("rtype", "question", desc="Default type")
        f.set("severity", "P4", desc="Default severity")
        with f.loop(desc="Each rule", iterator="list", over="$rules_list$", **{"as": "rule"}):
            with f.if_({"all": [{"op": "contains", "left": {"expr": "lower($subject$ + ' ' + $body$)"}, "right": "$rule.keyword$"},
                                "toNumber($rule.weight$) > $best$"]}, desc="Matches and weighs more?"):
                f.expr("best", "toNumber($rule.weight$)", desc="New best weight")
                f.set("rtype", "$rule.type$", desc="Its type")
                f.set("severity", "$rule.severity$", desc="Its severity")
        f.act("ooa.datatable/add_row", desc="Create the correlation record", table="$evidence$",
              values={"key": "$rec.id$", "case": "$case_key$", "received": "$rec.received$", "customer": "$customer$", "tier": "$tier$", "type": "$rtype$",
                      "severity": "$severity$", "hash": "$hash$", "masked_body": "$masked$"}, out={"table": "evidence"})
        f.act("ooa.microsoft365/mark_mail", desc="Check the message off (read)", id="$rec.id$", status="read", **graph(MAILBOX))
        f.set("rec_note", "${case_key} ${rtype} ${severity} (${customer})", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b3_servicenow_worker():
    c = Child("RW01", 3, "ServiceNow case worker",
              "For each case's incident: checks the OOA-QA tag, reads the account, applies the SLA matrix, sets the assignment group, "
              "adds one internal work note, moves it to In Progress, reads it back to verify and keeps the sys_id.",
              "10 test incidents", data_file="RW01/incidents.csv")
    f = c.f
    for n, t, d in [("found", "Table", None), ("inc", "Dictionary", None), ("incs", "List", None), ("acct", "List", None), ("tier", "String", ""),
                    ("sla", "Table", None), ("hit", "Table", None), ("group", "String", ""), ("hours", "String", ""), ("sev", "String", ""),
                    ("after", "Dictionary", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "case", "number", "sys_id", "severity", "tier", "group", "sla_hours", "state"])
    c.start()
    with c.prepare("Read the incidents to work and the SLA matrix (severity x tier -> hours and assignment group)."):
        fault_setup(f, "servicenow")
        f.act("ooa.csv/read", desc="Read the SLA matrix", path="${base}/data/RW01/sla_matrix.csv", out={"table": "sla"})
    with c.records("Per incident: find it, check the tag, read the account, apply the SLA, update, add a work note, read back, verify, keep the id.",
                   summary="${inc.number} ${sev} ${tier}\nGroup: ${group} (${hours} h)"):
        f.act("ooa.servicenow/query", desc="Find the incident of the case", table="incident", query="correlation_id=OOA-QA-${rec.case_key}", max_records=1,
              out={"table": "found"}, **sn())
        f.act("ooa.datatable/to_records", desc="As a record", table="$found$", out={"records": "incs"})
        with f.if_({"op": "empty", "left": "$incs$"}, desc="No incident?"):
            f.act("core/throw", desc="Route it", code="ROUTE.NO_INCIDENT", message="No incident for case ${rec.case_key}")
        f.expr("inc", "first($incs$)", desc="The incident")
        with f.if_("not startsWith($inc.short_description$, 'OOA-QA')", desc="Not tagged OOA-QA?"):
            f.act("core/throw", desc="Never touch an untagged record", code="POLICY.NOT_QA_RECORD",
                  message="Incident ${inc.number} is not tagged OOA-QA; it is left unchanged")
        f.act("ooa.servicenow/query", desc="Read the account context", table="customer_account", query="number=${inc.u_account}", max_records=1,
              out={"table": "found"}, **sn())
        f.act("ooa.datatable/to_records", desc="As a record", table="$found$", out={"records": "acct"})
        f.expr("tier", "if(len($acct$) > 0, get(first($acct$), 'u_tier'), 'Bronze')", desc="The account's tier")
        f.set("sev", "P${inc.urgency}", desc="Severity from the urgency")
        f.act("ooa.datatable/filter", desc="Apply the SLA matrix", table="$sla$", column="severity", operator="eq", value="$sev$",
              more_conditions=[{"column": "tier", "operator": "eq", "value": "$tier$"}], out={"table": "hit"})
        f.act("ooa.datatable/get_value", desc="Assignment group", table="$hit$", row=0, column="group", out={"value": "group"})
        f.act("ooa.datatable/get_value", desc="SLA hours", table="$hit$", row=0, column="hours", out={"value": "hours"})
        f.act("ooa.servicenow/update_record", desc="Set the assignment group and SLA", table="incident", sys_id="$inc.sys_id$",
              fields={"assignment_group": "$group$", "u_sla_hours": "$hours$"}, **sn())
        f.act("ooa.servicenow/add_comment", desc="Add the internal work note (with its dedupe key)", table="incident", sys_id="$inc.sys_id$", internal=True,
              text="[OOA-QA ${run_id} ${rec.key}] Routed to ${group}, SLA ${hours} h (${sev}, ${tier}).", **sn())
        f.act("ooa.servicenow/update_record", desc="Move it to In Progress", table="incident", sys_id="$inc.sys_id$", fields={"state": "2"}, **sn())
        f.act("ooa.servicenow/get_record", desc="Read it back", table="incident", sys_id="$inc.sys_id$", out={"record": "after"}, **sn())
        with f.if_({"any": [{"op": "ne", "left": "$after.assignment_group$", "right": "$group$"}, {"op": "ne", "left": "$after.state$", "right": "2"}]},
                   desc="Did the changes stick?"):
            f.act("core/throw", desc="Report the mismatch", code="VERIFY.MISMATCH", message="Incident ${inc.number} did not keep its changes")
        f.act("ooa.datatable/add_row", desc="Keep the sys_id and the result", table="$evidence$",
              values={"key": "$rec.key$", "case": "$rec.case_key$", "number": "$inc.number$", "sys_id": "$inc.sys_id$", "severity": "$sev$", "tier": "$tier$",
                      "group": "$group$", "sla_hours": "$hours$", "state": "$after.state$"}, out={"table": "evidence"})
        f.set("rec_note", "${inc.number} -> ${group}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b4_salesforce_jira():
    c = Child("RW01", 4, "Salesforce and Jira linker",
              "Finds each Salesforce case by its external test id, links the ServiceNow incident, creates or reuses a Jira defect for defects "
              "only, maps the priority, adds an evidence comment, moves the issue to In Progress, re-reads both systems and reconciles the ids.",
              "10 Salesforce cases and related Jira issues", data_file="RW01/sf_cases.csv")
    f = c.f
    for n, t, d in [("case", "Dictionary", None), ("found", "Table", None), ("incs", "List", None), ("sys_id", "String", ""), ("issues", "Table", None),
                    ("issue_key", "String", ""), ("jprio", "String", ""), ("again", "Dictionary", None), ("idem", "String", ""), ("hits", "Number", 0)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "case", "sf_id", "servicenow", "jira", "jira_status", "priority"])
    c.start()
    with c.prepare("Read the Salesforce cases to link."):
        fault_setup(f, "salesforce")
    with c.records("Per case: look it up, link ServiceNow, Jira for defects only, map priority, comment, transition, re-read and reconcile.",
                   summary="${case.Id}  ServiceNow ${sys_id}\nJira: ${issue_key}"):
        f.act("ooa.salesforce/get_record", desc="Look up the sandbox case by its external test id", object="Case", id="OOA-QA-${rec.case_key}",
              external_id_field="External_Id__c", out={"record": "case"}, **sf())
        f.act("ooa.crypto/hash_text", desc="Idempotency key of this link", text="${run_id}|${rec.case_key}", out={"hash": "idem"})
        f.act("ooa.servicenow/query", desc="Find the ServiceNow incident", table="incident", query="correlation_id=OOA-QA-${rec.case_key}", max_records=1,
              out={"table": "found"}, **sn())
        f.act("ooa.datatable/to_records", desc="As records", table="$found$", out={"records": "incs"})
        f.expr("sys_id", "if(len($incs$) > 0, get(first($incs$), 'number'), '')", desc="Its number")
        f.set("issue_key", "", desc="No Jira issue yet")
        with f.switch("$rec.priority$", desc="Map the priority to Jira's") as sw:
            with f.case(sw, "High"):
                f.set("jprio", "High", desc="High")
            with f.case(sw, "Medium"):
                f.set("jprio", "Medium", desc="Medium")
            with f.default(sw):
                f.set("jprio", "Low", desc="Low")
        with f.if_({"op": "eq", "left": "$rec.type$", "right": "defect"}, desc="A defect? Only defects get a Jira issue"):
            f.act("ooa.jira/search", desc="Is there an issue already (dedupe by label)?", jql='project = OOAQA AND labels = "OOA-QA-${rec.case_key}"',
                  max_results=1, out={"table": "issues", "count": "hits"}, **jira())
            with f.if_("$hits$ == 0", desc="None yet: create it") as none:
                f.act("ooa.jira/create_issue", desc="Create the test defect", project="OOAQA", issue_type="Bug", summary="OOA-QA ${rec.case_key} ${case.Subject}",
                      priority="$jprio$", labels=["OOA-QA-${rec.case_key}", "OOA-QA-${run_id}"], description="Linked from Salesforce ${case.Id}; ServiceNow ${sys_id}.",
                      out={"key": "issue_key"}, **jira())
            with f.else_(none):
                f.act("ooa.datatable/get_value", desc="Reuse the existing issue", table="$issues$", row=0, column="key", out={"value": "issue_key"})
            f.act("ooa.jira/add_comment", desc="Add the sanitized evidence link", key="$issue_key$",
                  text="Evidence: Salesforce ${case.Id}, ServiceNow ${sys_id}, run ${run_id} (no customer data).", **jira())
            f.act("ooa.jira/transition", desc="Move it within the test workflow", key="$issue_key$", transition="In Progress", **jira())
        f.act("ooa.salesforce/update_record", desc="Link the incident and the issue on the case", object="Case", id="$case.Id$",
              fields={"ServiceNow_Id__c": "$sys_id$", "Jira_Key__c": "$issue_key$", "Status": "Working"}, **sf())
        f.act("ooa.salesforce/get_record", desc="Re-read the case", object="Case", id="$case.Id$", out={"record": "again"}, **sf())
        with f.if_({"op": "ne", "left": "${again.Jira_Key__c}", "right": "$issue_key$"}, desc="Do the ids reconcile?"):
            f.act("core/throw", desc="Record the failed link for compensation", code="VERIFY.LINK_MISMATCH", message="Case ${case.Id} did not keep the Jira key")
        f.act("ooa.datatable/add_row", desc="Record the reconciled ids", table="$evidence$",
              values={"key": "$rec.key$", "case": "$rec.case_key$", "sf_id": "$case.Id$", "servicenow": "$sys_id$", "jira": "$issue_key$",
                      "jira_status": {"expr": "if(isEmpty($issue_key$), '', 'In Progress')"}, "priority": "$jprio$"}, out={"table": "evidence"})
        f.set("rec_note", "${case.Id} linked ${sys_id} ${issue_key}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b5_review_messaging():
    c = Child("RW01", 5, "Human review and messaging",
              "Builds each reply from the approved template and the case's facts, scans it for personal data, checks the approval and the "
              "separation of duties, sends only approved replies only to the QA mailbox, reconciles the sent message and logs the decision.",
              "10 response drafts", data_file="RW01/replies.csv")
    f = c.f
    for n, t, d in [("template", "String", ""), ("reply", "String", ""), ("customers", "Table", None), ("cases", "Table", None), ("account", "String", ""),
                    ("name", "String", ""), ("pii", "Number", 0), ("decision", "String", ""), ("got", "Table", None), ("seen", "Number", 0),
                    ("go_ahead", "Boolean", True)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "case", "to", "decision", "approver", "sent", "delivered"])
    c.start()
    with c.prepare("Read the approved reply template, the cases and the customers; ask the supervisor once (answers yes by itself after 5 s)."):
        fault_setup(f, "graph")
        f.act("ooa.desktop/prompt_yes_no", desc="Ask the supervisor: send the approved replies now? (yes after 5 s without an answer)",
              title="RW01-B5 replies", question="Send the approved OOA-QA replies now?", timeout_seconds=5, answer_on_timeout=True, cont=True,
              out={"answer": "go_ahead"})
        with f.if_("not $go_ahead$", desc="The supervisor said no?"):
            f.act("core/throw", desc="Stop: replies held", code="REVIEW.HELD", message="The supervisor held the replies")
        f.act("ooa.file/read_text", desc="Read the approved template", path="${base}/data/RW01/reply_template.txt", out={"text": "template"})
        f.act("ooa.csv/read", desc="Read the cases", path="${base}/data/RW01/cases.csv", out={"table": "cases"})
        f.act("ooa.csv/read", desc="Read the customers", path="${base}/data/RW01/customers.csv", out={"table": "customers"})
    with c.records("Per draft: fill the template, scan for personal data, check approval and separation of duties, allow only QA recipients, "
                   "send, reconcile and log.", summary="To ${rec.to}: ${decision}"):
        f.act("ooa.datatable/lookup", desc="The case's account", table="$cases$", column="key", value="$rec.case_key$", return_column="account",
              if_not_found="", out={"value": "account"})
        f.act("ooa.datatable/lookup", desc="The customer's name", table="$customers$", column="account", value="$account$", return_column="name",
              if_not_found="Customer", out={"value": "name"})
        f.act("ooa.string/replace", desc="Fill in the name", text="$template$", find="{name}", replace_with="$name$", out={"result": "reply"})
        f.act("ooa.string/replace", desc="Fill in the case", text="$reply$", find="{case}", replace_with="$rec.case_key$", out={"result": "reply"})
        f.act("ooa.string/replace", desc="Fill in severity and type", text={"expr": "replace($reply$, '{severity}', $rec.severity$)"}, find="{type}",
              replace_with="$rec.type$", out={"result": "reply"})
        f.act("ooa.string/replace", desc="Fill in team and hours", text={"expr": "replace($reply$, '{group}', 'OOA-QA Support')"}, find="{hours}",
              replace_with="24", out={"result": "reply"})
        f.act("ooa.string/replace", desc="Fill in the facts", text="$reply$", find="{facts}", replace_with="Our records for case ${rec.case_key} are attached to the ticket.",
              out={"result": "reply"})
        f.act("ooa.string/count_occurrences", desc="Scan for card numbers left in the text", text={"expr": "replace($reply$, ' ', '')"}, find="4111111111111111",
              out={"count": "pii"})
        with f.if_("$pii$ > 0", desc="Personal data in the reply?"):
            f.act("core/throw", desc="Hold it", code="POLICY.PII_IN_REPLY", message="The reply for ${rec.case_key} contains a card number")
        with f.if_({"op": "eq", "left": "$rec.approver$", "right": "agent"}, desc="Would the agent approve its own reply?"):
            f.act("core/throw", desc="Enforce separation of duties", code="POLICY.SEPARATION_OF_DUTIES", message="The reply for ${rec.case_key} needs a reviewer other than the agent")
        f.set("decision", "$rec.decision$", desc="The approver's decision")
        f.set("seen", 0, desc="Nothing sent yet")
        with f.if_({"op": "eq", "left": "$decision$", "right": "decline"}, desc="Declined?") as dec:
            f.act("ooa.log/write", desc="Log the decline (nothing is sent)", path="$log_file$", message="${rec.case_key}: declined by ${rec.approver}; not sent")
        with f.else_(dec):
            with f.if_("not endsWith(lower($rec.to$), '@ooa-qa.test')", desc="Only the QA tenant's addresses"):
                f.act("core/throw", desc="Block an outside recipient", code="POLICY.EXTERNAL_RECIPIENT", message="${rec.to} is outside the QA tenant; not sent")
            f.act("ooa.microsoft365/send_mail", desc="Send the approved reply to the QA mailbox", to="$rec.to$", subject="OOA-QA ${run_id} Re: case ${rec.case_key}",
                  body="$reply$", **graph(MAILBOX))
            f.act("ooa.microsoft365/list_mail", desc="Reconcile: find it in the recipient's mailbox", folder="inbox", subject_contains="OOA-QA ${run_id} Re: case ${rec.case_key}",
                  max_results=5, out={"table": "got", "count": "seen"}, **graph("$rec.to$"))
            with f.if_("$seen$ == 0", desc="Not delivered?"):
                f.act("core/throw", desc="Report it", code="VERIFY.NOT_DELIVERED", message="The reply for ${rec.case_key} did not arrive")
        f.act("ooa.log/write", desc="Log the approval decision", path="$log_file$", message="${rec.case_key}: ${decision} by ${rec.approver}; delivered ${seen}")
        f.act("ooa.datatable/add_row", desc="Record the result", table="$evidence$",
              values={"key": "$rec.key$", "case": "$rec.case_key$", "to": "$rec.to$", "decision": "$decision$", "approver": "$rec.approver$",
                      "sent": {"expr": "$decision$ == 'approve'"}, "delivered": "$seen$"}, out={"table": "evidence"})
        f.set("rec_note", "${decision}; delivered ${seen}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b6_excel_reconciler():
    c = Child("RW01", 6, "Excel and audit reconciler",
              "Pulls the other agents' evidence for every case, compares the ids, works out the aging, flags conflicts, builds an "
              "Excel summary with a formula, reads it back to verify the total, exports a CSV and checks that no file holds the secret canary.",
              "10 case bundles", data_file="RW01/cases.csv")
    f = c.f
    src = {"b1": "RW01-B1", "b2": "RW01-B2", "b3": "RW01-B3", "b4": "RW01-B4", "b5": "RW01-B5"}
    for k in src:
        f.var(k, "Table", "local", {"columns": ["key", "case"], "rows": []})
    for n, t, d in [("type", "String", ""), ("inc", "String", ""), ("jira_key", "String", ""), ("reply", "String", ""), ("verdict", "String", ""),
                    ("age", "Number", 0), ("arrived", "String", ""), ("complete", "Boolean", False),
                    ("summary", "Table", {"columns": ["case", "type", "incident", "jira", "reply", "public_check", "age_hours", "complete"], "rows": []}),
                    ("total", "Any", None), ("canary", "String", ""), ("files", "List", None), ("file", "String", ""), ("text", "String", "")]:
        f.var(n, t, "local", d)
    f.var("workbook", "String", "output", "", "The Excel summary.")
    c.start()
    with c.prepare("Load each agent's evidence for this run (a missing file stays an empty table)."):
        for k, code in src.items():
            with f.if_({"condition": "ooa.file/exists", "args": {"path": "${base}/output/${run_id}/" + code + "/evidence.csv"}}, desc=code + " evidence present?"):
                f.act("ooa.csv/read", desc="Read " + code + " evidence", path="${base}/output/${run_id}/" + code + "/evidence.csv", out={"table": k})
    with c.records("Per case: gather what each agent did, work out the age, flag conflicts and incomplete bundles, add the summary row.",
                   summary="${type} | ${inc} | ${jira_key} | ${reply}\nComplete: ${complete}"):
        f.act("ooa.datatable/lookup", desc="Intake: type", table="$b1$", column="case", value="$rec.key$", return_column="type", if_not_found="", out={"value": "type"})
        f.act("ooa.datatable/lookup", desc="ServiceNow: incident", table="$b3$", column="case", value="$rec.key$", return_column="number", if_not_found="", out={"value": "inc"})
        f.act("ooa.datatable/lookup", desc="Jira: issue", table="$b4$", column="case", value="$rec.key$", return_column="jira", if_not_found="", out={"value": "jira_key"})
        f.act("ooa.datatable/lookup", desc="Reply: decision", table="$b5$", column="case", value="$rec.key$", return_column="decision", if_not_found="", out={"value": "reply"})
        f.act("ooa.datatable/lookup", desc="Public research: verdict", table="$b2$", column="case", value="$rec.key$", return_column="verdict", if_not_found="", out={"value": "verdict"})
        f.act("ooa.datatable/lookup", desc="When the case arrived", table="$b1$", column="case", value="$rec.key$", return_column="received", if_not_found="", out={"value": "arrived"})
        f.set("age", 0, desc="Unknown age")
        with f.if_("not isEmpty($arrived$)", desc="Arrival known?"):
            f.act("ooa.datetime/difference", desc="Aging since the case arrived (hours)", start="$arrived$", end={"expr": "now()"}, unit="hours", out={"result": "age"})
        f.expr("complete", "not isEmpty($inc$) and not isEmpty($reply$)", desc="Complete bundle: an incident and a reply")
        with f.if_({"op": "contains", "left": "$verdict$", "right": "hold"}, desc="Public source differs from the knowledge base?"):
            f.act("ooa.log/write", desc="Flag the conflict", path="$log_file$", level="WARN", message="${rec.key}: public source differs from the knowledge base")
        f.act("ooa.datatable/add_row", desc="Add the summary row", table="$summary$",
              values={"case": "$rec.key$", "type": "$type$", "incident": "$inc$", "jira": "$jira_key$", "reply": "$reply$", "public_check": "$verdict$",
                      "age_hours": {"expr": "round($age$, 1)"}, "complete": "$complete$"}, out={"table": "summary"})
        f.set("rec_note", "complete ${complete}", desc="Note for the results")
    with c.finish("Build the Excel summary, verify it by reading it back, export the CSV and scan for the canary."):
        f.set("workbook", "${out_dir}/RW01-summary.xlsx", desc="Name of the workbook")
        f.act("ooa.excel/create_workbook", desc="Create the summary workbook", path="$workbook$", overwrite=True, sheet="Summary")
        f.act("ooa.excel/write_table", desc="Write the summary table", table="$summary$", sheet="Summary", start_cell="A1")
        f.act("ooa.excel/set_formula", desc="Count the rows (Excel formula)", cell="J1", formula="COUNTA(A2:A11)", sheet="Summary")
        f.act("ooa.excel/save", desc="Save the workbook")
        f.act("ooa.excel/get_cell", desc="Read the total back", cell="J1", sheet="Summary", out={"value": "total"})
        f.act("ooa.excel/close", desc="Close the workbook")
        with f.if_("toNumber($total$) != len($summary$)", desc="Does the workbook's total match?"):
            f.act("core/throw", desc="Stop: the workbook does not match", code="VERIFY.TOTALS", message="Workbook total ${total} differs from the table")
        f.act("ooa.datatable/write_to_file", desc="Export the sanitized CSV", table="$summary$", path="${out_dir}/RW01-summary.csv")
        f.act("ooa.global_values/get", desc="The secret canary", name="rw_canary", default="", type="String", out={"value": "canary"})
        f.act("ooa.file/list", desc="Every file of this run", folder="${base}/output/${run_id}", pattern="*.csv", include_subfolders=True, out={"files": "files"})
        with f.loop(desc="Each file", iterator="list", over="$files$", **{"as": "file"}):
            f.act("ooa.file/read_text", desc="Read it", path="$file$", out={"text": "text"})
            with f.if_({"all": [{"op": "ne", "left": "$canary$", "right": ""}, {"op": "contains", "left": "$text$", "right": "$canary$"}]}, desc="Canary inside?"):
                f.act("core/throw", desc="Stop: a secret leaked", code="SECURITY.CANARY_FOUND", message="The secret canary is in ${file}")
        f.box("Workbook: ${workbook}\nRows (Excel formula): ${total}", title="RW01-B6 workbook", seconds=3)
    return c


def main():
    m = Main("RW01", "Customer support", "Customer support: public research -> ServiceNow -> Salesforce -> Jira -> Microsoft 365.",
             [("rw01_b1", "RW01-B1", "Case intake and dedup"), ("rw01_b2", "RW01-B2", "Public knowledge research"),
              ("rw01_b3", "RW01-B3", "ServiceNow case worker"), ("rw01_b4", "RW01-B4", "Salesforce and Jira linker"),
              ("rw01_b5", "RW01-B5", "Human review and messaging"), ("rw01_b6", "RW01-B6", "Excel and audit reconciler")], FOLDER)
    return m.build()


CHILDREN = [b1_case_intake, b2_public_research, b3_servicenow_worker, b4_salesforce_jira, b5_review_messaging, b6_excel_reconciler]
MAINS = [main]
