"""Cases for the Network commands the samples do not cover. There is no SNMP agent on this computer, so the SNMP
reads and writes are pointed at a closed local port and must time out (nothing changes anywhere); a trap is sent to a
closed local port (SNMP traps are not answered, so sending succeeds)."""
from helpers_office import fix_samples, fcase

OID = "1.3.6.1.2.1.1.1.0"


def add(ua):
    fix_samples(ua)
    for n, t in (("o", "Boolean"), ("eng", "String"), ("ms", "Number"), ("r", "Boolean")):
        ua.var(n, t)
    common = {"host": "127.0.0.1", "port": 16199, "timeout_ms": 200, "retries": 0}
    for cmd, args, what in (
            ("snmp_get_next", {"oid": OID}, "Get next reads the object after an OID"),
            ("snmp_walk", {"oid": "1.3.6.1.2.1.1"}, "Walk reads every object under an OID"),
            ("snmp_set", {"oid": "1.3.6.1.2.1.1.6.0", "value": "UT room"}, "Set changes one value on a device")):
        with fcase(ua, "ooa.network/" + cmd, "no agent answers",
                   what + ". No SNMP agent listens on the closed local port, so the step stops with SNMP.TIMEOUT.") as (f, d):
            ua.expect_error("SNMP.TIMEOUT", lambda ff, c=cmd, a=args: ff.act("ooa.network/" + c, desc="Ask a closed port",
                                                                             **common, **a))
    with fcase(ua, "ooa.network/snmp_send_trap", "trap to a local port",
               "Send trap sends a notification to a monitoring system; it is not answered, so it is sent to a closed "
               "local port and the step succeeds.") as (f, d):
        f.act("ooa.network/snmp_send_trap", desc="Send a v2c trap", host="127.0.0.1", port=16299,
              trap_oid="1.3.6.1.4.1.99999.0.1", variables={"1.3.6.1.4.1.99999.1": "unit test"}, out={"engine_id": "eng"})
        ua.check("ne", "$eng$", "UT-NOT-SET", what="the step finished")
    with fcase(ua, "ooa.network/ping", "loopback twice",
               "Ping checks a host answers; two pings to this computer both come back.") as (f, d):
        f.act("ooa.network/ping", desc="Ping 127.0.0.1 twice", host="127.0.0.1", count=2, interval_ms=200,
              out={"reachable": "r", "replies": "ms"})
        ua.check_eq("r", True)
        ua.check_eq("ms", 2)
    with fcase(ua, "ooa.network/check_port", "the stand-in's port is open",
               "Check port is true for a port that accepts connections: the local stand-in on 8791.") as (f, d):
        f.act("ooa.network/check_port", desc="Check port 8791", host="127.0.0.1", port=8791, out={"open": "o"})
        ua.check_eq("o", True)
