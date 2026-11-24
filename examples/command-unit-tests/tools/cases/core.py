"""Cases for the Core group: the samples (fuller converter) and hand-written cases for what they miss.

run_bot and agent_succeeded run other agents checked in to One CommandCenter (here UT Convert, id ut_convert, which
is in the same folder): they pass only when this agent runs in One CommandCenter.
"""
from helpers_core import resample


def add(ua):
    # the Stop samples would end the agent half-way (a Try cannot catch Stop, not even with status failed): Stop is
    # the last case instead (_stop_last)
    resample(ua, drop={("core/stop", "ends the run, finally still runs"), ("core/stop", "failed status")})

    # ---- Watch for interruptions ----
    ua.var("popup", "Boolean", False)
    ua.var("handled", "Number", 0)
    ua.var("after_scope", "Boolean", False)
    with ua.case("core/interrupt_scope", "a pop-up is dealt with while the steps wait",
                 "Watch for interruptions runs its steps while checking its handlers: here a step makes a pretend pop-up "
                 "appear (a variable turns true) and then waits; the handler notices it, closes it and counts it. The "
                 "check is that the handler ran once and the steps after the scope carried on.") as f:
        f.set("popup", False)
        f.set("handled", 0)
        f.set("after_scope", False)
        s = {"id": f._id(), "action": "core/interrupt_scope", "description": "Watch for the pretend pop-up",
             "args": {"title": "Pretend pop-up", "check_every_seconds": 0.1}, "children": [],
             "handlers": [{"name": "Close the pop-up", "when": "$popup", "children": []}]}
        f._add(s)
        f._stack.append(s["children"])
        f.set("popup", True, desc="The pop-up appears")
        f.act("core/delay", desc="Wait while the handler deals with it", seconds=500, unit="milliseconds")
        f._stack.pop()
        f._stack.append(s["handlers"][0]["children"])
        f.set("popup", False, desc="Close the pop-up")
        f.expr("handled", "$handled + 1", desc="Count it")
        f._stack.pop()
        f.set("after_scope", True, desc="The steps after the scope run")
        ua.check_eq("handled", 1)
        ua.check_eq("popup", False)
        ua.check_eq("after_scope", True)

    # ---- Session is open (condition) ----
    ua.condition_case("core/session_open", "an open JSON session",
                      "Session is open is a condition: true while a session with this name is open in the run. A JSON "
                      "session is opened just before (in the case below it is closed again).",
                      {"session": "ut_core_json"}, True)
    # the session has to be open before the If: put the open step in front of the If made by condition_case
    case_steps = ua.f.steps[-1]["children"][1]["children"]      # Step group > [comment, try] > try children
    case_steps.insert(1, {"id": ua.f._id(), "action": "ooa.json/start_session", "description": "Open a JSON session",
                          "args": {"value": {"a": 1}, "session": "ut_core_json"}})
    case_steps.insert(3, {"id": ua.f._id(), "action": "ooa.json/end_session", "description": "Close it again",
                          "args": {"session": "ut_core_json"}})
    ua.condition_case("core/session_open", "no such session",
                      "The same condition is false for a session name that is not open.",
                      {"session": "ut_core_json"}, False)

    # ---- Run agent / Agent succeeded (need One CommandCenter) ----
    ua.var("child_status", "String", "")
    ua.var("child_runs", "Number", 0)
    ua.var("child_pass", "Number", 0)
    with ua.case("core/run_bot", "run the UT Convert agent (needs One CommandCenter)",
                 "Run agent starts another checked-in agent (UT Convert, in this folder) and keeps its outputs: its "
                 "ut_pass output goes into child_pass. Checks: status success, one run, at least one case passed. "
                 "Runs only in One CommandCenter, where checked-in agents can be found.") as f:
        f.act("core/run_bot", desc="Run UT Convert and keep its ut_pass", automation="ut_convert",
              output_map={"ut_pass": "child_pass"}, timeout_seconds=600,
              out={"status": "child_status", "runs": "child_runs"})
        ua.check_eq("child_status", "success")
        ua.check_eq("child_runs", 1)
        ua.check("gt", "$child_pass$", 0, what="child_pass")
    ua.condition_case("core/agent_succeeded", "UT Convert succeeds (needs One CommandCenter)",
                      "Agent succeeded is a condition: it runs another checked-in agent and is true when it ends "
                      "without an error. Runs only in One CommandCenter.",
                      {"automation": "ut_convert"}, True)

    _stop_last(ua)


def _stop_last(ua):
    """Stop ends the whole agent, so its case comes last: the summary is placed in the Finally of its Try, which still
    runs after Stop (that is what the case checks)."""
    f = ua.f
    ua.n += 1
    ua.covered.add("core/stop")
    ua.var("ut_after_stop", "Boolean", False)
    label = "core/stop: ends the run, Finally steps still run"
    with f.step(f"{ua.n}. core/stop - ends the run; Finally steps still run (the summary is in them)",
                why="Stop agent ends the run at once with the status chosen (here success), but the Finally steps of "
                    "a Try around it still run. This case is the last one: the step after Stop must not run, and the "
                    "agent's summary is in the Finally, so it running at all shows Finally works after Stop."):
        f.set("ut_after_stop", False)
        with f.try_(desc="Stop the agent here") as t:
            f.act("core/stop", desc="End the run now (success)", message="Command unit tests: Stop case reached",
                  status="success")
            f.set("ut_after_stop", True, desc="Must not run: Stop has ended the agent")
        with f.finally_(t):
            with f.if_({"op": "eq", "left": "$ut_after_stop$", "right": False}, desc="Did Stop end the steps?") as s:
                f.expr("ut_pass", "$ut_pass + 1", desc="Count the case as passed")
                f.set("ut_report", "${ut_report}PASS  " + label + "\n", desc="Add a PASS line to the report")
            with f.else_(s):
                f.expr("ut_fail", "$ut_fail + 1", desc="Count the case as failed")
                f.set("ut_report", "${ut_report}FAIL  " + label + ": steps after Stop ran\n", desc="Add a FAIL line")
            fin = f._stack[-1]
    orig = ua.finish

    def finish():
        f._stack.append(fin)
        try:
            return orig()
        finally:
            f._stack.pop()
    ua.finish = finish
