import threading
import time

import pytest

from ooa.runner import ListSink, Runner
from ooa.sdk import ActionException, Input, Output, action, package, sign_package, PublisherKey
from ooa.sdk.registry import PackageRegistry
from ooa.packages import FIRST_PARTY


@package("acme.flaky", "Flaky", "1.0.0", description="Test package with controllable failures.", publisher="acme")
class Flaky:
    calls = 0

    @action("sometimes", "Sometimes fails", description="Fails the first N calls with a retryable error.",
            inputs=[Input("fail_times", "Number", description="How many calls fail.")],
            outputs=[Output("attempts", "Number", "Calls so far.")])
    def sometimes(self, ctx, fail_times):
        Flaky.calls += 1
        if Flaky.calls <= fail_times:
            raise ActionException("transient", code="ACME.TRANSIENT", retryable=True)
        return {"attempts": Flaky.calls}

    @action("slow", "Slow", description="Sleeps.", inputs=[Input("seconds", "Number", description="Sleep time.")],
            timeout_seconds=0.2)
    def slow(self, ctx, seconds):
        time.sleep(float(seconds))
        return {}

    @action("crash", "Crash", description="Raises a plain Python exception.")
    def crash(self, ctx):
        raise ZeroDivisionError("bug")

    @action("leaky_log", "Leaky log", description="Logs a secret by mistake.",
            inputs=[Input("secret", "Credential", description="A credential.")])
    def leaky_log(self, ctx, secret):
        ctx.log.info(f"password is {secret.reveal()}")
        return {}


@pytest.fixture(scope="module")
def reg():
    key, acme = PublisherKey.generate("oneofficeautomation"), PublisherKey.generate("acme")
    r = PackageRegistry()
    r.trust(key); r.trust(acme)
    for cls in FIRST_PARTY:
        r.install(cls, sign_package(cls, key))
    r.install(Flaky, sign_package(Flaky, acme))
    return r


def bot(steps, variables=None, **kw):
    return {"kind": "TaskBot", "id": "t", "name": "t", "variables": variables or [], "steps": steps, **kw}


def test_retry_with_backoff_events(reg):
    Flaky.calls = 0
    sink = ListSink()
    f = bot([{"id": "s", "action": "acme.flaky/sometimes", "args": {"fail_times": 2}, "out": {"attempts": "n"},
              "retry": {"max": 3, "backoff": "exponential", "delayMs": 1}}],
            [{"name": "n", "type": "Number", "direction": "output"}])
    res = Runner(reg, sink=sink).run(f)
    assert res.ok and res.outputs["n"] == 3
    assert [e.data["attempt"] for e in sink.of_type("step.retry")] == [1, 2]


def test_retry_exhausted(reg):
    Flaky.calls = 0
    f = bot([{"id": "s", "action": "acme.flaky/sometimes", "args": {"fail_times": 5}, "retry": {"max": 1, "delayMs": 1}}])
    res = Runner(reg).run(f)
    assert res.status == "failed" and res.error["code"] == "ACME.TRANSIENT"


def test_action_timeout(reg):
    res = Runner(reg).run(bot([{"id": "s", "action": "acme.flaky/slow", "args": {"seconds": 2}}]))
    assert res.error["code"] == "STEP.TIMEOUT" and res.error["stepId"] == "s"


def test_package_bug_becomes_typed_error(reg):
    res = Runner(reg).run(bot([{"id": "s", "action": "acme.flaky/crash"}]))
    assert res.error["code"] == "ACTION.UNEXPECTED" and "ZeroDivisionError" in res.error["message"]


def test_secrets_redacted_from_logs_and_events(reg, vault):
    sink = ListSink()
    f = bot([{"id": "s", "action": "acme.flaky/leaky_log", "args": {"secret": "$pw"}}],
            [{"name": "pw", "type": "Credential", "direction": "input", "required": True}])
    res = Runner(reg, sink=sink, services={"vault": vault}).run(f, {"pw": {"$credential": "test/app/password"}})
    assert res.ok
    blob = str([e.to_dict() for e in sink.events])
    assert "s3cr3t" not in blob and "****" in blob


def test_cancel_stops_run(reg):
    cancel = threading.Event()
    f = bot([{"id": "d", "action": "core/delay", "args": {"seconds": 5}}])
    threading.Timer(0.1, cancel.set).start()
    t0 = time.monotonic()
    res = Runner(reg).run(f, cancel_event=cancel)
    assert res.status == "stopped" and time.monotonic() - t0 < 2


def test_run_time_limit(reg):
    res = Runner(reg).run(bot([{"id": "d", "action": "core/delay", "args": {"seconds": 5}}]), max_run_seconds=0.1)
    assert res.error["code"] == "RUN.TIMEOUT"


def test_parallel_branches(reg):
    f = bot([{"id": "p", "action": "core/parallel", "children": [
        {"id": "a", "action": "core/delay", "args": {"seconds": 0.3}},
        {"id": "b", "action": "core/delay", "args": {"seconds": 0.3}},
        {"id": "c", "action": "core/delay", "args": {"seconds": 0.3}}]}])
    t0 = time.monotonic()
    assert Runner(reg).run(f).ok
    assert time.monotonic() - t0 < 0.8


def test_plan_hash_is_deterministic_and_ignores_layout(reg):
    f = bot([{"id": "c", "action": "core/comment"}])
    h1 = Runner(reg).run(f).plan_hash
    h2 = Runner(reg).run({**f, "ui": {"c": {"x": 1}}}).plan_hash
    h3 = Runner(reg).run({**f, "name": "renamed"}).plan_hash
    assert h1 == h2 != h3


def test_child_bot_with_repository(reg):
    from ooa.governance import Repository
    repo = Repository()
    child = {"kind": "ApiTask", "id": "double", "name": "double", "description": "doubles",
             "variables": [{"name": "x", "type": "Number", "direction": "input", "required": True},
                           {"name": "y", "type": "Number", "direction": "output"}],
             "steps": [{"id": "s", "action": "core/set", "args": {"variable": "y", "value": {"expr": "$x * 2"}}}]}
    repo.check_in(child, registry=reg)
    parent = bot([{"id": "call", "action": "core/run_bot", "args": {"automation": "double", "inputs": {"x": 21}},
                   "out": {"outputs": "res"}},
                  {"id": "pick", "action": "core/set", "args": {"variable": "answer", "value": "$res.y"}}],
                 [{"name": "res", "type": "Dictionary"}, {"name": "answer", "type": "Number", "direction": "output"}])
    res = Runner(reg, services={"repository": repo}).run(parent)
    assert res.ok and res.outputs["answer"] == 42


def test_apitask_cannot_require_desktop(reg):
    from ooa.runner import validate_flow
    f = {"kind": "ApiTask", "id": "a", "name": "a", "requires": {"capabilities": ["desktop"]}, "steps": []}
    probs = validate_flow(f, reg, raise_on_error=False)
    assert any("desktop" in p["message"] for p in probs)


def test_validation_reports_all_problems_with_fixes(reg):
    from ooa.runner import validate_flow
    f = bot([{"id": "s", "action": "ooa.excel/get_cell", "args": {"cell": "b2", "nope": 1}, "out": {"value": "ghost"}},
             {"id": "s", "action": "ooa.nothing/act"}])
    probs = validate_flow(f, reg, raise_on_error=False)
    msgs = " | ".join(p["message"] for p in probs)
    for frag in ("Duplicate step id", "Unknown input 'nope'", "not declared", "does not match", "not installed"):
        assert frag in msgs, frag
