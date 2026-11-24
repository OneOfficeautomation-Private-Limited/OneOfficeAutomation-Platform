"""Hand-written cases for the Process mining commands (event logs written in the run folder; no outside service)."""
from helpers_cloud import adapt_samples, value

T = "ooa.mining/"
LOG = ("case,activity,time\n"
       "PO-1,Create order,2026-11-02 09:00\nPO-1,Approve,2026-11-02 10:00\nPO-1,Ship,2026-11-03 09:00\n"
       "PO-2,Create order,2026-11-02 11:00\nPO-2,Approve,2026-11-02 11:30\nPO-2,Ship,2026-11-02 15:30\n"
       "PO-3,Create order,2026-11-03 08:00\nPO-3,Ship,2026-11-03 12:00\n")


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("pm_cases", "Number"), ("pm_events", "Number"), ("pm_variants", "Table"), ("pm_starts", "Dictionary"),
                 ("pm_diagram", "String"), ("pm_ok", "Number"), ("pm_bad", "Number"), ("pm_fit", "Number"), ("pm_dev", "Table"),
                 ("pm_flows", "Table"), ("pm_log", "Table")):
        ua.var(n, t)

    with ua.case(T + "discover_process", "orders from a CSV event log",
                 "Discover process reads an event log (case, activity, time) and finds the cases, the variants (paths "
                 "taken), the flows between activities and a Mermaid diagram. Checked: 3 cases, 8 events, the most "
                 "common variant and the start activity.") as f:
        f.act("ooa.file/write_text", desc="An order event log", path="mining/orders.csv", text=LOG)
        f.act(T + "discover_process", desc="Discover the process", file="mining/orders.csv", top_variants=5, min_edge_count=1,
              out={"cases": "pm_cases", "events": "pm_events", "variants": "pm_variants", "start_activities": "pm_starts",
                   "diagram": "pm_diagram", "flows": "pm_flows"})
        ua.check_eq("pm_cases", 3)
        ua.check_eq("pm_events", 8)
        value(ua, "$pm_variants.rows[0][1]", "eq", 2, "cases of the most common variant")
        value(ua, "$pm_starts['Create order']", "eq", 3, "cases starting with Create order")
        ua.check("contains", "$pm_diagram$", "Approve", what="the diagram")

    with ua.case(T + "discover_process", "an event log given as a table",
                 "Discover process also takes the log as a data table with its own column names. Checked: the cases.") as f:
        f.set("pm_log", {"columns": ["ticket", "step", "at"],
                         "rows": [["T1", "Open", "2026-11-01 08:00"], ["T1", "Close", "2026-11-01 09:00"],
                                  ["T2", "Open", "2026-11-01 08:30"], ["T2", "Close", "2026-11-01 10:30"]]}, desc="A ticket log")
        f.act(T + "discover_process", desc="Discover the process", table="$pm_log$", case_column="ticket", activity_column="step",
              time_column="at", out={"cases": "pm_cases"})
        ua.check_eq("pm_cases", 2)

    with ua.case(T + "check_conformance", "orders that skipped approval",
                 "Check conformance compares each case with the allowed flows, start and end activities. Checked: two "
                 "cases conform, one skipped Approve, fitness 2/3.") as f:
        f.act("ooa.file/write_text", desc="An order event log", path="mining/orders.csv", text=LOG)
        f.act(T + "check_conformance", desc="Check the orders", file="mining/orders.csv",
              allowed_flows={"Create order": ["Approve"], "Approve": ["Ship"]}, start_activities=["Create order"],
              end_activities=["Ship"], out={"conforming": "pm_ok", "deviating": "pm_bad", "fitness": "pm_fit", "deviations": "pm_dev"})
        ua.check_eq("pm_ok", 2)
        ua.check_eq("pm_bad", 1)
        value(ua, "$pm_dev.rows[0][0]", "eq", "PO-3", "the deviating case")
        ua.check("between", "$pm_fit$", [0.66, 0.67], what="fitness")
