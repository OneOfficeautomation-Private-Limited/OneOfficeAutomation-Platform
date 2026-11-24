"""Jobs and alerts cases for One CommandCenter (the samples' "outside One CommandCenter" cases are left out: there the
commands work)."""
from helpers_platform import drop, renumber

J = "ooa.jobs/"


def add(ua):
    drop(ua, "outside One CommandCenter", "refused before the run")
    for v, t in (("jb_jobs", "List"), ("jb_n", "Number"), ("jb_job", "Dictionary"), ("jb_status", "String"),
                 ("jb_id", "String"), ("jb_stopped", "Boolean")):
        ua.var(v, t)
    f = ua.f
    with ua.case(J + "list_jobs", "the active runs include this one",
                 "List jobs lists runs the run's user may see. While this agent runs, the active runs are at least one "
                 "(this run), and the count matches the list."):
        f.act(J + "list_jobs", status="active", limit=50, out={"jobs": "jb_jobs", "count": "jb_n"})
        ua.check("ge", "$jb_n$", 1, what="active runs")
        f.expr("jb_n", "len($jb_jobs) - $jb_n")
        ua.check("eq", "$jb_n$", 0, what="list length minus count")
    with ua.case(J + "get_job", "this run is running",
                 "Get job reads a run by its id; this run's own id (sys.run_id) shows status running."):
        f.act(J + "get_job", job_id="$sys.run_id$", out={"job": "jb_job", "status": "jb_status"})
        ua.check_eq("jb_status", "running")
        f.expr("jb_id", "$jb_job.id")
        ua.check("eq", "$jb_id$", "$sys.run_id$", what="the run id")
    with ua.case(J + "stop_job", "a run that has already ended is not stopped",
                 "Stop job stops a queued or running run. The newest finished run is taken from the history; "
                 "stopping it changes nothing: stopped is false and status is the state it ended in."):
        f.act(J + "list_jobs", status="history", limit=1, out={"jobs": "jb_jobs"})
        f.expr("jb_id", "$jb_jobs[0].id")
        f.act(J + "stop_job", job_id="$jb_id$", out={"stopped": "jb_stopped", "status": "jb_status"})
        ua.check_eq("jb_stopped", False)
        ua.check("in", "$jb_status$", ["success", "failed", "stopped"], what="status")
    with ua.case(J + "raise_alert", "a warning alert with a subject",
                 "Raise alert sends an alert to the alert rules of kind Raised by an agent; alerts is how many were "
                 "raised (0 when no rule takes them). Raising the same subject again in this run raises none."):
        ua.var("jb_alerts", "Number", -1)
        f.act(J + "raise_alert", message="Unit test alert from UT Jobs and alerts; nothing to do.", severity="warning",
              subject="ut_unit_test_alert", out={"alerts": "jb_alerts"})
        ua.check("ge", "$jb_alerts$", 0, what="alerts raised")
        f.act(J + "raise_alert", message="Same subject again.", subject="ut_unit_test_alert", out={"alerts": "jb_alerts"})
        ua.check("eq", "$jb_alerts$", 0, what="alerts for a repeated subject")
    renumber(ua)
