"""Shared pieces of the Microsoft 365, Google Workspace and Active Directory unit-test agents.

The command samples of these toolkits answer from the CLI test's own mock ({mock}) or LDAP server ({ldap}); they cannot
run in an agent, so start() takes every converted sample out and every case here talks to the stand-in plugins instead:
  Microsoft Graph   ${standin_url}/msgraph/v1.0     (standin/plugins/msg_graph.py)
  SharePoint site   ${standin_url}/msgsp/sites/Finance
  Google Workspace  ${standin_url}/msgoogle        (standin/plugins/msg_google.py)
Tokens are made-up vault values: unit_tests/m365/token, unit_tests/google/token, unit_tests/sharepoint/token.
"""
from __future__ import annotations

import json

GRAPH = "${standin_url}/msgraph/v1.0"
SP_SITE = "${standin_url}/msgsp/sites/Finance"
GOOGLE = "${standin_url}/msgoogle"
M365_TOKEN = {"$credential": "unit_tests/m365/token"}
GOOGLE_TOKEN = {"$credential": "unit_tests/google/token"}
SP_TOKEN = {"$credential": "unit_tests/sharepoint/token"}
SITE = "ut.sharepoint.test:/sites/Finance"
ME = "una.tester@ut-m365.test"
ANN = "ann.lee@ut-m365.test"
ANN_ID = "1c2d3e4f-5061-7283-94a5-b6c7d8e9f0a1"
TEAM = "5061-team-ut-finance"
GENERAL = "19:general@thread.tacv2"
GME = "una.tester@ut-google.test"


def start(ua, reset_path: str, about: str):
    """Takes out the converted samples (they need the CLI test's mock) and resets the stand-in's records first."""
    keep = ua.f.steps[:1]   # the "About this agent" step; every converted sample after it goes
    ua.f.steps[:] = keep
    ua.n = 0
    ua.covered = set()
    ua.var("ut_v", "Any", None)
    if reset_path:
        with ua.f.step("Prepare: fresh stand-in records", why=about):
            ua.f.act("ooa.rest/request", desc="Put the stand-in's made-up records back as they were seeded",
                     url="${standin_url}" + reset_path, method="POST", out={"status": "ut_v"})


def check(ua, expr: str, expected, what: str, op: str = "eq"):
    """Works out an expression into ut_v and checks it (ops as in If: eq, ne, contains, gt, notEmpty, ...)."""
    ua.f.expr("ut_v", expr, desc="Take " + what + " for the check")
    ua.check(op, "$ut_v$", expected, what=what)


def g(**kw):
    """Arguments every Graph step takes."""
    return dict(access_token=M365_TOKEN, base_url=GRAPH, **kw)


def gg(**kw):
    """Arguments every Google step takes."""
    return dict(access_token=GOOGLE_TOKEN, base_url=GOOGLE, **kw)
