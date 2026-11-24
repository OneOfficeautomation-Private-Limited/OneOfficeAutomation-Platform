"""Cases for the Windows service commands: read-only checks on real services; the commands that change a service
run only against a service name that does not exist, so no real service is touched."""
from helpers_office import fix_samples, fcase

NONE = "OoaUtNoSuchService9f3a"


def add(ua):
    fix_samples(ua)
    ua.var("s", "String")
    for cmd, what in (("stop", "Stop waits until a service has stopped"), ("restart", "Restart stops then starts a service"),
                      ("pause", "Pause pauses a service that supports it"), ("resume", "Resume continues a paused service")):
        with fcase(ua, "ooa.services/" + cmd, "unknown service",
                   what + ". No real service is changed in a unit test: a service that does not exist stops the step "
                   "with SERVICES.NOT_FOUND.") as (f, d):
            ua.expect_error("SERVICES.NOT_FOUND", lambda ff, c=cmd: ff.act("ooa.services/" + c, desc=c.title() + " a service that does not exist",
                                                                          name=NONE, timeout_seconds=5))
    with fcase(ua, "ooa.services/get_status", "a service that is usually stopped or running",
               "Get status returns one of the service states; the Windows Update service is RUNNING or STOPPED.") as (f, d):
        f.act("ooa.services/get_status", desc="Status of wuauserv", name="wuauserv", out={"status": "s"})
        ua.check("matches", "$s$", "^(RUNNING|STOPPED|START_PENDING|STOP_PENDING|PAUSED)$", what="status")
    with fcase(ua, "ooa.services/is_running", "unknown service",
               "Is running is a condition; for a service that does not exist it stops with SERVICES.NOT_FOUND instead "
               "of answering false, so a misspelt name is noticed.") as (f, d):
        def use(ff):
            with ff.if_({"condition": "ooa.services/is_running", "args": {"name": NONE}}, desc="Is it running?"):
                ff.set("s", "running")
        ua.expect_error("SERVICES.NOT_FOUND", use)
