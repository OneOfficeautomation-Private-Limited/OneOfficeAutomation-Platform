"""Shared shape of the real-world test agents: child agents (one per child bot of the test workbook), main agents
(one per automation RW01-RW08) and the shared agents (stand-ins, preflight, run all).

Every child agent:
  1. Start      - reads the run context, makes its output folder, writes the log, says what it will do;
  2. Prepare    - child specific (read the 10 test records, open the browser, connect the database ...);
  3. Records    - one pass per record inside Try: at least ten commands per record, a message box that closes by
                  itself, a short pause; a failing record is caught, logged and counted, the next one goes on;
  4. Finish     - child specific wrap-up, then the results file, the outputs and a closing message box.
"""
from __future__ import annotations

from contextlib import contextmanager

from flowlib import Flow

BASE = "C:/Users/virup/OOA Real-world tests"
STANDIN = "http://127.0.0.1:8791"
LOCKER = "rw_qa_standins"


def cred(name, attribute="token"):
    """A vault reference to a stand-in credential (made-up values the stand-ins accept)."""
    return {"$credential": f"{LOCKER}/{name}/{attribute}"}


def common_inputs(f: Flow):
    f.var("base", "String", "input", BASE, "Folder of the real-world tests: data, output and the stand-in server.")
    f.var("run_id", "String", "input", "", "Run id shared by a main agent and its children; made here when empty.")
    f.var("standin", "String", "input", STANDIN, "Address of the local QA stand-in server (127.0.0.1 only).")
    f.var("watch", "Boolean", "input", True, "Show message boxes and pause between records so a person can follow the run.")
    f.var("pause_seconds", "Number", "input", 1, "Pause after each record when watch is on.")
    f.var("fault_profile", "String", "input", "none", "none, or resilience: the stand-ins inject the five failures of runbook step 09.")


class Child:
    """Builder for one child agent."""

    def __init__(self, test_id, child_no, title, responsibility, records_label, data_file=None, record_key="key"):
        self.test_id = test_id                      # RW01
        self.code = f"{test_id}-B{child_no}"        # RW01-B2
        self.flow_id = f"{test_id.lower()}_b{child_no}"
        self.title = title
        self.name = f"{self.code} {title}"
        self.responsibility = responsibility
        self.records_label = records_label
        self.data_file = data_file                  # relative to ${base}/data
        self.record_key = record_key
        self.f = Flow(self.flow_id, self.name, f"{self.code} ({test_id} real-world test). {responsibility}")
        self.f.tags = ["real-world-test", test_id]
        f = self.f
        common_inputs(f)
        for name, typ, default in [
            ("info", "Dictionary", None), ("out_dir", "String", ""), ("log_file", "String", ""),
            ("records", "Table", None), ("rec", "Dictionary", None), ("i", "Number", 0),
            ("err", "Dictionary", None), ("err_msg", "String", ""), ("rec_status", "String", ""),
            ("rec_note", "String", ""), ("started", "DateTime", None),
            ("results", "Table", {"columns": ["key", "status", "note", "time"], "rows": []}),
        ]:
            f.var(name, typ, "local", default)
        f.var("processed", "Number", "output", 0, "Records handled without an error.")
        f.var("exceptions", "Number", "output", 0, "Records routed to the exception list.")
        f.var("status", "String", "output", "not_run", "success when every record was handled (routed exceptions included).")
        f.var("result_file", "String", "output", "", "CSV file with one row per record.")

    # ---------- the four phases ----------
    def start(self):
        f = self.f
        with f.step("1. Start", f"What this agent does: {self.responsibility} "
                                 f"It works through {self.records_label} and writes its results to the run's output folder."):
            f.act("ooa.run_context/get", desc="Read the run context (run id, runner, user)", out={"info": "info"})
            f.expr("started", "now()", desc="Remember when the agent started")
            with f.if_({"op": "empty", "left": "$run_id$"}, desc="Running on its own: make a run id"):
                f.set("run_id", "SOLO-${formatDate(now(), '%Y%m%d-%H%M%S')}", desc="Run id for a run on its own")
            f.set("out_dir", "${base}/output/${run_id}/" + self.code, desc="This agent's output folder for the run")
            f.act("ooa.folder/create", desc="Create the output folder", path="$out_dir$")
            f.set("log_file", "${out_dir}/log.txt", desc="Log file of this agent")
            f.act("ooa.log/write", desc="Log the start", path="$log_file$",
                  message=f"{self.name} started; run ${{run_id}}; computer ${{info.device}}, user ${{info.os_user}}")
            f.box(f"{self.name}\n\n{self.responsibility}\n\nRecords: {self.records_label}\nRun: ${{run_id}}",
                  title=f"{self.code} starts", seconds=4)

    @contextmanager
    def prepare(self, why):
        with self.f.step("2. Prepare", why) as s:
            if self.data_file:
                self.f.act("ooa.csv/read", desc=f"Read the test records ({self.data_file})",
                           path="${base}/data/" + self.data_file, out={"table": "records"})
            yield s

    @contextmanager
    def records(self, why, over="$records$", summary="${rec_note}"):
        """The record loop. The body (yielded) must leave a short text in rec_note."""
        f = self.f
        with f.step("3. Work through the records", why):
            with f.loop(desc=f"Once for each of the {self.records_label}", iterator="table_rows", over=over, **{"as": "rec", "index": "i"}):
                f.set("rec_note", "", desc="Clear the note of the previous record")
                with f.try_(desc="Handle one record; a failure is caught below and the next record goes on") as t:
                    yield t
                    f.act("ooa.datatable/add_row", desc="Add the record's result to the results table", table="$results$",
                          values={"key": f"$rec.{self.record_key}$", "status": "done", "note": "$rec_note$",
                                  "time": "${formatDate(now(), '%H:%M:%S')}"}, out={"table": "results"})
                    f.expr("processed", "$processed$ + 1", desc="Count the record as handled")
                    f.box(f"{self.code}: record ${{$i + 1}} of ${{len($records$)}}\n\n{summary}",
                          title=f"{self.code} - record ${{rec.{self.record_key}}}", seconds=2)
                with f.catch(t, as_="err", message_to="err_msg"):
                    f.comment("The record failed: keep the reason, count it as an exception and go on with the next one.")
                    f.act("ooa.datatable/add_row", desc="Add the failure to the results table", table="$results$",
                          values={"key": f"$rec.{self.record_key}$", "status": "exception", "note": "${err.code}: ${err_msg}",
                                  "time": "${formatDate(now(), '%H:%M:%S')}"}, out={"table": "results"})
                    f.expr("exceptions", "$exceptions$ + 1", desc="Count the exception")
                    f.act("ooa.log/write", desc="Log the exception", path="$log_file$", level="WARN",
                          message=f"Record ${{rec.{self.record_key}}}: ${{err.code}} ${{err_msg}}")
                    f.box(f"{self.code}: record ${{rec.{self.record_key}}} went to the exception list\n\n${{err.code}}: ${{err_msg}}",
                          title=f"{self.code} - exception", seconds=3, icon="warning")
                f.pause()

    @contextmanager
    def finish(self, why="Save the results, set the outputs and say how it went."):
        f = self.f
        with f.step("4. Finish", why):
            yield
            f.set("result_file", "${out_dir}/results.csv", desc="Name of the results file")
            f.act("ooa.datatable/write_to_file", desc="Save one row per record", table="$results$", path="$result_file$")
            f.set("status", "success", desc="Every record was handled (exceptions are routed, not lost)")
            f.act("ooa.log/write", desc="Log the end", path="$log_file$",
                  message=f"{self.name} finished: ${{processed}} handled, ${{exceptions}} exceptions")
            f.box(f"{self.name} finished\n\nHandled: ${{processed}}\nExceptions: ${{exceptions}}\nResults: ${{result_file}}",
                  title=f"{self.code} finished", seconds=4)


# connection arguments of the real toolkits, pointed at the stand-in (live tenants: change the address and the locker)
def sn():
    return {"instance_url": "$standin$", "access_token": cred("servicenow")}


def sf():
    return {"instance_url": "$standin$", "access_token": cred("salesforce")}


def jira():
    return {"site_url": "$standin$", "personal_access_token": cred("jira")}


def graph(mailbox):
    return {"access_token": cred("graph"), "base_url": "${standin}/graph/v1.0", "mailbox": mailbox}


def evidence_var(f: Flow, columns):
    f.var("evidence", "Table", "local", {"columns": list(columns), "rows": []})
    f.var("evidence_file", "String", "output", "", "Evidence rows of this agent (one per record).")


def save_evidence(f: Flow):
    f.set("evidence_file", "${out_dir}/evidence.csv", desc="Name of the evidence file")
    f.act("ooa.datatable/write_to_file", desc="Save the evidence rows", table="$evidence$", path="$evidence_file$")


class Main:
    """A main agent: runs its six child agents (in order, or two at a time), cleans up and sums up."""

    def __init__(self, test_id, title, process, children, folder, parallel_group=(1, 3)):
        self.test_id, self.children, self.parallel_group = test_id, children, parallel_group
        self.f = Flow(f"{test_id.lower()}_main", f"{test_id} Main - {title}",
                      f"{test_id} real-world test: {process} Runs its six child agents, then cleans up the run's test records and sums up.")
        f = self.f
        f.tags = ["real-world-test", test_id, "main"]
        common_inputs(f)
        f.var("concurrency", "Number", "input", 1, "1 runs the children one after another (to watch); 2 or 3 runs children whose inputs are ready together.")
        for name, typ, default in [("info", "Dictionary", None), ("out_dir", "String", ""), ("log_file", "String", ""),
                                   ("files", "List", None), ("file", "String", ""), ("text", "String", ""), ("canary", "String", ""),
                                   ("leaks", "Number", 0), ("failed", "Number", 0), ("handled", "Number", 0), ("routed", "Number", 0),
                                   ("summary", "Table", {"columns": ["agent", "status", "handled", "exceptions", "results"], "rows": []})]:
            f.var(name, typ, "local", default)
        f.var("status", "String", "output", "not_run", "success when every child agent finished.")
        f.var("summary_file", "String", "output", "", "One row per child agent.")

    def run_child(self, flow_id, code, title, n=""):
        """Run one child; n gives parallel branches their own variables, so branches never overwrite each other's."""
        f = self.f
        child, status, error = "child" + n, "child_status" + n, "child_error" + n
        f.var(child, "Dictionary", "local")
        f.var(status, "String", "local", "")
        f.var(error, "Dictionary", "local")
        f.box(f"Next: {code} {title}", title=f"{self.test_id} main", seconds=2)
        f.act("core/run_bot", desc=f"Run {code} {title}", automation=flow_id, fail_on_error=False,
              inputs={"base": "$base$", "run_id": "$run_id$", "standin": "$standin$", "watch": "$watch$",
                      "pause_seconds": "$pause_seconds$", "fault_profile": "$fault_profile$"},
              out={"outputs": child, "status": status, "error": error})
        f.act("ooa.datatable/add_row", desc=f"Note how {code} went", table="$summary$",
              values={"agent": code, "status": "$" + status + "$", "handled": "${" + child + ".processed}", "exceptions": "${" + child + ".exceptions}",
                      "results": "${" + child + ".result_file}"}, out={"table": "summary"})
        with f.if_({"op": "ne", "left": "$" + status + "$", "right": "success"}, desc="Did the child agent fail?"):
            f.expr("failed", "$failed$ + 1", desc="Count the failed child")
            f.act("ooa.log/write", desc="Log the failure", path="$log_file$", level="ERROR",
                  message=code + " failed: ${" + error + ".code} ${" + error + ".message}")
        f.expr("handled", "$handled$ + toNumber(coalesce($" + child + ".processed, 0))", desc="Add up handled records")
        f.expr("routed", "$routed$ + toNumber(coalesce($" + child + ".exceptions, 0))", desc="Add up routed exceptions")

    def build(self):
        f, t = self.f, self.test_id
        with f.step("1. Start", "Make the run id every child shares, the output folder and the log; say what will happen."):
            f.act("ooa.run_context/get", desc="Read the run context", out={"info": "info"})
            with f.if_({"op": "empty", "left": "$run_id$"}, desc="No run id given: make one"):
                f.set("run_id", t + "-${formatDate(now(), '%Y%m%d-%H%M%S')}", desc="Run id for this run")
            f.set("out_dir", "${base}/output/${run_id}", desc="Output folder of the run")
            f.act("ooa.folder/create", desc="Create the output folder", path="$out_dir$")
            f.set("log_file", "${out_dir}/" + t + "-main-log.txt", desc="Log file of the main agent")
            f.act("ooa.log/write", desc="Log the start", path="$log_file$", message=f"{f.name} started; run ${{run_id}}")
            f.box(f"{f.name}\n\n" + "\n".join(f"{c[1]} {c[2]}" for c in self.children) + "\n\nRun: ${run_id}", title=f"{t} starts", seconds=5)
        with f.step("2. Prepare", "Start the QA stand-ins (if they are not running) and, for a resilience run, arm their faults."):
            f.act("core/run_bot", desc="Start the QA stand-ins", automation="rw00_standins",
                  inputs={"base": "$base$", "standin": "$standin$", "watch": "$watch$"})
            with f.if_({"op": "ne", "left": "$fault_profile$", "right": "none"}, desc="Resilience run?"):
                f.act("ooa.rest/request", desc="Arm the injected faults on every stand-in service", method="POST", url="${standin}/qa/faults",
                      json={"service": "all", "profile": "$fault_profile$", "run_id": "$run_id$"})
        with f.step("3. Run the child agents", "Each child works through its 10 records; a failing child is noted and the next one still runs."):
            with f.if_("$concurrency$ <= 1", desc="One after another (to watch)") as one:
                for c in self.children:
                    self.run_child(*c)
            with f.else_(one):
                if self.parallel_group is None:
                    f.comment("Each child needs the one before it (a strict chain), so they still run one after another; "
                              "concurrency only speeds up the automations whose children are independent.")
                    for c in self.children:
                        self.run_child(*c)
                else:
                    a, b = self.parallel_group
                    f.comment("Two at a time where it is safe: the intake child first (the others use what it accepts), then the next two "
                              "together, then the rest in order.")
                    for c in self.children[:a]:
                        self.run_child(*c)
                    with f.parallel("$concurrency$", desc="Children that need only the intake's results"):
                        for i, c in enumerate(self.children[a:b]):
                            with f.step(c[1]):
                                self.run_child(*c, n=str(i + 1))
                    for c in self.children[b:]:
                        self.run_child(*c)
        with f.step("4. Clean up and attest", "Remove only this run's OOA-QA records from the stand-ins, and check no output file holds the secret canary."):
            f.act("ooa.rest/request", desc="Remove this run's test records from the stand-ins", method="POST", url="${standin}/qa/cleanup", json={"run_id": "$run_id$"})
            f.act("ooa.global_values/get", desc="Read the canary text (also kept as a vault secret)", name="rw_canary", default="", type="String", out={"value": "canary"})
            f.act("ooa.file/list", desc="List every file the run wrote", folder="$out_dir$", pattern="*.csv", include_subfolders=True, out={"files": "files"})
            with f.loop(desc="Each output file", iterator="list", over="$files$", **{"as": "file"}):
                f.act("ooa.file/read_text", desc="Read the file", path="$file$", out={"text": "text"})
                with f.if_({"all": [{"op": "ne", "left": "$canary$", "right": ""}, {"op": "contains", "left": "$text$", "right": "$canary$"}]}, desc="Does it hold the canary?"):
                    f.expr("leaks", "$leaks$ + 1", desc="Count the leak")
                    f.act("ooa.log/write", desc="Log the leak", path="$log_file$", level="ERROR", message="Secret canary found in ${file}")
        with f.step("5. Sum up", "Save one row per child, record the totals for Insights, and say how the run went."):
            f.set("summary_file", "${out_dir}/" + t + "-summary.csv", desc="Name of the summary file")
            f.act("ooa.datatable/write_to_file", desc="Save the summary", table="$summary$", path="$summary_file$")
            f.act("ooa.insights/tag_values", desc="Record the run's totals for Insights", category="real_world_tests",
                  values={"test": t, "handled": "$handled$", "exceptions": "$routed$", "failed_children": "$failed$", "canary_leaks": "$leaks$"})
            f.expr("status", "if($failed$ == 0 and $leaks$ == 0, 'success', 'failed')", desc="Overall result")
            with f.if_("$watch$", desc="A sound at the end (watch is on)"):
                f.act("ooa.sound/beep", desc="Beep", times=2)
            f.box(f"{f.name} finished: ${{status}}\n\nRecords handled: ${{handled}}\nRouted to exceptions: ${{routed}}\nChild agents failed: ${{failed}}\n"
                  "Canary leaks: ${leaks}\n\nSummary: ${summary_file}", title=f"{t} finished", seconds=6)
            f.act("ooa.log/write", desc="Log the end", path="$log_file$", message=f"{f.name} finished: ${{status}}")
        return f


def fault_setup(f: Flow, service):
    """Tell the stand-in which fault profile this run uses (runbook step 09)."""
    with f.if_({"op": "ne", "left": "$fault_profile$", "right": "none"}, desc="Resilience run: arm the stand-in's faults"):
        f.act("ooa.rest/request", desc="Arm the stand-in's injected faults for this service", method="POST",
              url=f"${{standin}}/qa/faults", json={"service": service, "profile": "$fault_profile$", "run_id": "$run_id$"})
