"""Test data for RW05 Access review: 10 synthetic QA identities across Entra ID, Salesforce, Jira, ServiceNow, Slack and Okta,
a versioned role matrix, privileged grants and segregation-of-duties rules."""
import datetime as _dt
import hashlib

MATRIX_VERSION = "v3"
ROLES = {   # role -> expected grants (app:grant)
    "support-agent": ["entra:GRP-QA-Support", "sf:Service_Console", "jira:qa-support", "sn:itil", "okta:QA-Support"],
    "release-manager": ["entra:GRP-QA-Readers", "jira:release-managers", "sn:change_manager", "okta:QA-Release"],
    "finance-analyst": ["entra:GRP-QA-Readers", "sf:Finance_Reports", "sn:finance_viewer", "okta:QA-Finance"],
}
ROLE_OWNERS = {"support-agent": "lead.support@ooa-qa.test", "release-manager": "lead.release@ooa-qa.test", "finance-analyst": "lead.finance@ooa-qa.test"}
PRIVILEGED = ["entra:GRP-QA-Admins", "sf:Modify_All_Data", "sn:admin", "jira:jira-admins", "okta:QA-Admins"]
SOD = [("jira:release-managers", "sn:change_approver"), ("sf:Finance_Reports", "sn:finance_approver")]
APP_OWNERS = [("entra", "owner.entra@ooa-qa.test"), ("sf", "owner.sf@ooa-qa.test"), ("jira", "owner.jira@ooa-qa.test"),
              ("sn", "owner.sn@ooa-qa.test"), ("okta", "owner.okta@ooa-qa.test")]

# key, first, last, role, OU, manager, days since last sign-in, extra grants, missing grants, notes
PEOPLE = [
    ("I01", "Ann", "Lee", "support-agent", "OU=QA", "lead.support@ooa-qa.test", 3, [], [], "clean"),
    ("I02", "Ben", "Ray", "support-agent", "OU=QA", "lead.support@ooa-qa.test", 5, ["sf:Modify_All_Data"], [], "privileged grant beyond the role"),
    ("I03", "Cara", "Ng", "release-manager", "OU=QA", "owner.sn@ooa-qa.test", 2, ["sn:change_approver"], [], "SoD: releases and approves changes"),
    ("I04", "Dev", "Rao", "finance-analyst", "OU=QA", "lead.finance@ooa-qa.test", 200, [], [], "dormant"),
    ("I05", "Eli", "Sun", "support-agent", "OU=QA", "lead.support@ooa-qa.test", 9, [], [], "two Jira accounts with one email"),
    ("I06", "Fay", "Ito", "support-agent", "OU=QA", "lead.support@ooa-qa.test", 1, ["entra:GRP-QA-Nest-A"], [], "admin through nested groups (with a cycle)"),
    ("I07", "Gus", "Kim", "support-agent", "OU=Staff", "lead.support@ooa-qa.test", 4, [], [], "outside the QA OU"),
    ("I08", "Hal", "Oak", "release-manager", "OU=QA", "lead.release@ooa-qa.test", 6, [], ["okta:QA-Release"], "missing a role grant"),
    ("I09", "Ivy", "Paz", "finance-analyst", "OU=QA", "someone.else@ooa-qa.test", 12, [], [], "manager differs from the role owner"),
    ("I10", "Jon", "Uhl", "finance-analyst", "OU=QA", "lead.finance@ooa-qa.test", 60, [], [], "clean (not yet dormant)"),
]
DECISIONS = {"I08 add okta:QA-Release#2": "decline"}   # the second approver declines adding I08 (others approve)


def hexid(*parts):
    """A ServiceNow-shaped sys_id (32 hex characters), the same on every run."""
    return hashlib.md5("|".join(map(str, parts)).encode()).hexdigest()


def upn(p):
    return "qa.%s.%s@ooa-qa.test" % (p[1].lower(), p[2].lower())


def grants(p):
    g = [x for x in ROLES[p[3]] if x not in p[8]] + p[7]
    return g


def write(w):
    w("RW05/principals.csv", [(p[0], upn(p), "%s %s" % (p[1], p[2])) for p in PEOPLE], ["key", "upn", "name"])
    w("RW05/role_matrix.csv", [(MATRIX_VERSION, r, g, "yes" if g in PRIVILEGED else "no") for r, gs in ROLES.items() for g in gs],
      ["version", "role", "grant", "privileged"])
    w("RW05/role_owners.csv", list(ROLE_OWNERS.items()), ["role", "owner"])
    w("RW05/privileged.csv", [(g,) for g in PRIVILEGED], ["grant"])
    w("RW05/sod_rules.csv", SOD, ["grant_a", "grant_b"])
    w("RW05/app_owners.csv", APP_OWNERS, ["app", "owner"])
    w("RW05/assignments.csv", [(p[0], p[3]) for p in PEOPLE], ["key", "role"])


def seed():
    now = _dt.datetime.now(_dt.timezone.utc)
    names = sorted({g.split(":", 1)[1] for p in PEOPLE for g in grants(p) if g.startswith("entra:")} | {"GRP-QA-Admins", "GRP-QA-Nest-B"})
    gid = {n: "g-" + n.lower().replace("grp-qa-", "") for n in names}
    users, members = [], {gid[n]: [] for n in names}
    for p in PEOPLE:
        uid = "u-qa-" + p[0].lower()
        users.append({"id": uid, "userPrincipalName": upn(p), "displayName": "%s %s" % (p[1], p[2]), "accountEnabled": True, "mail": upn(p),
                      "department": "QA", "onPremisesDistinguishedName": "CN=%s %s,%s,DC=ooa-qa,DC=test" % (p[1], p[2], p[4]),
                      "manager": p[5], "signInActivity": {"lastSignInDateTime": (now - _dt.timedelta(days=p[6])).strftime("%Y-%m-%dT%H:%M:%SZ")}})
        for g in grants(p):
            if g.startswith("entra:"):
                members[gid[g.split(":", 1)[1]]].append(uid)
    # nesting with a cycle: Nest-A is in Nest-B, Nest-B is in Nest-A, and Nest-B is in Admins
    members[gid["GRP-QA-Nest-B"]].append(gid["GRP-QA-Nest-A"])
    members[gid["GRP-QA-Nest-A"]].append(gid["GRP-QA-Nest-B"])
    members[gid["GRP-QA-Admins"]].append(gid["GRP-QA-Nest-B"])
    groups = [{"id": gid[n], "displayName": n, "securityEnabled": True} for n in names]

    sf_users, psa, jira_users, jira_groups, sn_users, sn_roles, slack_users, okta_users = [], [], [], {}, [], [], [], []
    okta_groups = {}
    for i, p in enumerate(PEOPLE, 1):
        mail = upn(p)
        sf_users.append({"Id": "005QA%010d" % i, "Email": mail, "Username": mail + ".qa", "IsActive": True, "Name": "%s %s" % (p[1], p[2])})
        acc = ["qa-acc-%02d" % i] + (["qa-acc-%02d-old" % i] if p[0] == "I05" else [])
        for a in acc:
            jira_users.append({"accountId": a, "emailAddress": mail, "displayName": "%s %s" % (p[1], p[2]), "active": True})
        sn_users.append({"sys_id": hexid("snu", i), "user_name": mail.split("@")[0], "email": mail, "active": "true"})
        slack_users.append({"id": "UQA%04d" % i, "name": mail.split("@")[0], "deleted": False, "is_admin": False, "profile": {"email": mail, "real_name": "%s %s" % (p[1], p[2])}})
        okta_users.append({"id": "00uqa%04d" % i, "status": "ACTIVE", "profile": {"login": mail, "email": mail, "firstName": p[1], "lastName": p[2]}})
        for g in grants(p):
            app, name = g.split(":", 1)
            if app == "sf":
                psa.append({"Id": "0PaQA%04d%09d" % (i, len(psa)), "AssigneeId": "005QA%010d" % i, "PermissionSet.Name": name})
            elif app == "jira":
                jira_groups.setdefault(name, []).append(acc[0])
            elif app == "sn":
                sn_roles.append({"sys_id": hexid("snr", i, name), "user": hexid("snu", i), "role": name})
            elif app == "okta":
                okta_groups.setdefault(name, []).append("00uqa%04d" % i)
    slack_users.append({"id": "UQA9999", "name": "ghost.user", "deleted": False, "is_admin": True, "profile": {"email": "ghost.user@ooa-qa.test", "real_name": "Ghost User"}})
    for n in ["QA-Support", "QA-Release", "QA-Finance", "QA-Admins"]:
        okta_groups.setdefault(n, [])
    return {"graph_directory": {"users": users, "groups": groups, "members": members},
            "salesforce": {"User": sf_users, "PermissionSetAssignment": psa, "Organization": [{"Id": "00DQA0000000001", "Name": "OOA QA sandbox", "IsSandbox": True}]},
            "jira_directory": {"users": jira_users, "groups": jira_groups},
            "servicenow": {"sys_user": sn_users, "sys_user_has_role": sn_roles},
            "slack": {"users": slack_users},
            "okta": {"users": okta_users, "groups": [{"id": "00gqa" + n.lower().replace("-", ""), "profile": {"name": n}, "_members": ids} for n, ids in okta_groups.items()]},
            "approvals": {"decisions": DECISIONS, "requests": []}}
