"""Long-running agent cases for One CommandCenter: every wait is short and carries on, so the agent ends by itself."""
from helpers_platform import drop, renumber

R = "ooa.longrunning/"


def add(ua):
    drop(ua, "outside One CommandCenter", "refused before the run", "an approval or a form")
    for v, t in (("lr_url", "String"), ("lr_id", "String"), ("lr_by", "String"), ("lr_timed_out", "Boolean"),
                 ("lr_result", "Dictionary"), ("lr_text", "String"), ("lr_jobs", "List")):
        ua.var(v, t)
    f = ua.f
    with ua.case(R + "create_callback", "a one-time callback address",
                 "Create callback URL makes a web address another system calls when its work is done; both the "
                 "address and the callback id are filled in."):
        f.act(R + "create_callback", out={"url": "lr_url", "callback_id": "lr_id"})
        ua.check("startsWith", "$lr_url$", "http", what="the callback address")
        ua.check("notEmpty", "$lr_id$", what="the callback id")
    with ua.case(R + "wait_for_callback", "a call made before the wait is not lost",
                 "The agent calls its own callback address (HTTP POST with JSON), then Wait for callback carries on "
                 "with that JSON body as result, without timing out."):
        f.act(R + "create_callback", out={"url": "lr_url", "callback_id": "lr_id"})
        f.act("ooa.rest/request", desc="Call the address the way the other system would", url="$lr_url$",
              method="POST", json={"invoice": "INV-7", "approved": True})
        f.act(R + "wait_for_callback", callback_id="$lr_id$", timeout_seconds=120, on_timeout="continue",
              out={"timed_out": "lr_timed_out", "result": "lr_result"})
        ua.check_eq("lr_timed_out", False)
        f.expr("lr_text", "$lr_result.invoice")
        ua.check_eq("lr_text", "INV-7")
    with ua.case(R + "create_task", "an approval in the Task inbox",
                 "Create task puts an approval in the Task inbox for role:admin and gives its id. (Cancel the tasks "
                 "named ut_ ... from the Task inbox afterwards.)"):
        f.act(R + "create_task", kind="approval", title="ut_ approval (unit test, cancel me)", assignee="role:admin",
              description="Made by the unit test agent UT Long-running agents.", data={"invoice": "INV-7"},
              due_hours=1, out={"task_id": "lr_id"})
        ua.check("notEmpty", "$lr_id$", what="the task id")
    with ua.case(R + "wait_for_task", "nobody answers: the wait times out and carries on",
                 "Wait for task suspends until a task is done; here nobody answers a new form task, so after 10 "
                 "seconds it carries on with timed_out true."):
        f.act(R + "create_task", kind="form", title="ut_ form task (unit test, cancel me)", assignee="role:admin",
              form={"fields": [{"id": "comment", "type": "text", "label": "Comment"}]}, out={"task_id": "lr_id"})
        f.act(R + "wait_for_task", task_id="$lr_id$", timeout_seconds=10, on_timeout="continue",
              out={"timed_out": "lr_timed_out"})
        ua.check_eq("lr_timed_out", True)
    with ua.case(R + "resume_after", "suspend for 5 seconds, then carry on",
                 "Resume after suspends the run without holding the runner and carries on after the delay; "
                 "resumed_by says what woke it."):
        f.act(R + "resume_after", delay_seconds=5, out={"resumed_by": "lr_by"})
        ua.check("notEmpty", "$lr_by$", what="resumed_by")
    with ua.case(R + "suspend_until", "until a time 5 seconds away",
                 "Suspend until waits for an event of a kind; kind time with ref 5 wakes after 5 seconds without "
                 "timing out."):
        f.act(R + "suspend_until", kind="time", ref="5", timeout_seconds=120, on_timeout="continue",
              out={"resumed_by": "lr_by", "timed_out": "lr_timed_out"})
        ua.check_eq("lr_timed_out", False)
        ua.check("notEmpty", "$lr_by$", what="resumed_by")
    with ua.case(R + "wait_for_item", "an item nobody processes: the wait times out",
                 "Wait for work item waits until an item of a One CommandCenter queue ends. An item is added to the "
                 "queue ut_longrunning that no agent processes, so after 10 seconds the wait carries on with "
                 "timed_out true; the item is deleted afterwards."):
        f.act("ooa.workload/add_item", queue="ut_longrunning", data={"n": 1}, central=True, out={"item_id": "lr_id"})
        f.act(R + "wait_for_item", item_id="$lr_id$", timeout_seconds=10, on_timeout="continue",
              out={"timed_out": "lr_timed_out"})
        f.act("ooa.workload/delete_items", queue="ut_longrunning", status="any", force=True, central=True)
        ua.check_eq("lr_timed_out", True)
    with ua.case(R + "wait_for_job", "a run that has already ended",
                 "Wait for job waits until another run ends; the newest finished run is taken from the history, so "
                 "it carries on at once with that run's result."):
        f.act("ooa.jobs/list_jobs", status="history", limit=1, out={"jobs": "lr_jobs"})
        f.expr("lr_id", "$lr_jobs[0].id")
        f.act(R + "wait_for_job", job_id="$lr_id$", timeout_seconds=60, on_timeout="continue",
              out={"timed_out": "lr_timed_out", "result": "lr_result"})
        ua.check_eq("lr_timed_out", False)
    renumber(ua)
