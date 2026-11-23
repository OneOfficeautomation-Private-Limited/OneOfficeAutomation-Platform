"""Shared bits for the business-application unit-test agents (cases/<group>.py of groups_business.py).

The command samples of these toolkits are written for the test harness's mock server ({mock} addresses, made-up
variables); they do not run in an agent, so each case file starts with drop_samples(ua) and writes its own cases
against the stand-in's /biz/<service>/ routes (standin/plugins/biz_<service>.py).
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

BASE_VARS = 6       # ut_dir, standin_url, ut_pass, ut_fail, ut_report, ut_err


def drop_samples(ua):
    """Removes the cases gen.py added from the command samples (they need the harness's mock server)."""
    f = ua.f
    f.steps[:] = f.steps[:1]
    del f.variables[BASE_VARS:]
    ua.n = 0
    ua.covered = set()


def cred(service: str, attr: str = "token") -> dict:
    """A made-up vault value: unit_tests/<service>/<attr> (OOA_SECRET_UNIT_TESTS_<SERVICE>_<ATTR> in secrets.env)."""
    return {"$credential": f"unit_tests/{service}/{attr}"}


def url(service: str, rest: str = "") -> str:
    """The stand-in address for one service: ${standin_url}/biz/<service><rest>."""
    return "${standin_url}/biz/" + service + rest


def vars_(ua, *names, type_="Any"):
    for n in names:
        ua.var(n, type_)
