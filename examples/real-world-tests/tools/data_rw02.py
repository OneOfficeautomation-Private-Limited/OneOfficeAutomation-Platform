"""Test data for RW02 Employee lifecycle: 10 synthetic OOA-QA HR requests (joiners, movers, leavers) and what goes with them."""
import datetime as _dt

TENANT = "ooa_qa"
TODAY = _dt.date.today()


def day(n):
    return (TODAY + _dt.timedelta(days=n)).isoformat()


# key, request_id, action, first, last, manager, cost_center, worker_type, effective (days from today), role, prior_role, approved
REQUESTS = [
    ("H01", "HR-QA-001", "joiner", "anna", "LEE", "M-100", "CC-410", "employee", 3, "analyst", "", "yes"),
    ("H02", "HR-QA-002", "joiner", "Ben", "o'neil", "M-100", "CC-410", "contractor", 5, "developer", "", "yes"),
    ("H03", "HR-QA-003", "mover", "Chen", "Wu", "M-200", "CC-520", "employee", 0, "manager", "analyst", "yes"),
    ("H04", "HR-QA-002", "joiner", "Ben", "O'Neil", "M-100", "CC-410", "contractor", 5, "developer", "", "yes"),
    ("H05", "HR-QA-005", "leaver", "Dina", "Rao", "M-200", "CC-520", "employee", -1, "", "analyst", "yes"),
    ("H06", "HR-QA-006", "joiner", "Eli", "Stone", "M-300", "CC-999", "employee", 7, "analyst", "", "yes"),
    ("H07", "HR-QA-007", "joiner", "Fay", "Ito", "M-300", "CC-630", "intern", 40, "analyst", "", "yes"),
    ("H08", "HR-QA-008", "mover", "Gus", "Kim", "M-100", "CC-410", "employee", 1, "developer", "analyst", "no"),
    ("H09", "HR-QA-009", "joiner", "Hana", "Sato", "M-200", "CC-520", "employee", 2, "developer", "", "yes"),
    ("H10", "HR-QA-010", "leaver", "Ivo", "Berg", "M-300", "CC-630", "contractor", 0, "", "developer", "yes"),
]
MANAGERS = [("M-100", "Mia Lane", "mia.lane@ooa-qa.test"), ("M-200", "Noor Shah", "noor.shah@ooa-qa.test"), ("M-300", "Omar Diaz", "omar.diaz@ooa-qa.test")]
COST_CENTERS = [("CC-410", "Sales"), ("CC-520", "Engineering"), ("CC-630", "Operations")]
WORKER_TYPES = [("employee",), ("contractor",), ("intern",)]
# role -> groups (least privilege), laptop profile, software
ROLES = [
    ("analyst", "QA-Analysts;QA-AllStaff", "standard-laptop", "Office;BI"),
    ("developer", "QA-Developers;QA-AllStaff", "dev-laptop", "Office;IDE;Git"),
    ("manager", "QA-Managers;QA-AllStaff;QA-Approvers", "standard-laptop", "Office;BI;HR-Portal"),
]
GROUPS = {"QA-AllStaff": "g-allstaff", "QA-Analysts": "g-analysts", "QA-Developers": "g-developers", "QA-Managers": "g-managers", "QA-Approvers": "g-approvers"}
# existing Workday workers (movers, leavers and one rehire: Hana Sato left in 2025)
WORKERS = [("Chen Wu", "W-3001", "Active", "analyst"), ("Dina Rao", "W-3002", "Active", "analyst"), ("Gus Kim", "W-3003", "Active", "analyst"),
           ("Ivo Berg", "W-3004", "Active", "developer"), ("Hana Sato", "W-2900", "Terminated", "developer")]


def write(w):
    w("RW02/requests.csv", [r[:8] + (day(r[8]),) + r[9:] for r in REQUESTS],
      ["key", "request_id", "action", "first", "last", "manager", "cost_center", "worker_type", "effective", "role", "prior_role", "approved"])
    w("RW02/managers.csv", MANAGERS, ["manager", "name", "email"])
    w("RW02/cost_centers.csv", COST_CENTERS, ["cost_center", "name"])
    w("RW02/worker_types.csv", WORKER_TYPES, ["worker_type"])
    w("RW02/roles.csv", ROLES, ["role", "groups", "laptop", "software"])
    w("RW02/groups.csv", [(k, v) for k, v in GROUPS.items()], ["name", "id"])
    import qa_desk      # the HR team's small Java desk app (RW02-B6 drives it with the Java applications commands)
    jar = qa_desk.build()
    if jar:
        w("RW02/qa-desk.jar", jar)


def seed():
    workers = [{"id": "%032x" % (0xC2000 + n), "descriptor": w[0], "employeeId": w[1], "status": w[2], "businessTitle": w[3],
                "primaryWorkEmail": w[0].lower().replace(" ", ".") + ".qa@ooa-qa.test"} for n, w in enumerate(WORKERS)]
    users = [{"id": "u-%s" % w[1].lower(), "displayName": w[0], "userPrincipalName": w[0].lower().replace(" ", ".") + ".qa@ooa-qa.test",
              "employeeId": w[1], "accountEnabled": w[2] == "Active", "jobTitle": w[3]} for w in WORKERS]
    member = {g: [] for g in GROUPS.values()}
    for w, u in zip(WORKERS, users):
        if w[2] == "Active":
            for g in dict((r[0], r[1]) for r in ROLES)[w[3]].split(";"):
                member[GROUPS[g]].append(u["id"])
    return {
        "workday": {TENANT: {"workers": workers, "staged": []}},
        "graph_directory": {"users": users, "groups": [{"id": v, "displayName": k} for k, v in GROUPS.items()], "members": member},
        "teams": {"t-onboarding": {"displayName": "OOA-QA Onboarding", "channels": {"c-general": {"displayName": "General", "messages": []}}}},
        "servicenow": {"sc_task": []},
    }
