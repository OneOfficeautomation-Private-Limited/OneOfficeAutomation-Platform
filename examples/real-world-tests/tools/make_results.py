"""Builds the real-world test results workbook (runbook step 11): test case -> agent -> result.

    python make_results.py <source test-case workbook.xlsx> <output.xlsx> [RW01=run-id ...]

Sheets: Summary (8 automations), Child bots (48), Records (every record of every child), Command coverage (the 93 catalogue
rows, with the agents that use each command and whether they ran), Targets (the 29 URLs and apps), Findings.
Run ids default to the newest CHECK run of each automation, else its newest LOCAL run, under <base>/output.
"""
from __future__ import annotations

import csv
import glob
import json
import os
import re
import sys

import openpyxl
from openpyxl.styles import Alignment, Font, PatternFill

HERE = os.path.dirname(os.path.abspath(__file__))
AGENTS = os.path.join(os.path.dirname(HERE), "agents")
BASE = os.path.join(os.path.expanduser("~"), "OOA Real-world tests")

TARGETS = {   # how each of the 29 targets is used (kept with the agents that use it)
    "RPA Challenge": ("Live", "RW04-B1 fills the ten demo rounds (approved demo form submission)."),
    "Sauce Demo": ("Live, waits for the vault", "RW04-B1 signs in with the demo password from the vault (rw_qa_standins/saucedemo/password, entered by a person)."),
    "Books to Scrape": ("Live, read only", "RW01-B2 research; RW04-B1 prices of four books."),
    "DemoQA Web Tables": ("Live, read only", "RW04-B1 reads the table (component check)."),
    "Quotes to Scrape JavaScript": ("Live, read only", "RW01-B2 research."),
    "Wikipedia population table": ("Live, read only", "RW01-B2 research; RW00 Preflight checks the site answers."),
    "Microsoft 365": ("Stand-in", "Graph stand-in for mail, Teams, SharePoint, calendar; a live QA tenant needs its address and a Graph token in the vault."),
    "Microsoft identity": ("Not run", "Sign-in is replaced by vault tokens for the stand-in; a live tenant needs the app registration."),
    "Exchange Online": ("Stand-in", "RW01-B1, RW03-B1, RW06-B1 mailboxes; RW02-B5, RW03-B4, RW04-B5 send to QA addresses only."),
    "Teams": ("Stand-in", "RW02-B5, RW07-B6, RW08-B4/B6 post and read back."),
    "SharePoint": ("Stand-in", "Every automation stores its workbook; RW06-B1 retention labels; RW08-B6 permissions."),
    "OneDrive": ("Stand-in", "Same Graph drive calls as SharePoint."),
    "Entra ID": ("Stand-in", "RW02-B3 users and groups; RW05 census, nested groups, remediation and restore."),
    "Salesforce": ("Stand-in", "RW01-B4, RW04-B2/B4, RW05-B2/B5 (sandbox check refuses production)."),
    "Salesforce My Domain": ("Stand-in", "Same as Salesforce; the host check accepts only sandbox or QA hosts."),
    "ServiceNow Developer": ("Not run", "Portal for obtaining an instance; not automated."),
    "ServiceNow": ("Stand-in", "RW01-B3, RW02-B4, RW05-B2/B5, RW06-B4, RW07, RW08-B3/B5."),
    "Atlassian": ("Not run", "Sign-in replaced by a vault token for the stand-in."),
    "Jira": ("Stand-in", "RW01-B4 issues; RW05 groups."),
    "Slack": ("Stand-in", "RW05-B2 members, paging and orphan accounts."),
    "GitHub": ("Stand-in", "RW08-B1/B2/B4: pull requests, protection, signatures, CI runs, artifacts (read only)."),
    "Azure DevOps": ("Stand-in", "RW08-B1 work items."),
    "Shopify": ("Stand-in", "RW04-B1/B2/B3: products, draft orders, stock with versions; payments refused."),
    "Workday": ("Stand-in", "RW02-B2 workers and staged hires."),
    "SAP GUI": ("Not here (simulator runs)", "RW03-B5 SAP GUI path built (MIR7 park); SAP GUI is not installed here, the QA simulator parks the drafts."),
    "SQL Server": ("Embedded H2 instead", "RW03-B3, RW04-B3, RW05-B1/B3, RW06-B3/B5 use an embedded H2 file; a live run points the connect step at the QA database."),
    "PostgreSQL": ("Embedded H2 instead", "As SQL Server."),
    "AWS": ("Stand-in", "RW07-B2/B4/B5/B6: EC2 query API; terminate refused; only OOA-QA tagged instances change."),
    "OOA test harness": ("Here", "The QA stand-in on 127.0.0.1:8791 (loopback only)."),
}


def walk(steps):
    for s in steps:
        yield s
        a = s.get("args", {})
        if isinstance(a.get("condition"), dict) and a["condition"].get("condition"):
            yield {"action": a["condition"]["condition"]}
        if isinstance(a.get("iterator"), str) and "/" in a["iterator"]:
            yield {"action": a["iterator"]}
        for k in ("children", "else"):
            yield from walk(s.get(k) or [])
        for b in s.get("elseIf") or []:
            yield from walk(b.get("children") or [])
        for c in s.get("catches") or s.get("catch") or []:
            yield from walk(c.get("children") or [])
        for k in ("finally", "cases", "default"):
            v = s.get(k)
            if isinstance(v, list):
                for x in v:
                    yield from walk(x.get("children", []) if isinstance(x, dict) and "children" in x and "action" not in x else [x])


def agent_actions():
    used = {}
    for path in glob.glob(os.path.join(AGENTS, "*", "*.flow.json")):
        name = os.path.basename(path)[:-10]
        code = name.split(" ")[0]
        d = json.load(open(path, encoding="utf-8"))
        acts = set()
        for s in walk(d.get("steps", [])):
            if s.get("action"):
                acts.add(s["action"])
        text = json.dumps(d)
        acts |= set(re.findall(r'"(ooa\.[a-z_0-9]+/[a-z_0-9]+)"', text))
        if '"$credential"' in text:
            acts.add("$credential")
        used[code] = acts
    return used


def latest_run(rw):
    out = os.path.join(BASE, "output")
    for kind in ("CHECK", "LOCAL"):
        runs = sorted(glob.glob(os.path.join(out, "%s-%s-*" % (rw, kind))), key=os.path.getmtime)
        runs = [r for r in runs if len(glob.glob(os.path.join(r, rw + "-B*"))) >= 6]
        if runs:
            return os.path.basename(runs[-1])
    return None


def results(run, code):
    p = os.path.join(BASE, "output", run or "-", code, "results.csv")
    return list(csv.DictReader(open(p, encoding="utf-8"))) if os.path.exists(p) else None


def newest(code, pinned=None):
    """The newest complete run of one child (CHECK runs in One CommandCenter, LOCAL runs on this PC), or the pinned run."""
    if pinned:
        return pinned, results(pinned, code)
    found = [(os.path.getmtime(p), p) for p in glob.glob(os.path.join(BASE, "output", "*", code, "results.csv"))]
    found = [x for x in found if re.search(r"-(CHECK|LOCAL|SOLO)-", x[1])]
    if not found:
        return None, None
    p = max(found)[1]
    run = os.path.basename(os.path.dirname(os.path.dirname(p)))
    return run, results(run, code)


def expand(cmds):
    """'ooa.desktop/type_text, press_keys' -> both commands; 'core/try, core/throw' as given; 'ooa.number' as a toolkit."""
    keys, prefix = [], None
    for part in re.split(r"[,+;]| and ", cmds):
        part = part.strip().strip("`")
        m = re.match(r"(ooa\.[a-z_0-9]+|core)(/[a-z_0-9*]+)?", part)
        if m:
            prefix = m.group(1)
            keys.append(m.group(0))
        elif prefix and re.fullmatch(r"[a-z_0-9*]+", part):
            keys.append(prefix + "/" + part)
    return keys


def style(ws, widths):
    head = PatternFill("solid", fgColor="1F3A5F")
    for c in ws[1]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = head
        c.alignment = Alignment(vertical="top", wrap_text=True)
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[openpyxl.utils.get_column_letter(i)].width = w
    for row in ws.iter_rows(min_row=2):
        for c in row:
            c.alignment = Alignment(vertical="top", wrap_text=True)
    ws.freeze_panes = "A2"


def main():
    src, dst = sys.argv[1], sys.argv[2]
    runs = {"RW%02d" % i: None for i in range(1, 9)}
    for a in sys.argv[3:]:
        k, v = a.split("=", 1)
        runs[k] = v
    pinned = {k: v for k, v in runs.items() if v}
    wb_src = openpyxl.load_workbook(src, data_only=True)
    child_rows = [r for r in wb_src["Child Bots"].iter_rows(min_row=2, values_only=True) if r[0] and re.match(r"RW\d\d-B\d", str(r[0]))]
    used = agent_actions()

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Summary"
    ws.append(["Automation", "Run id", "Where it ran", "Children passed", "Records handled", "Routed to exceptions", "Main agent", "Notes"])
    child_status = {}
    for rw in runs:
        passed, handled, routed, used_runs = 0, 0, 0, []
        for b in range(1, 7):
            code = "%s-B%d" % (rw, b)
            run, res = newest(code, pinned.get(rw))
            ok = res is not None and len(res) > 0
            child_status[code] = (run, res)
            if run and run not in used_runs:
                used_runs.append(run)
            if ok:
                passed += 1
                handled += sum(1 for r in res if r["status"] == "done")
                routed += sum(1 for r in res if r["status"] == "exception")
        run = ", ".join(used_runs)
        where = "; ".join(sorted({"One CommandCenter" if "CHECK" in r else "this PC (ooa run, local vault)" for r in used_runs})) or "not run"
        mains = [p for p in glob.glob(os.path.join(BASE, "output", rw + "-*", rw + "-summary.csv"))]
        main_ok = bool(mains)
        main_state = ("ran in One CommandCenter (" + os.path.basename(os.path.dirname(mains[-1])) + ")") if main_ok else "checked in; runs after sign-in"
        child_status[rw] = (run, [{"status": "done"}] if main_ok else None)
        ws.append([rw, run or "", where, "%d of 6" % passed, handled, routed, main_state,
                   "Routed records are the ones each test sends aside on purpose (duplicates, policy breaks, declined approvals ...). "
                   "A child after the first works on what the child before it accepted, so later children see fewer than 10 records."])
    style(ws, [11, 22, 26, 14, 14, 18, 28, 60])

    ws = wb.create_sheet("Child bots")
    ws.append(["Test case", "Automation", "Child bot (workbook)", "Agent in One CommandCenter", "Run id", "Status", "Handled", "Routed", "Commands used",
               "Toolkits used", "Workbook responsibility"])
    for r in child_rows:
        code = str(r[0])
        agent = next((os.path.basename(p)[:-10] for p in glob.glob(os.path.join(AGENTS, "*", code + " *.flow.json"))), "")
        run, res = child_status.get(code, (None, None))
        acts = sorted(used.get(code, set()))
        kits = sorted({a.split("/")[0] for a in acts})
        ws.append([code, r[1], r[2], agent, run or "", "pass" if res else "not run",
                   sum(1 for x in res or [] if x["status"] == "done"), sum(1 for x in res or [] if x["status"] == "exception"), len(acts), ", ".join(kits), r[3]])
    style(ws, [10, 9, 26, 36, 18, 8, 9, 9, 10, 50, 70])

    ws = wb.create_sheet("Records")
    ws.append(["Test case", "Record", "Result", "Note", "Time", "Run id"])
    for code, (run, res) in sorted(child_status.items()):
        if "-B" not in code:
            continue
        for x in res or []:
            ws.append([code, x["key"], "handled" if x["status"] == "done" else "routed: " + x["note"].split(":")[0], x["note"], x["time"], run])
    style(ws, [10, 12, 34, 90, 10, 18])

    ws = wb.create_sheet("Command coverage")
    ws.append(["#", "Product area", "Capability", "OOA commands", "Catalogue status", "Agents that use it", "Result here", "How"])
    for r in wb_src["Command Coverage"].iter_rows(min_row=2, values_only=True):
        if not isinstance(r[0], (int, float)):
            continue
        cmds = str(r[3] or "")
        keys = expand(cmds)
        def hit(a, k):
            return a == k or ("/" not in k and a.startswith(k + "/")) or (k.endswith("*") and a.startswith(k[:-1]))
        agents = sorted({code for code, acts in used.items() if any(hit(a, k) for k in keys for a in acts)})
        if re.search(r"(?i)vault|locker", cmds + str(r[2])):
            agents = sorted(code for code, acts in used.items() if "$credential" in acts)
        ran_shared = any(child_status.get(x, (None, None))[1] for x in ("RW01", "RW02"))
        ran = [a for a in agents if child_status.get(a, (None, None))[1] or (a == "RW00" and ran_shared)]
        if agents and ran:
            result, how = "Ran", "Passed in " + ", ".join(ran[:8]) + (" ..." if len(ran) > 8 else "")
        elif agents:
            result, how = "Built", "Used by " + ", ".join(agents[:8])
        else:
            result, how = "Not exercised", "No agent uses it (" + (str(r[4] or "")) + ")"
        if int(r[0]) in BRANCH_ONLY and result == "Ran":
            result, how = BRANCH_ONLY[int(r[0])]
        ws.append([int(r[0]), r[1], r[2], cmds, r[4], ", ".join(agents), result, how])
    style(ws, [5, 18, 26, 36, 16, 40, 14, 60])

    ws = wb.create_sheet("Targets")
    ws.append(["Category", "Product / app", "Address", "How it is used here", "Detail"])
    for r in wb_src["Real URLs & Apps"].iter_rows(min_row=2, values_only=True):
        if not r[1]:
            continue
        how = TARGETS.get(str(r[1]), ("", ""))
        ws.append([r[0], r[1], r[2], how[0], how[1]])
    style(ws, [22, 26, 50, 24, 80])

    ws = wb.create_sheet("Findings")
    ws.append(["#", "Finding", "Where seen", "Effect", "Proposed fix (needs your OK)"])
    for row in FINDINGS:
        ws.append(list(row))
    style(ws, [4, 50, 22, 50, 60])
    wb.save(dst)
    print("results written to", dst)


BRANCH_ONLY = {   # catalogue rows whose step sits on a branch that runs only when its system is given
    37: ("Built, not run here", "RW07-B3 terminal path runs when terminal_host is given (no terminal host here)."),
    38: ("Simulator ran; GUI built", "RW03-B5 parked drafts in the SAP QA simulator; the SAP GUI path (MIR7, park) runs with sap_mode=gui on a PC with SAP Logon."),
    42: ("Built, not run here", "RW03-B1 Outlook desktop path runs when outlook_mailbox is given (Outlook has no mail profile here)."),
    61: ("Built, not run here", "RW08-B2 SFTP path runs when sftp_host is given (no SFTP server here)."),
    62: ("Built, not run here", "RW05-B1 Active Directory path runs when ad_server is given (no QA domain here)."),
    74: ("Ping ran; SNMP built", "RW07-B5 pings; its SNMP GET runs when snmp_host is given (no lab device here)."),
    84: ("Built, set aside", "RW06-B2 sends to Document Automation only when docflow_process is given; Document Automation testing is set aside."),
}

FINDINGS = [
    (1, "String and data table commands refuse empty text ('resolved to empty').", "RW01, RW02 build", "Agents must guard every Trim, Replace or Lookup on a value that may be empty.",
     "Let text inputs accept an empty value where an empty result makes sense (Trim of '' is '')."),
    (2, "Start program with a hidden, minimized or maximized window merges all arguments into one under Windows PowerShell 5.1.", "RW00 Start QA stand-ins",
     "Programs started hidden get one wrong argument.", "Pass the argument array to PowerShell without the ConvertFrom-Json array quirk."),
    (3, "No Entra ID user and group commands in the Microsoft 365 toolkit.", "RW02-B3, RW05", "Agents call Graph through the REST toolkit.",
     "Add Entra commands: find/create/update user, group members add/remove, nested membership."),
    (4, "If conditions have no 'not empty' comparison (only 'empty'); Validate does not flag the unknown operator.", "RW03-B4/B6 build",
     "The step fails at run time with FLOW.BAD_CONDITION.", "Add notEmpty to the comparison list of If, and make Validate check the operator."),
    (5, "Desktop mouse and key commands report 'No visible window matches' when Windows refuses to bring a window to the front.", "RW04-B6",
     "The message points at the wrong cause.", "Report WINDOW.NOT_ACTIVATED (as Window > Activate does) with the Windows focus rule as the reason."),
    (6, "Desktop object actions take about 3 s each on the demo order-entry app.", "RW04-B6 (46 actions, 143 s)", "Desktop-heavy agents are slow.",
     "Cache the window's UI Automation element between steps of the same window."),
    (7, "A Credential variable cannot take a vault reference as its default; only a run input can.", "RW04-B1", "The main agent must pass the demo password in.",
     "Allow a Credential variable to name a vault reference, as Credential fields of commands do."),
    (8, "Java applications: Get text on a label returns its accessible name, not the text it shows, when the two differ.", "RW02-B6 (Java desk app)",
     "A status label with its own accessible name always reads back that name.", "Return the label's text (AccessibleText or JLabel.getText) for get_text; keep the name in get_properties."),
]

if __name__ == "__main__":
    main()
