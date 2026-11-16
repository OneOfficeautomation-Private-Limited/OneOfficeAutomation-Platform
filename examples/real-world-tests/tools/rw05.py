"""RW05 Quarterly access review: Entra ID/AD + Salesforce + Jira + ServiceNow + Slack + Okta (child agents and main).

Only tagged QA principals (qa.*@ooa-qa.test in the QA OU) are read or changed; every change needs two approvals, is checked
after it is made, and B6 puts the baseline back. Every RW05 agent keys its records by the identity key I01 ... I10.
"""
from common import Child, Main, cred, evidence_var, fault_setup, graph, save_evidence, sf, sn

FOLDER = "RW05 Access review"
RETRY = {"max": 3, "backoff": "exponential", "delayMs": 1000}
GRAPH = "${standin}/graph/v1.0"
POLICY_VERSION = "v3"


def g_api(path, method="GET", **kw):
    """A Microsoft Graph call through the REST toolkit (the Microsoft 365 toolkit has no Entra user and group commands)."""
    return dict(method=method, url=GRAPH + path, bearer_token=cred("graph"), retry=RETRY, **kw)


def okta():
    return {"org_url": "${standin}/okta", "api_token": cred("okta")}


def jira_api(path, method="GET", **kw):
    return dict(method=method, url="${standin}" + path, bearer_token=cred("jira"), retry=RETRY, **kw)


def run_file(code, name="evidence.csv"):
    return "${base}/output/${run_id}/" + code + "/" + name


def read_prev(f, code, var, name="evidence.csv", what="evidence"):
    with f.if_({"condition": "ooa.file/exists", "args": {"path": run_file(code, name)}}, desc=f"{code} ran in this run?") as has:
        f.act("ooa.csv/read", desc=f"Read {code} {what}", path=run_file(code, name), out={"table": var})
    with f.else_(has):
        f.comment(f"Running on its own: use the fixture, a saved copy of {code}'s {what} from a checked run.")
        f.act("ooa.csv/read", desc=f"Read {code} {what} (fixture)", path="${base}/data/RW05/fixtures/" + code + "-" + name, out={"table": var})


def qa_guard(f, upn_expr="$rec.upn$"):
    """Fail closed: only tagged QA principals."""
    with f.if_(f"not (startsWith(lower({upn_expr}), 'qa.') and endsWith(lower({upn_expr}), '@ooa-qa.test'))", desc="Not a tagged QA principal?"):
        f.act("core/throw", desc="Fail closed", code="SCOPE.NOT_QA", message="${rec.key}: not a tagged QA principal; nothing is read or changed")


# ------------------------------------------------------------------ B1
def b1_census():
    c = Child("RW05", 1, "Identity census",
              "Pages through the QA principals in Entra ID (and AD when a test domain is given), normalizes their immutable ids, reads enabled "
              "state, groups and last sign-in, expands nested groups with a cycle limit, tags stale access, masks attributes, stores a hashed "
              "snapshot in the review database and fails closed for anyone outside the QA OU.",
              "10 synthetic identities", data_file="RW05/principals.csv")
    f = c.f
    f.var("ad_server", "String", "input", "", "LDAP address of an isolated QA domain (ldaps://...); empty: AD is skipped (no test domain here).")
    f.var("stale_days", "Number", "input", 90, "No sign-in for more than this many days is stale.")
    for n, t, d in [("url", "String", ""), ("page", "Dictionary", None), ("all", "List", []), ("pages", "Number", 0), ("groups", "Dictionary", None),
                    ("gname", "Dictionary", {}), ("gi", "Dictionary", None), ("u", "Dictionary", None), ("mo", "Dictionary", None), ("direct", "List", []),
                    ("queue", "List", []), ("qi", "Number", 0), ("gid", "String", ""), ("seen", "List", []), ("cycles", "Number", 0), ("parents", "Dictionary", None),
                    ("pg", "Dictionary", None), ("effective", "List", []), ("days", "Number", 0), ("stale", "Boolean", False), ("masked", "String", ""),
                    ("immutable", "String", ""), ("snap", "String", ""), ("hash", "String", ""), ("ad", "Table", None), ("db", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "upn", "immutable_id", "enabled", "manager", "last_sign_in", "days", "stale", "direct_groups", "effective_groups", "cycles",
                     "masked", "hash"])
    c.start()
    with c.prepare("Page through the QA principals (4 per page), read the group names, open the review database; AD only with a test domain."):
        fault_setup(f, "graph")
        f.set("url", GRAPH + "/users?$top=4&$filter=startswith(userPrincipalName,'qa.')", desc="First page of QA principals")
        with f.loop(desc="1. Each page (follow @odata.nextLink)", iterator="while", condition="not isEmpty($url$)", max_iterations=20):
            f.act("ooa.rest/request", desc="Read a page", url="$url$", bearer_token=cred("graph"), retry=RETRY, out={"json": "page"})
            f.act("ooa.list/append_list", desc="Add its principals", list="$all$", other="$page.value$", out={"list": "all"})
            f.expr("url", "get($page$, '@odata.nextLink', '')", desc="Next page, if any")
            f.expr("pages", "$pages$ + 1", desc="Count the page")
        f.act("ooa.rest/request", desc="All groups (names)", out={"json": "groups"}, **g_api("/groups"))
        with f.loop(desc="Group id -> name", iterator="list", over="$groups.value$", **{"as": "gi"}):
            f.act("ooa.dictionary/put", desc="Remember the name", dictionary="$gname$", key="$gi.id$", value="$gi.displayName$", out={"dictionary": "gname"})
        f.set("db", "${out_dir}/review", desc="Review database of this run")
        f.act("ooa.database/connect", desc="Open the review database (embedded H2; live: the SQL entitlement store)", database_type="h2", file="$db$", session="rev")
        f.act("ooa.database/execute", desc="Fresh snapshot table", sql="DROP TABLE IF EXISTS snapshot", session="rev")
        f.act("ooa.database/execute", desc="Create it", session="rev",
              sql="CREATE TABLE snapshot(pkey VARCHAR(5), immutable_id VARCHAR(40), enabled BOOLEAN, effective VARCHAR(400), stale BOOLEAN, hash VARCHAR(64), taken TIMESTAMP)")
        with f.if_("not isEmpty($ad_server$)", desc="A QA test domain given?"):
            f.act("ooa.active_directory/connect", desc="Sign in to the QA domain", url="$ad_server$", base_dn="OU=QA,DC=ooa-qa,DC=test", bind_user="svc-ooa-qa",
                  password=cred("ad", "password"))
            f.act("ooa.active_directory/search", desc="1. QA principals in AD", filter="(&(objectClass=user)(userPrincipalName=qa.*))",
                  attributes=["userPrincipalName", "objectGUID", "userAccountControl", "lastLogonTimestamp", "memberOf"], out={"table": "ad"})
        f.act("ooa.log/write", desc="Census source", path="$log_file$", message="Entra: ${len($all$)} QA principals in ${pages} pages")
    with c.records("Per identity: QA OU check, immutable id, state and groups, nested groups (cycle limit), stale tag, masking, hashed snapshot.",
                   summary="${masked}: enabled ${u.accountEnabled}, ${days} days since sign-in\nGroups: ${join($effective$, ', ')}\nCycles: ${cycles}"):
        qa_guard(f)
        f.act("ooa.rest/request", desc="3. Read the identity", out={"json": "u"}, **g_api("/users/${rec.upn}"))
        with f.if_("not contains(coalesce($u.onPremisesDistinguishedName$, ''), ',OU=QA,')", desc="9. Outside the QA OU?"):
            f.act("core/throw", desc="Fail closed", code="SCOPE.OUTSIDE_QA_OU", message="${rec.upn} is outside the QA OU: nothing is read further")
        f.expr("immutable", "lower(trim($u.id$))", desc="2. Normalize the immutable id")
        f.act("ooa.rest/request", desc="3. Direct groups", out={"json": "mo"}, **g_api("/users/${u.id}/memberOf"))
        f.set("direct", [], desc="Start the direct groups")
        f.set("queue", [], desc="Start the expansion queue")
        with f.loop(desc="Each direct group", iterator="list", over="$mo.value$", **{"as": "gi"}):
            f.act("ooa.list/add_item", desc="Direct group name", list="$direct$", item="$gi.displayName$", out={"list": "direct"})
            f.act("ooa.list/add_item", desc="Queue it for expansion", list="$queue$", item="$gi.id$", out={"list": "queue"})
        for v, val in [("seen", []), ("qi", 0), ("cycles", 0)]:
            f.set(v, val, desc="Expansion: " + v)
        with f.loop(desc="4. Expand nested groups (stops at 20 groups: cycle limit)", iterator="while", condition="$qi$ < len($queue$) and $qi$ < 20", max_iterations=25):
            f.expr("gid", "$queue$[$qi$]", desc="Next group")
            f.expr("qi", "$qi$ + 1", desc="Move on")
            with f.if_({"op": "contains", "left": "$seen$", "right": "$gid$"}, desc="Seen already (a cycle)?") as cyc:
                f.expr("cycles", "$cycles$ + 1", desc="Count the cycle")
            with f.else_(cyc):
                f.act("ooa.list/add_item", desc="Mark it seen", list="$seen$", item="$gid$", out={"list": "seen"})
                f.act("ooa.rest/request", desc="Groups this group is in", out={"json": "parents"}, **g_api("/groups/${gid}/memberOf"))
                with f.loop(desc="Each parent group", iterator="list", over="$parents.value$", **{"as": "pg"}):
                    f.act("ooa.list/add_item", desc="Queue the parent", list="$queue$", item="$pg.id$", out={"list": "queue"})
        f.set("effective", [], desc="Start the effective groups")
        with f.loop(desc="5. Effective group names", iterator="list", over="$seen$", **{"as": "gid"}):
            f.act("ooa.list/add_item", desc="Add the name", list="$effective$", item={"expr": "get($gname$, $gid$, $gid$)"}, out={"list": "effective"})
        f.expr("days", "round(dateDiff(coalesce($u.signInActivity.lastSignInDateTime$, '2000-01-01T00:00:00Z'), now(), 'days'), 0)", desc="3. Days since the last sign-in")
        f.expr("stale", "$days$ > $stale_days$", desc="6. Tag stale access")
        f.set("masked", "${substring($rec.upn$, 0, 4)}***@ooa-qa.test", desc="7. Mask the sign-in name for logs")
        f.act("ooa.list/sort", desc="Groups in a fixed order (stable hash)", list="$effective$", out={"list": "effective"})
        f.set("snap", "${immutable}|${u.accountEnabled}|${join($effective$, ';')}|${stale}", desc="5. The membership snapshot")
        f.act("ooa.crypto/hash_text", desc="8. Snapshot hash", text="$snap$", out={"hash": "hash"})
        f.act("ooa.database/execute", desc="8. Store the snapshot", session="rev", sql="INSERT INTO snapshot VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
              parameters=["$rec.key$", "$immutable$", "$u.accountEnabled$", {"expr": "join($effective$, ';')"}, "$stale$", "$hash$"])
        f.act("ooa.datatable/add_row", desc="Census row", table="$evidence$",
              values={"key": "$rec.key$", "upn": "$rec.upn$", "immutable_id": "$immutable$", "enabled": "$u.accountEnabled$", "manager": "$u.manager$",
                      "last_sign_in": "$u.signInActivity.lastSignInDateTime$", "days": "$days$", "stale": "$stale$", "direct_groups": {"expr": "join($direct$, ';')"},
                      "effective_groups": {"expr": "join($effective$, ';')"}, "cycles": "$cycles$", "masked": "$masked$", "hash": "$hash$"}, out={"table": "evidence"})
        f.act("ooa.log/write", desc="Log (masked)", path="$log_file$", message="${masked}: ${len($effective$)} groups, ${days} days, stale ${stale}")
        f.set("rec_note", "${len($effective$)} groups${if($cycles$ > 0, ', cycle', '')}${if($stale$, ', stale', '')}", desc="Note for the results")
    with c.finish():
        f.act("ooa.database/disconnect", desc="Close the review database", session="rev")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B2
def b2_collectors():
    c = Child("RW05", 2, "SaaS entitlement collectors",
              "For each QA principal reads the Salesforce permission sets, Jira groups, ServiceNow roles, Slack account and Okta groups (metadata "
              "only), maps the app identities, flags missing or duplicate mappings, pages through the APIs within their rate limits, records the "
              "source time, checks the Salesforce tenant is the sandbox and lists orphan app accounts.",
              "10 principals x 5 SaaS systems")
    f = c.f
    for n, t, d in [("org", "Table", None), ("sbx", "String", ""), ("all_slack", "List", []), ("cursor", "String", "first"), ("sl", "Dictionary", None),
                    ("sfu", "Table", None), ("sfid", "String", ""), ("psa", "Table", None), ("ps", "List", None), ("jacc", "List", None), ("jg", "List", None),
                    ("snu", "Table", None), ("snid", "String", ""), ("snr", "Table", None), ("roles", "List", None), ("slack_user", "Dictionary", None),
                    ("okg", "List", None), ("x", "String", ""), ("actual", "List", []), ("dup", "String", ""), ("missing", "List", []), ("at", "String", ""),
                    ("emails", "List", []), ("orphans", "List", []), ("acc", "Dictionary", None), ("item", "Dictionary", None), ("b1", "Table", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "upn", "grants", "direct_groups", "manager", "sf_user", "jira_accounts", "sn_user", "slack_user", "okta", "mapping",
                     "collected_at", "days"])
    c.start()
    with c.prepare("Read the census, check the Salesforce tenant is the sandbox (9.), and page through Slack's members for the orphan check."):
        fault_setup(f, "salesforce")
        read_prev(f, "RW05-B1", "records")
        f.act("ooa.csv/read", desc="All QA principals (for the orphan check)", path="${base}/data/RW05/principals.csv", out={"table": "b1"})
        f.act("ooa.datatable/column_to_list", desc="Their emails", table="$b1$", column="upn", out={"list": "emails"})
        f.act("ooa.salesforce/query", desc="9. Which Salesforce org is this?", soql="SELECT Id, IsSandbox FROM Organization", out={"table": "org"}, retry=RETRY, **sf())
        f.act("ooa.datatable/get_value", desc="Sandbox?", table="$org$", row=0, column="IsSandbox", out={"value": "sbx"})
        with f.if_({"op": "ne", "left": "${sbx}", "right": "true"}, desc="Not a sandbox?"):
            f.act("core/throw", desc="Stop: production tenant", code="SCOPE.NOT_SANDBOX", message="The Salesforce org is not a sandbox")
        with f.loop(desc="5. Page through Slack members (cursor)", iterator="while", condition="not isEmpty($cursor$)", max_iterations=20):
            f.act("ooa.rest/request", desc="A page of members", method="POST", url="${standin}/slack/api/users.list", bearer_token=cred("slack"), retry=RETRY,
                  form={"limit": "4", "cursor": {"expr": "if($cursor$ == 'first', '', $cursor$)"}}, out={"json": "sl"})
            f.act("ooa.list/append_list", desc="Add them", list="$all_slack$", other="$sl.members$", out={"list": "all_slack"})
            f.expr("cursor", "coalesce($sl.response_metadata.next_cursor$, '')", desc="Next cursor")
            f.act("core/delay", desc="6. Respect the rate limit", seconds=300, unit="milliseconds")
    with c.records("Per principal: each app's account and grants (metadata only), mapping check, source time, snapshot.",
                   summary="${rec.upn}\n${len($actual$)} grants  mapping: ${dup}"):
        qa_guard(f)
        f.set("actual", [], desc="Start the grants")
        with f.loop(desc="Entra groups from the census", iterator="list", over={"expr": "split($rec.effective_groups$, ';')"}, **{"as": "x"}):
            with f.if_("not isEmpty($x$)", desc="A group"):
                f.act("ooa.list/add_item", desc="entra grant", list="$actual$", item="entra:${x}", out={"list": "actual"})
        f.set("dup", "ok", desc="Mapping ok so far")
        f.act("ooa.salesforce/query", desc="1.-2. Salesforce user by email", soql="SELECT Id FROM User WHERE Email = '${rec.upn}'", out={"table": "sfu"}, retry=RETRY, **sf())
        f.act("ooa.datatable/get_value", desc="Its id", table="$sfu$", row=0, column="Id", out={"value": "sfid"})
        f.act("ooa.salesforce/query", desc="1. Its permission sets", soql="SELECT Id, PermissionSet.Name FROM PermissionSetAssignment WHERE AssigneeId = '${sfid}'",
              out={"table": "psa"}, retry=RETRY, **sf())
        f.set("ps", [], desc="No permission sets yet")
        with f.if_({"condition": "ooa.datatable/row_count_is", "args": {"table": "$psa$", "operator": "gt", "count": 0}}, desc="Any assignments?"):
            f.act("ooa.datatable/column_to_list", desc="Names", table="$psa$", column="PermissionSet.Name", skip_empty=True, out={"list": "ps"})
        with f.loop(desc="Each permission set", iterator="list", over="$ps$", **{"as": "x"}):
            f.act("ooa.list/add_item", desc="sf grant", list="$actual$", item="sf:${x}", out={"list": "actual"})
        f.act("ooa.rest/request", desc="1.-2. Jira accounts with this email", out={"json": "jacc"}, **jira_api("/rest/api/2/user/search/qa", query={"query": "$rec.upn$"}))
        with f.if_("len($jacc$) > 1", desc="3. More than one Jira account?") as jd:
            f.set("dup", "duplicate Jira accounts (${len($jacc$)})", desc="Mapping problem")
        with f.elif_(jd, "len($jacc$) == 0"):
            f.set("dup", "no Jira account", desc="Mapping problem")
        with f.if_("len($jacc$) > 0", desc="Has a Jira account"):
            f.act("ooa.rest/request", desc="1. Its Jira groups", out={"json": "jg"}, **jira_api("/rest/api/2/user/groups", query={"accountId": {"expr": "first($jacc$).accountId"}}))
            with f.loop(desc="Each group", iterator="list", over="$jg$", **{"as": "acc"}):
                f.act("ooa.list/add_item", desc="jira grant", list="$actual$", item="jira:${acc.name}", out={"list": "actual"})
        f.act("ooa.servicenow/query", desc="1.-2. ServiceNow user by email", table="sys_user", query="email=${rec.upn}", max_records=2, out={"table": "snu"}, retry=RETRY, **sn())
        f.act("ooa.datatable/get_value", desc="Its sys_id", table="$snu$", row=0, column="sys_id", out={"value": "snid"})
        f.act("ooa.servicenow/query", desc="1. Its roles", table="sys_user_has_role", query="user=${snid}", out={"table": "snr"}, retry=RETRY, **sn())
        f.set("roles", [], desc="No roles yet")
        with f.if_({"condition": "ooa.datatable/row_count_is", "args": {"table": "$snr$", "operator": "gt", "count": 0}}, desc="Any roles?"):
            f.act("ooa.datatable/column_to_list", desc="Role names", table="$snr$", column="role", skip_empty=True, out={"list": "roles"})
        with f.loop(desc="Each role", iterator="list", over="$roles$", **{"as": "x"}):
            f.act("ooa.list/add_item", desc="sn grant", list="$actual$", item="sn:${x}", out={"list": "actual"})
        f.act("ooa.slack/get_user_details", desc="1.-2. Slack account by email", bot_token=cred("slack"), user="$rec.upn$", base_url="${standin}/slack/api", retry=RETRY,
              out={"user": "slack_user"})
        f.act("ooa.okta/list_user_groups", desc="1. Okta groups", user="$rec.upn$", retry=RETRY, out={"names": "okg"}, **okta())
        with f.loop(desc="Each Okta group", iterator="list", over="$okg$", **{"as": "x"}):
            f.act("ooa.list/add_item", desc="okta grant", list="$actual$", item="okta:${x}", out={"list": "actual"})
        f.act("core/delay", desc="6. Respect the rate limits", seconds=200, unit="milliseconds")
        f.expr("at", "formatDate(now(), '%Y-%m-%dT%H:%M:%SZ')", desc="8. Source time")
        f.act("ooa.list/sort", desc="Grants in a fixed order", list="$actual$", out={"list": "actual"})
        f.act("ooa.json/write_file", desc="7. Snapshot of this principal (metadata only)", path="${out_dir}/snapshots/${rec.key}.json", pretty=True,
              value={"key": "$rec.key$", "grants": "$actual$", "mapping": "$dup$", "collected_at": "$at$"})
        f.act("ooa.datatable/add_row", desc="Entitlement row", table="$evidence$",
              values={"key": "$rec.key$", "upn": "$rec.upn$", "grants": {"expr": "join($actual$, ';')"}, "direct_groups": "$rec.direct_groups$", "manager": "$rec.manager$",
                      "sf_user": "$sfid$", "jira_accounts": {"expr": "len($jacc$)"}, "sn_user": "$snid$", "slack_user": "$slack_user.id$", "okta": {"expr": "len($okg$)"},
                      "mapping": "$dup$", "collected_at": "$at$", "days": "$rec.days$"}, out={"table": "evidence"})
        f.set("rec_note", "${len($actual$)} grants, mapping ${dup}", desc="Note for the results")
    with c.finish("3. Orphans: Slack accounts that belong to no QA principal; then save."):
        with f.loop(desc="Each Slack member", iterator="list", over="$all_slack$", **{"as": "item"}):
            with f.if_("not contains($emails$, $item.profile.email$)", desc="No principal for it?"):
                f.act("ooa.list/add_item", desc="An orphan", list="$orphans$", item="slack:${item.profile.email}", out={"list": "orphans"})
        f.act("ooa.file/write_text", desc="Save the orphans", path="${out_dir}/orphans.txt", text={"expr": "join($orphans$, '\\n')"})
        f.act("ooa.log/write", desc="Orphans", path="$log_file$", level="WARN", message="Orphan app accounts: ${join($orphans$, ', ')}")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B3
def b3_policy():
    c = Child("RW05", 3, "Policy and SoD analyzer",
              "Joins each snapshot to the versioned role matrix, works out expected against actual grants, finds privileged extras (also those "
              "inherited through nested groups), SoD conflicts and dormant accounts, compares the manager with the role owner, classifies the "
              "severity, proposes least-privilege actions, sends ambiguous cases to review, keeps the baseline and gives reason codes.",
              "10 access profiles")
    f = c.f
    for n, t, d in [("assign", "Table", None), ("owners", "Table", None), ("rules", "Table", None), ("rule", "Dictionary", None), ("role", "String", ""),
                    ("owner", "String", ""), ("exp_t", "Table", None), ("expected", "List", None), ("actual", "List", None), ("direct", "List", None),
                    ("missing", "List", None), ("extra", "List", None), ("priv", "List", None), ("privx", "List", None), ("sod", "List", []), ("actions", "List", []),
                    ("reasons", "List", []), ("x", "String", ""), ("sev", "String", ""), ("version", "String", ""), ("db", "String", ""),
                    ("app_owners", "Table", None), ("known", "List", None), ("more", "List", None)]:
        f.var(n, t, "local", d)
    f.var("dormant_days", "Number", "input", 90, "No sign-in for longer than this: disable (with approval).")
    evidence_var(f, ["key", "upn", "role", "version", "owner", "manager", "expected", "actual", "missing", "extra", "privileged", "sod", "dormant",
                     "severity", "actions", "reasons"])
    c.start()
    with c.prepare("Read the entitlements, load the versioned role matrix and the rules into the review database, check the policy version."):
        read_prev(f, "RW05-B2", "records")
        f.act("ooa.global_values/get", desc="Policy version in force", name="rw05_policy_version", default=POLICY_VERSION, type="String", out={"value": "version"})
        f.set("db", "${out_dir}/policy", desc="Policy database of this run")
        f.act("ooa.database/connect", desc="Open the policy database (embedded H2)", database_type="h2", file="$db$", session="pol")
        f.act("ooa.database/execute", desc="Fresh matrix table", sql="DROP TABLE IF EXISTS matrix", session="pol")
        f.act("ooa.database/execute", desc="1. Load the versioned role matrix", session="pol",
              sql="CREATE TABLE matrix(version VARCHAR(5), role VARCHAR(30), grant_ VARCHAR(60), privileged VARCHAR(3)) AS SELECT * FROM CSVREAD('${base}/data/RW05/role_matrix.csv')")
        f.act("ooa.database/execute", desc="Fresh baseline table", sql="DROP TABLE IF EXISTS baseline", session="pol")
        f.act("ooa.database/execute", desc="8. Baseline table", sql="CREATE TABLE baseline(pkey VARCHAR(5), grants VARCHAR(600), taken TIMESTAMP)", session="pol")
        f.act("ooa.csv/read", desc="Who has which role", path="${base}/data/RW05/assignments.csv", out={"table": "assign"})
        f.act("ooa.csv/read", desc="Role owners", path="${base}/data/RW05/role_owners.csv", out={"table": "owners"})
        f.act("ooa.csv/read", desc="App owners", path="${base}/data/RW05/app_owners.csv", out={"table": "app_owners"})
        f.act("ooa.datatable/column_to_list", desc="Known managers: the role owners", table="$owners$", column="owner", out={"list": "known"})
        f.act("ooa.datatable/column_to_list", desc="and the app owners", table="$app_owners$", column="owner", out={"list": "more"})
        f.act("ooa.list/append_list", desc="One list", list="$known$", other="$more$", out={"list": "known"})
        f.act("ooa.csv/read", desc="SoD rules", path="${base}/data/RW05/sod_rules.csv", out={"table": "rules"})
        f.act("ooa.csv/read", desc="Privileged grants", path="${base}/data/RW05/privileged.csv", out={"table": "exp_t"})
        f.act("ooa.datatable/column_to_list", desc="As a list", table="$exp_t$", column="grant", out={"list": "priv"})
    with c.records("Per profile: expected vs actual, privileged and SoD, dormant, owner, severity, actions, review or proposal.",
                   summary="${rec.upn} (${role})\nSeverity ${sev}\nActions: ${join($actions$, '; ')}"):
        f.act("ooa.datatable/lookup", desc="1. The principal's role", table="$assign$", column="key", value="$rec.key$", return_column="role", out={"value": "role"})
        f.act("ooa.database/query", desc="2. Expected grants (matrix ${version})", session="pol", parameters=["$role$", "$version$"], out={"table": "exp_t"},
              sql='SELECT grant_ AS "grant" FROM matrix WHERE role = ? AND version = ?')
        f.act("ooa.datatable/column_to_list", desc="As a list", table="$exp_t$", column="grant", out={"list": "expected"})
        f.expr("actual", "split($rec.grants$, ';')", desc="2. Actual grants")
        f.expr("direct", "split(coalesce($rec.direct_groups$, ''), ';')", desc="Direct Entra groups")
        f.act("ooa.list/subtract", desc="2. Missing grants", list="$expected$", other="$actual$", out={"list": "missing"})
        f.act("ooa.list/subtract", desc="2. Extra grants", list="$actual$", other="$expected$", out={"list": "extra"})
        f.act("ooa.list/intersect", desc="3. Privileged extras", list="$extra$", other="$priv$", out={"list": "privx"})
        for v in ("sod", "actions", "reasons"):
            f.set(v, [], desc="Start: " + v)
        with f.loop(desc="3. Each SoD rule", iterator="table_rows", over="$rules$", **{"as": "rule"}):
            with f.if_({"all": [{"op": "contains", "left": "$actual$", "right": "$rule.grant_a$"}, {"op": "contains", "left": "$actual$", "right": "$rule.grant_b$"}]},
                       desc="Both grants held?"):
                f.act("ooa.list/add_item", desc="SoD conflict", list="$sod$", item="${rule.grant_a} + ${rule.grant_b}", out={"list": "sod"})
                f.act("ooa.list/add_item", desc="6. Remove the second grant", list="$actions$", item="remove ${rule.grant_b}", out={"list": "actions"})
                f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="SOD_CONFLICT", out={"list": "reasons"})
        with f.loop(desc="6. Each extra grant", iterator="list", over="$extra$", **{"as": "x"}):
            with f.if_("startsWith($x$, 'entra:') and not contains($direct$, substring($x$, 6))", desc="Inherited through a nested group?") as inh:
                f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="INHERITED_${upper(substring($x$, 6))}", out={"list": "reasons"})
            with f.else_(inh):
                with f.if_("not contains($actions$, 'remove ' + $x$)", desc="Not already proposed"):
                    f.act("ooa.list/add_item", desc="Remove the extra grant", list="$actions$", item="remove ${x}", out={"list": "actions"})
                    f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item={"expr": "if(contains($priv$, $x$), 'PRIVILEGED_EXTRA', 'EXTRA_GRANT')"}, out={"list": "reasons"})
        with f.loop(desc="6. Each missing grant", iterator="list", over="$missing$", **{"as": "x"}):
            f.act("ooa.list/add_item", desc="Add it", list="$actions$", item="add ${x}", out={"list": "actions"})
            f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="MISSING_GRANT", out={"list": "reasons"})
        with f.if_("toNumber($rec.days$) > $dormant_days$", desc="3. Dormant?"):
            f.act("ooa.list/add_item", desc="Disable the account", list="$actions$", item="disable account", out={"list": "actions"})
            f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="DORMANT", out={"list": "reasons"})
        f.act("ooa.datatable/lookup", desc="4. Role owner", table="$owners$", column="role", value="$role$", return_column="owner", out={"value": "owner"})
        f.expr("sev", "if(len($privx$) > 0 or len($sod$) > 0 or contains($reasons$, 'INHERITED_GRP-QA-ADMINS'), 'high', if(contains($reasons$, 'DORMANT'), 'medium', "
                      "if(len($actions$) > 0, 'low', 'none')))", desc="5. Severity by rule")
        f.act("ooa.database/execute", desc="8. Keep the baseline", session="pol", sql="INSERT INTO baseline VALUES (?, ?, CURRENT_TIMESTAMP)", parameters=["$rec.key$", "$rec.grants$"])
        f.act("ooa.datatable/add_row", desc="9. Profile with reason codes", table="$evidence$",
              values={"key": "$rec.key$", "upn": "$rec.upn$", "role": "$role$", "version": "$version$", "owner": "$owner$", "manager": "$rec.manager$",
                      "expected": {"expr": "join($expected$, ';')"}, "actual": "$rec.grants$", "missing": {"expr": "join($missing$, ';')"},
                      "extra": {"expr": "join($extra$, ';')"}, "privileged": {"expr": "join($privx$, ';')"}, "sod": {"expr": "join($sod$, ';')"},
                      "dormant": {"expr": "toNumber($rec.days$) > $dormant_days$"}, "severity": "$sev$", "actions": {"expr": "join($actions$, ';')"},
                      "reasons": {"expr": "join($reasons$, ';')"}}, out={"table": "evidence"})
        with f.if_("not contains($known$, $rec.manager$) or $rec.mapping$ != 'ok'", desc="7. Ambiguous (4. unknown manager, or app mapping unclear)?"):
            f.act("core/throw", desc="Send it to review", code="ROUTE.REVIEW",
                  message="${rec.upn}: ${if($rec.mapping$ != 'ok', $rec.mapping$, 'manager ' + $rec.manager$ + ' is neither a role owner nor an app owner')}")
        f.set("rec_note", "${sev}: ${len($actions$)} action(s)", desc="Note for the results")
    with c.finish():
        f.act("ooa.database/query", desc="The baseline as kept", session="pol", sql='SELECT pkey AS "key", grants AS "grants" FROM baseline', out={"table": "exp_t"})
        f.act("ooa.datatable/write_to_file", desc="8. Save the baseline", table="$exp_t$", path="${out_dir}/baseline.csv")
        f.act("ooa.database/disconnect", desc="Close the policy database", session="pol")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B4
def b4_approvals():
    c = Child("RW05", 4, "Approval and change guard",
              "For each proposed change bundle: routes it to the app owner, makes sure the reviewer is not the requestor, needs a rationale for "
              "high-risk changes, locks the policy version, asks a second approver (two-person rule), blocks anything not approved, expires stale "
              "approvals, keeps the exact changeset hash, audits the decision and returns a signed approval token.",
              "10 proposed access bundles (those with changes)")
    f = c.f
    for n, t, d in [("app_owners", "Table", None), ("acts", "List", None), ("app", "String", ""), ("reviewer", "String", ""), ("rationale", "String", ""),
                    ("chash", "String", ""), ("r1", "Dictionary", None), ("r2", "Dictionary", None), ("st", "Dictionary", None), ("s1", "String", ""),
                    ("s2", "String", ""), ("tries", "Number", 0), ("token", "String", ""), ("exp", "Boolean", False), ("version", "String", ""),
                    ("subject", "String", ""), ("age", "Number", 0)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "upn", "actions", "changeset_hash", "reviewer", "second", "approval_1", "approval_2", "status", "policy_version", "token"])
    c.start()
    with c.prepare("Only bundles with changes; read the app owners; check the signing key has not expired (vault metadata)."):
        fault_setup(f, "approvals")
        read_prev(f, "RW05-B3", "records")
        f.act("ooa.datatable/filter", desc="Bundles that propose a change", table="$records$", column="actions", operator="not_empty", out={"table": "records"})
        f.act("ooa.csv/read", desc="App owners (reviewers)", path="${base}/data/RW05/app_owners.csv", out={"table": "app_owners"})
        f.act("ooa.global_values/get", desc="Policy version in force", name="rw05_policy_version", default=POLICY_VERSION, type="String", out={"value": "version"})
        f.act("ooa.vault/credential_expiry", desc="Signing key still valid? (vault metadata only)", credential="rw_qa_standins/sign_key", cont=True, out={"expired": "exp"})
    with c.records("Per bundle: owner as reviewer (not the requestor), rationale, policy lock, two approvals, block, expiry, hash, token.",
                   summary="${rec.upn}: ${rec.actions}\nReviewer ${reviewer}; answers ${s1} / ${s2}"):
        f.expr("acts", "split($rec.actions$, ';')", desc="The changes")
        f.expr("app", "split(split(first($acts$), ' ')[1], ':')[0]", desc="2. App of the first change")
        f.act("ooa.datatable/lookup", desc="2. Route by app owner", table="$app_owners$", column="app", value="$app$", return_column="owner",
              if_not_found="owner.entra@ooa-qa.test", out={"value": "reviewer"})
        with f.if_({"op": "eq", "left": "$reviewer$", "right": "$rec.manager$"}, desc="3. Reviewer is the requestor (the manager)?"):
            f.act("ooa.log/write", desc="Reroute", path="$log_file$", level="WARN", message="${rec.key}: ${reviewer} requested it; the security lead reviews instead")
            f.set("reviewer", "lead.security@ooa-qa.test", desc="Independent reviewer")
        f.set("rationale", "${rec.reasons}", desc="4. Rationale (the reason codes)")
        with f.if_({"all": [{"op": "eq", "left": "$rec.severity$", "right": "high"}, {"op": "empty", "left": "$rationale$"}]}, desc="High risk without a rationale?"):
            f.act("core/throw", desc="Block it", code="APPROVAL.NO_RATIONALE", message="${rec.key}: high-risk change without a rationale")
        with f.if_({"op": "ne", "left": "$rec.version$", "right": "$version$"}, desc="5. Proposed under another policy version?"):
            f.act("core/throw", desc="Block it", code="POLICY.VERSION", message="${rec.key}: proposed under ${rec.version}, policy is ${version}")
        f.act("ooa.crypto/hash_text", desc="8. Exact changeset hash", text="${rec.key}|${rec.version}|${rec.actions}", out={"hash": "chash"})
        f.set("subject", "${rec.key} ${first($acts$)}", desc="Review task subject")
        f.act("ooa.rest/request", desc="1. Review task for the app owner", method="POST", url="${standin}/approvals/requests", bearer_token=cred("servicenow"), retry=RETRY,
              json={"subject": "$subject$", "approver": "$reviewer$", "requester": "$rec.manager$", "changeset": "$chash$", "rationale": "$rationale$", "run": "$run_id$"},
              out={"json": "r1"})
        f.act("ooa.rest/request", desc="Second approver (two-person rule)", method="POST", url="${standin}/approvals/requests", bearer_token=cred("servicenow"), retry=RETRY,
              json={"subject": "${subject}#2", "approver": "lead.security2@ooa-qa.test", "requester": "$rec.manager$", "changeset": "$chash$", "run": "$run_id$"},
              out={"json": "r2"})
        f.set("s1", "pending", desc="Waiting")
        f.set("s2", "pending", desc="Waiting")
        f.set("tries", 0, desc="Start counting")
        with f.loop(desc="Wait for both answers", iterator="while", condition="($s1$ == 'pending' or $s2$ == 'pending') and $tries$ < 3", max_iterations=4):
            f.act("core/delay", desc="Wait a second", seconds=1)
            f.expr("tries", "$tries$ + 1", desc="Count")
            f.act("ooa.rest/request", desc="First answer", url="${standin}/approvals/requests/${r1.id}", bearer_token=cred("servicenow"), out={"json": "st"})
            f.set("s1", "$st.status$", desc="Its state")
            f.act("ooa.rest/request", desc="Second answer", url="${standin}/approvals/requests/${r2.id}", bearer_token=cred("servicenow"), out={"json": "st"})
            f.set("s2", "$st.status$", desc="Its state")
            f.expr("age", "dateDiff(coalesce(get($st$, 'decided'), now()), now(), 'hours')", desc="Age of the decision")
        with f.if_("$age$ > 24", desc="7. Stale approval (over a day old)?"):
            f.act("core/throw", desc="Expire it", code="APPROVAL.STALE", message="${rec.key}: approval is ${age} hours old; ask again")
        with f.if_("$s1$ != 'approved' or $s2$ != 'approved'", desc="6. Not approved by both?"):
            f.act("core/throw", desc="Block the change", code="APPROVAL.NOT_GRANTED", message="${rec.key}: reviewer ${s1}, second approver ${s2}: no change")
        f.act("ooa.crypto/hmac", desc="10. Signed approval token", text="${chash}|${r1.id}|${r2.id}", key=cred("sign_key", "value"), out={"signature": "token"})
        f.act("ooa.log/write", desc="9. Audit the decision", path="$log_file$", message="${rec.key}: approved by ${reviewer} and lead.security2; changeset ${substring($chash$, 0, 12)}")
        f.act("ooa.datatable/add_row", desc="Approved bundle", table="$evidence$",
              values={"key": "$rec.key$", "upn": "$rec.upn$", "actions": "$rec.actions$", "changeset_hash": "$chash$", "reviewer": "$reviewer$",
                      "second": "lead.security2@ooa-qa.test", "approval_1": "$r1.id$", "approval_2": "$r2.id$", "status": "approved", "policy_version": "$version$",
                      "token": "$token$"}, out={"table": "evidence"})
        f.set("rec_note", "approved (${len($acts$)} change(s))", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B5
def b5_remediation():
    c = Child("RW05", 5, "QA remediation executor",
              "For each approved changeset: checks the QA prefix, the tenant and the approval token before every write, removes or adds the "
              "approved group and role grants, disables the test identity only when approved, reads the app back, compares the change made with "
              "the approved one (undoing anything unexpected), keeps the response ids and stops on a host that is not QA.",
              "10 approved changesets (the approved ones)")
    f = c.f
    for n, t, d in [("acts", "List", None), ("a", "String", ""), ("verb", "String", ""), ("app", "String", ""), ("name", "String", ""), ("uid", "String", ""),
                    ("u", "Dictionary", None), ("tok", "String", ""), ("host", "String", ""), ("q", "Table", None), ("rid", "String", ""), ("ids", "List", []),
                    ("present", "Boolean", False), ("done", "List", []), ("groups", "Dictionary", None), ("gid", "String", ""), ("okg", "List", None),
                    ("sfid", "String", ""), ("snid", "String", ""), ("jacc", "List", None), ("jg", "List", None), ("cnt", "Number", 0),
                    ("grp", "Dictionary", None), ("mo", "Dictionary", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "upn", "applied", "response_ids", "verified"])
    c.start()
    with c.prepare("Only approved changesets; read the Entra groups (names to ids)."):
        fault_setup(f, "graph")
        read_prev(f, "RW05-B4", "records")
        f.act("ooa.rest/request", desc="Entra groups", out={"json": "groups"}, **g_api("/groups"))
    with c.records("Per changeset: guards, then each change: apply, read back, compare, undo if unexpected.",
                   summary="${rec.upn}\nApplied: ${join($done$, '; ')}"):
        f.act("ooa.crypto/hmac", desc="Recompute the approval token", text="${rec.changeset_hash}|${rec.approval_1}|${rec.approval_2}", key=cred("sign_key", "value"), out={"signature": "tok"})
        with f.if_({"op": "ne", "left": "$tok$", "right": "$rec.token$"}, desc="Token does not match?"):
            f.act("core/throw", desc="No change without a valid approval", code="APPROVAL.TOKEN", message="${rec.key}: the approval token does not match")
        f.expr("acts", "split($rec.actions$, ';')", desc="The approved changes")
        f.set("done", [], desc="Nothing applied yet")
        f.set("ids", [], desc="No response ids yet")
        f.act("ooa.rest/request", desc="The Entra identity", out={"json": "u"}, **g_api("/users/${rec.upn}"))
        with f.loop(desc="Each approved change", iterator="list", over="$acts$", **{"as": "a"}):
            qa_guard(f)
            f.expr("host", "regexExtract($standin$, '//([^:/]+)', 1)", desc="1. Target host")
            with f.if_({"op": "ne", "left": "$host$", "right": "127.0.0.1"}, desc="9. Host is not the QA tenant?"):
                f.act("core/throw", desc="Stop on host mismatch", code="SCOPE.HOST", message="Write target ${host} is not the QA tenant: stopped")
            f.set("rid", "", desc="No response id yet")
            f.set("gid", "", desc="No group yet")
            f.expr("verb", "split($a$, ' ')[0]", desc="add, remove or disable")
            f.expr("app", "if($verb$ == 'disable', 'account', split(split($a$, ' ')[1], ':')[0])", desc="Which app")
            f.expr("name", "if($verb$ == 'disable', '', substring(split($a$, ' ')[1], len($app$) + 1))", desc="Which grant")
            with f.switch("$app$", desc="2.-3. Apply the change in its app") as sw:
                with f.case(sw, "sf"):
                    f.act("ooa.salesforce/query", desc="Salesforce user", soql="SELECT Id FROM User WHERE Email = '${rec.upn}'", out={"table": "q"}, **sf())
                    f.act("ooa.datatable/get_value", desc="Its id", table="$q$", row=0, column="Id", out={"value": "sfid"})
                    f.act("ooa.salesforce/query", desc="The assignment", out={"table": "q"}, **sf(),
                          soql="SELECT Id FROM PermissionSetAssignment WHERE AssigneeId = '${sfid}' AND PermissionSet.Name = '${name}'")
                    f.act("ooa.datatable/get_value", desc="Its id", table="$q$", row=0, column="Id", out={"value": "rid"})
                    f.act("ooa.salesforce/query", desc="Confirm the record about to be removed is the approved grant", out={"table": "q", "count": "cnt"}, **sf(),
                          soql="SELECT Id FROM PermissionSetAssignment WHERE Id = '${rid}' AND PermissionSet.Name = '${name}'")
                    with f.if_("$cnt$ != 1", desc="Not the approved grant?"):
                        f.act("core/throw", desc="Remove nothing", code="REMEDIATE.WRONG_TARGET", message="${rec.key}: record ${rid} is not ${name}; nothing removed")
                    f.act("ooa.salesforce/delete_record", desc="Remove the permission set", object="PermissionSetAssignment", id="$rid$", retry=RETRY, **sf())
                    f.act("ooa.salesforce/query", desc="4. Read back", out={"table": "q"}, **sf(),
                          soql="SELECT Id FROM PermissionSetAssignment WHERE AssigneeId = '${sfid}' AND PermissionSet.Name = '${name}'")
                    f.act("ooa.datatable/row_count", desc="Still there?", table="$q$", out={"count": "cnt"})
                    f.expr("present", "$cnt$ > 0", desc="Present?")
                with f.case(sw, "sn"):
                    f.act("ooa.servicenow/query", desc="ServiceNow user", table="sys_user", query="email=${rec.upn}", out={"table": "q"}, **sn())
                    f.act("ooa.datatable/get_value", desc="Its sys_id", table="$q$", row=0, column="sys_id", out={"value": "snid"})
                    f.act("ooa.servicenow/query", desc="The role grant", table="sys_user_has_role", query="user=${snid}^role=${name}", out={"table": "q"}, **sn())
                    f.act("ooa.datatable/get_value", desc="Its sys_id", table="$q$", row=0, column="sys_id", out={"value": "rid"})
                    f.act("ooa.servicenow/delete_record", desc="Remove the role", table="sys_user_has_role", sys_id="$rid$", retry=RETRY, **sn())
                    f.act("ooa.servicenow/query", desc="4. Read back", table="sys_user_has_role", query="user=${snid}^role=${name}", out={"table": "q"}, **sn())
                    f.act("ooa.datatable/row_count", desc="Still there?", table="$q$", out={"count": "cnt"})
                    f.expr("present", "$cnt$ > 0", desc="Present?")
                with f.case(sw, "entra"):
                    f.set("gid", "", desc="Find the group id")
                    with f.loop(desc="Group by name", iterator="list", over="$groups.value$", **{"as": "grp"}):
                        with f.if_({"op": "eq", "left": "$grp.displayName$", "right": "$name$"}, desc="This one?"):
                            f.set("gid", "$grp.id$", desc="Its id")
                    f.act("ooa.rest/request", desc="Remove the membership", **g_api("/groups/${gid}/members/${u.id}/$ref", "DELETE"))
                    f.act("ooa.rest/request", desc="4. Read back the direct groups", out={"json": "mo"}, **g_api("/users/${u.id}/memberOf"))
                    f.expr("present", "contains(join($mo.value$, ','), $gid$)", desc="Still a member?")
                with f.case(sw, "okta"):
                    with f.if_({"op": "eq", "left": "$verb$", "right": "add"}, desc="Add or remove?") as ad:
                        f.act("ooa.okta/add_user_to_group", desc="Add to the Okta group", group="$name$", user="$rec.upn$", retry=RETRY, **okta())
                    with f.else_(ad):
                        f.act("ooa.okta/remove_user_from_group", desc="Remove from the Okta group", group="$name$", user="$rec.upn$", retry=RETRY, **okta())
                    f.act("ooa.okta/list_user_groups", desc="4. Read back", user="$rec.upn$", out={"names": "okg"}, **okta())
                    f.expr("present", "contains($okg$, $name$)", desc="Member now?")
                with f.case(sw, "jira"):
                    f.act("ooa.rest/request", desc="Jira account", out={"json": "jacc"}, **jira_api("/rest/api/2/user/search/qa", query={"query": "$rec.upn$"}))
                    with f.if_({"op": "eq", "left": "$verb$", "right": "add"}, desc="Add or remove?") as jad:
                        f.act("ooa.rest/request", desc="Add to the Jira group", json={"accountId": {"expr": "first($jacc$).accountId"}},
                              **jira_api("/rest/api/2/group/user", "POST", query={"groupname": "$name$"}))
                    with f.else_(jad):
                        f.act("ooa.rest/request", desc="Remove from the Jira group",
                              **jira_api("/rest/api/2/group/user", "DELETE", query={"groupname": "$name$", "accountId": {"expr": "first($jacc$).accountId"}}))
                    f.act("ooa.rest/request", desc="4. Read back", out={"json": "jg"}, **jira_api("/rest/api/2/user/groups", query={"accountId": {"expr": "first($jacc$).accountId"}}))
                    f.expr("present", "contains(join($jg$, ','), $name$)", desc="Member now?")
                with f.case(sw, "account"):
                    f.act("ooa.rest/request", desc="3. Disable the test identity in Entra", json={"accountEnabled": False}, **g_api("/users/${u.id}", "PATCH"))
                    f.act("ooa.okta/deactivate_user", desc="3. And in Okta", user="$rec.upn$", retry=RETRY, **okta())
                    f.act("ooa.rest/request", desc="4. Read back", out={"json": "u"}, **g_api("/users/${u.id}"))
                    f.expr("present", "not $u.accountEnabled$", desc="Disabled now? (true = the change is there)")
                with f.default(sw):
                    f.act("core/throw", desc="Unknown app", code="REMEDIATE.UNKNOWN_APP", message="${rec.key}: no executor for ${a}")
            f.expr("present", "toBoolean($present$)", desc="As yes/no")
            with f.if_("($verb$ == 'remove' and $present$) or ($verb$ == 'add' and not $present$) or ($verb$ == 'disable' and not $present$)",
                       desc="5. The app does not show the approved change?"):
                f.act("ooa.log/write", desc="6. Unexpected delta: report it (the next run's restore puts the baseline back)", path="$log_file$", level="ERROR",
                      message="${rec.key}: ${a} not reflected by the app")
                f.act("core/throw", desc="Stop this changeset", code="REMEDIATE.DELTA", message="${rec.key}: ${a} did not take effect as approved")
            f.act("ooa.list/add_item", desc="7. Applied", list="$done$", item="$a$", out={"list": "done"})
            f.act("ooa.list/add_item", desc="8. Response id", list="$ids$", item={"expr": "coalesce($rid$, $gid$, $u.id$)"}, out={"list": "ids"})
        f.act("ooa.datatable/add_row", desc="Changeset row", table="$evidence$",
              values={"key": "$rec.key$", "upn": "$rec.upn$", "applied": {"expr": "join($done$, ';')"}, "response_ids": {"expr": "join($ids$, ';')"}, "verified": True},
              out={"table": "evidence"})
        save_evidence(f)
        f.set("rec_note", "${len($done$)} change(s) applied and verified", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B6
def b6_evidence():
    c = Child("RW05", 6, "Excel evidence and restore",
              "Builds the before-and-after entitlement workbook for the 10 reviews, checks counts, diffs and approvals, stores it in the QA "
              "SharePoint library and reads it back, puts the baseline memberships and accounts back, confirms no QA principal is left disabled, "
              "hashes the evidence, scans for secrets and closes Excel.",
              "10 identity reviews", data_file="RW05/principals.csv")
    f = c.f
    src = {"b1": "RW05-B1", "b2": "RW05-B2", "b3": "RW05-B3", "b4": "RW05-B4", "b5": "RW05-B5"}
    for k in src:
        f.var(k, "Table", "local", {"columns": ["key"], "rows": []})
    for n, t, d in [("b3r", "Table", None), ("before", "String", ""), ("acts", "String", ""), ("appr", "String", ""), ("applied", "String", ""), ("a", "String", ""),
                    ("verb", "String", ""), ("app", "String", ""), ("name", "String", ""), ("q", "Table", None), ("sfid", "String", ""), ("snid", "String", ""),
                    ("u", "Dictionary", None), ("groups", "Dictionary", None), ("g", "Dictionary", None), ("gid", "String", ""), ("restored", "List", []),
                    ("st", "String", ""), ("enabled", "Boolean", True), ("left_disabled", "Number", 0), ("okta_user", "Dictionary", None), ("canary", "String", ""),
                    ("files", "List", None), ("file", "String", ""), ("text", "String", ""), ("leaks", "Number", 0), ("hash", "String", ""), ("cell", "Any", None),
                    ("review", "Table", {"columns": ["key", "upn", "before", "proposed", "approved", "applied", "restored", "enabled_after", "outcome"], "rows": []})]:
        f.var(n, t, "local", d)
    f.var("workbook", "String", "output", "", "The access review workbook.")
    c.start()
    with c.prepare("Read every child's evidence; the Entra groups (for the restore)."):
        for k, code in src.items():
            read_prev(f, code, k)
        read_prev(f, "RW05-B3", "b3r", "results.csv", "results")
        f.act("ooa.rest/request", desc="Entra groups", out={"json": "groups"}, **g_api("/groups"))
    with c.records("Per review: before, proposed, approved, applied; 5. restore the baseline; 6. confirm the account is enabled.",
                   summary="${rec.upn}\nApplied: ${applied}\nRestored: ${join($restored$, '; ')}  enabled ${enabled}"):
        qa_guard(f)
        f.act("ooa.datatable/lookup", desc="Grants before", table="$b2$", column="key", value="$rec.key$", return_column="grants", if_not_found="", out={"value": "before"})
        f.act("ooa.datatable/lookup", desc="Proposed", table="$b3$", column="key", value="$rec.key$", return_column="actions", if_not_found="", out={"value": "acts"})
        f.act("ooa.datatable/lookup", desc="Approved", table="$b4$", column="key", value="$rec.key$", return_column="status", if_not_found="", out={"value": "appr"})
        f.act("ooa.datatable/lookup", desc="Applied", table="$b5$", column="key", value="$rec.key$", return_column="applied", if_not_found="", out={"value": "applied"})
        f.act("ooa.datatable/lookup", desc="Review outcome", table="$b3r$", column="key", value="$rec.key$", return_column="status", if_not_found="", out={"value": "st"})
        f.set("restored", [], desc="Nothing restored yet")
        with f.loop(desc="5. Undo each applied change (baseline back)", iterator="list", over={"expr": "if(isEmpty($applied$), [], split($applied$, ';'))"}, **{"as": "a"}):
            f.expr("verb", "split($a$, ' ')[0]", desc="What was done")
            f.expr("app", "if($verb$ == 'disable', 'account', split(split($a$, ' ')[1], ':')[0])", desc="Which app")
            f.expr("name", "if($verb$ == 'disable', '', substring(split($a$, ' ')[1], len($app$) + 1))", desc="Which grant")
            with f.switch("$app$", desc="Reverse it") as sw:
                with f.case(sw, "sf"):
                    f.act("ooa.salesforce/query", desc="Salesforce user", soql="SELECT Id FROM User WHERE Email = '${rec.upn}'", out={"table": "q"}, **sf())
                    f.act("ooa.datatable/get_value", desc="Its id", table="$q$", row=0, column="Id", out={"value": "sfid"})
                    f.act("ooa.salesforce/create_record", desc="Give the permission set back", object="PermissionSetAssignment",
                          fields={"AssigneeId": "$sfid$", "PermissionSet.Name": "$name$", "QA_Run__c": "$run_id$"}, **sf())
                with f.case(sw, "sn"):
                    f.act("ooa.servicenow/query", desc="ServiceNow user", table="sys_user", query="email=${rec.upn}", out={"table": "q"}, **sn())
                    f.act("ooa.datatable/get_value", desc="Its sys_id", table="$q$", row=0, column="sys_id", out={"value": "snid"})
                    f.act("ooa.servicenow/create_record", desc="Give the role back", table="sys_user_has_role", fields={"user": "$snid$", "role": "$name$"}, **sn())
                with f.case(sw, "entra"):
                    f.set("gid", "", desc="Find the group id")
                    with f.loop(desc="Group by name", iterator="list", over="$groups.value$", **{"as": "g"}):
                        with f.if_({"op": "eq", "left": "$g.displayName$", "right": "$name$"}, desc="This one?"):
                            f.set("gid", "$g.id$", desc="Its id")
                    f.act("ooa.rest/request", desc="The identity", out={"json": "u"}, **g_api("/users/${rec.upn}"))
                    f.act("ooa.rest/request", desc="Put the membership back", json={"@odata.id": GRAPH + "/directoryObjects/${u.id}"},
                          **g_api("/groups/${gid}/members/$ref", "POST"))
                with f.case(sw, "okta"):
                    with f.if_({"op": "eq", "left": "$verb$", "right": "add"}, desc="Was it added?") as ad:
                        f.act("ooa.okta/remove_user_from_group", desc="Take the added group away", group="$name$", user="$rec.upn$", **okta())
                    with f.else_(ad):
                        f.act("ooa.okta/add_user_to_group", desc="Give the group back", group="$name$", user="$rec.upn$", **okta())
                with f.case(sw, "account"):
                    f.act("ooa.rest/request", desc="The identity", out={"json": "u"}, **g_api("/users/${rec.upn}"))
                    f.act("ooa.rest/request", desc="Enable it again in Entra", json={"accountEnabled": True}, **g_api("/users/${u.id}", "PATCH"))
                    f.act("ooa.okta/activate_user", desc="And in Okta", user="$rec.upn$", send_email=False, **okta())
            f.act("ooa.list/add_item", desc="Restored", list="$restored$", item="$a$", out={"list": "restored"})
        f.act("ooa.rest/request", desc="6. Read the identity after the restore", out={"json": "u"}, **g_api("/users/${rec.upn}"))
        f.act("ooa.okta/get_user", desc="6. And its Okta account", user="$rec.upn$", out={"user": "okta_user"}, **okta())
        f.expr("enabled", "$u.accountEnabled$ and $okta_user.status$ == 'ACTIVE'", desc="Enabled in both?")
        with f.if_("not $enabled$", desc="Left disabled?"):
            f.expr("left_disabled", "$left_disabled$ + 1", desc="Count it")
        f.act("ooa.datatable/add_row", desc="1. Before/after row", table="$review$",
              values={"key": "$rec.key$", "upn": "$rec.upn$", "before": "$before$", "proposed": "$acts$", "approved": "$appr$", "applied": "$applied$",
                      "restored": {"expr": "join($restored$, ';')"}, "enabled_after": "$enabled$",
                      "outcome": {"expr": "if(isEmpty($before$), 'outside scope', if($st$ == 'exception', 'review', if(isEmpty($acts$), 'clean', if(isEmpty($applied$), 'not approved', 'remediated and restored'))))"}},
              out={"table": "review"})
        f.set("rec_note", "${if(isEmpty($applied$), 'no change', 'restored ' + len($restored$))}", desc="Note for the results")
    with c.finish("Workbook, SharePoint, read back, no one left disabled, evidence hash, secret scan, close."):
        with f.if_("$left_disabled$ > 0", desc="6. Anyone left disabled?"):
            f.act("core/throw", desc="Stop", code="RESTORE.DISABLED_LEFT", message="${left_disabled} QA principal(s) are still disabled after the restore")
        f.set("workbook", "${out_dir}/RW05-access-review.xlsx", desc="Name of the workbook")
        f.act("ooa.excel/create_workbook", desc="1. The access review workbook", path="$workbook$", overwrite=True, sheet="Reviews")
        f.act("ooa.excel/write_table", desc="Before and after", table="$review$", sheet="Reviews")
        f.act("ooa.excel/set_cell", desc="Label", cell="K1", value="Reviews", sheet="Reviews")
        f.act("ooa.excel/set_formula", desc="2. Count formula", cell="L1", formula="COUNTA(A2:A11)", sheet="Reviews")
        f.act("ooa.excel/set_cell", desc="Label", cell="K2", value="Remediated", sheet="Reviews")
        f.act("ooa.excel/set_formula", desc="2. Remediated count", cell="L2", formula="COUNTIF(I2:I11,\"remediated and restored\")", sheet="Reviews")
        f.act("ooa.excel/add_sheet", desc="Approvals sheet", name="Approvals")
        f.act("ooa.excel/write_table", desc="2. The approvals", table="$b4$", sheet="Approvals")
        f.act("ooa.excel/save", desc="Save")
        f.act("ooa.excel/close", desc="Close")
        f.act("ooa.microsoft365/upload_file", desc="3. Store it in the QA SharePoint library", local_path="$workbook$", remote_path="OOA-QA/${run_id}/RW05-access-review.xlsx",
              drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url=GRAPH, retry=RETRY)
        f.act("ooa.excel/open", desc="4. Re-open it", path="$workbook$", session="check")
        f.act("ooa.excel/get_cell", desc="Read the count back", cell="A11", sheet="Reviews", session="check", out={"value": "cell"})
        f.act("ooa.excel/close", desc="9. Close Excel", session="check", save=False)
        with f.if_({"op": "ne", "left": "$cell$", "right": "I10"}, desc="Read back differs?"):
            f.act("core/throw", desc="Stop", code="VERIFY.READBACK", message="Row 11 holds ${cell}, expected I10")
        f.act("ooa.crypto/hash_file", desc="7. Hash the evidence workbook", path="$workbook$", out={"hash": "hash"})
        f.act("ooa.file/write_text", desc="Keep the hash next to it", path="${workbook}.sha256", text="$hash$")
        f.act("ooa.global_values/get", desc="8. The canary text", name="rw_canary", default="", type="String", out={"value": "canary"})
        f.act("ooa.file/list", desc="Every file this run wrote", folder="${base}/output/${run_id}", pattern="*.*", include_subfolders=True, out={"files": "files"})
        with f.loop(desc="Each output file", iterator="list", over="$files$", **{"as": "file"}):
            with f.if_("endsWith($file$, '.csv') or endsWith($file$, '.json') or endsWith($file$, '.txt')", desc="A text file?"):
                f.act("ooa.file/read_text", desc="Read it", path="$file$", out={"text": "text"})
                with f.if_("not isEmpty($canary$) and contains($text$, $canary$)", desc="Holds the canary?"):
                    f.expr("leaks", "$leaks$ + 1", desc="Count the leak")
        with f.if_("$leaks$ > 0", desc="Secret in an output?"):
            f.act("core/throw", desc="Stop", code="SECURITY.CANARY", message="The secret canary is in ${leaks} output file(s)")
        f.act("ooa.datatable/write_to_file", desc="The reviews as CSV", table="$review$", path="${out_dir}/RW05-reviews.csv")
        f.act("ooa.log/write", desc="Close-out", path="$log_file$", message="Restore done; no QA principal disabled; evidence hash ${substring($hash$, 0, 16)}; no canary")
    return c


def main():
    return Main("RW05", "Access review", "Quarterly access review: Entra ID/AD + Salesforce + Jira + ServiceNow + Slack + Okta, two-person approval, QA-only changes, baseline restored.",
                [("rw05_b1", "RW05-B1", "Identity census"), ("rw05_b2", "RW05-B2", "SaaS entitlement collectors"),
                 ("rw05_b3", "RW05-B3", "Policy and SoD analyzer"), ("rw05_b4", "RW05-B4", "Approval and change guard"),
                 ("rw05_b5", "RW05-B5", "QA remediation executor"), ("rw05_b6", "RW05-B6", "Excel evidence and restore")], FOLDER, parallel_group=None).build()


CHILDREN = [b1_census, b2_collectors, b3_policy, b4_approvals, b5_remediation, b6_evidence]
MAINS = [main]
