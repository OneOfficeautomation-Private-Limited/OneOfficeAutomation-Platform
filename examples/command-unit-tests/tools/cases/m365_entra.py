"""Microsoft 365 Entra ID users and groups against the Graph stand-in (users Una, Ann, Bob; group UT Finance with Ann and
Bob, inside group UT Everyone with Una)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, g, ANN, ME  # noqa: E402

M = "ooa.microsoft365/entra_"
PW = {"$credential": "unit_tests/ldap/user_password"}


def new_user(f, name, var="s"):
    f.act(M + "create_user", desc="Create the test user " + name, out={"user_id": var},
          **g(display_name=name, user_principal_name=name.lower().replace(" ", ".") + "@ut-m365.test", password=PW, department="QA"))


def add(ua):
    start(ua, "/msgraph/reset", "Every case below talks to the local Graph stand-in; its users and groups start as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("e", "Dictionary"), ("l", "List"), ("b", "Boolean")):
        ua.var(v, t)

    with ua.case(M + "find_users", "Finance, enabled only", "Find users filters by department and leaves out disabled accounts.") as f:
        f.act(M + "find_users", desc="Enabled Finance users", out={"table": "t", "count": "n"}, **g(department="Finance", enabled_only=True))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][2]", ANN, "sign-in name")
    with ua.case(M + "get_user", "Ann with her manager", "Get user reads one user with the manager and whether the account is enabled.") as f:
        f.act(M + "get_user", desc="Read Ann", out={"user": "e", "enabled": "b"}, **g(user=ANN))
        ua.check_eq("b", True)
        check(ua, "$e.manager", ME, "manager")
    with ua.case(M + "create_user", "with a first password", "Create user makes a user with a vault password and gives the new id.") as f:
        new_user(f, "UT Newcomer")
        ua.check("notEmpty", "$s$", what="user id")
        f.act(M + "get_user", desc="Read the new user", out={"user": "e"}, **g(user="ut.newcomer@ut-m365.test"))
        check(ua, "$e.department", "QA", "department")
    with ua.case(M + "update_user", "disable the account", "Update user disables an account and changes its job title.") as f:
        new_user(f, "UT Leaver")
        f.act(M + "update_user", desc="Disable it", **g(user="$s$", account="disable", job_title="Former"))
        f.act(M + "get_user", desc="Read it", out={"enabled": "b", "user": "e"}, **g(user="$s$"))
        ua.check_eq("b", False)
        check(ua, "$e.job_title", "Former", "job title")
    with ua.case(M + "delete_user", "remove the user", "Delete user removes the user; reading it afterwards fails.") as f:
        new_user(f, "UT Gone")
        f.act(M + "delete_user", desc="Delete it", **g(user="$s$"))
        ua.expect_error("*", lambda ff: ff.act(M + "get_user", desc="Read the deleted user", **g(user="$s$")))
    with ua.case(M + "user_groups", "direct and nested", "Groups of a user lists Ann's direct group, and with nested also the group it sits in.") as f:
        f.act(M + "user_groups", desc="Ann's direct groups", out={"names": "l"}, **g(user=ANN))
        ua.check_eq("l", ["UT Finance"])
        f.act(M + "user_groups", desc="Ann's groups, nested", out={"count": "n"}, **g(user=ANN, nested=True))
        ua.check_eq("n", 2)
    with ua.case(M + "group_members", "UT Everyone, nested", "Group members lists a group's members, with nested also the members of groups inside it.") as f:
        f.act(M + "group_members", desc="Members of UT Everyone", out={"count": "n"}, **g(group="UT Everyone"))
        ua.check_eq("n", 2)
        f.act(M + "group_members", desc="Nested members", out={"count": "n"}, **g(group="UT Everyone", nested=True))
        ua.check_eq("n", 4)
    with ua.case(M + "add_group_member", "add, then add again", "Add to group adds a member; a second time it says it was a member already.") as f:
        new_user(f, "UT Joiner")
        f.act(M + "add_group_member", desc="Add to UT Finance", out={"added": "b"}, **g(group="UT Finance", member="$s$"))
        ua.check_eq("b", True)
        f.act(M + "add_group_member", desc="Add again", out={"added": "b"}, **g(group="UT Finance", member="$s$"))
        ua.check_eq("b", False)
    with ua.case(M + "remove_group_member", "remove, then remove again", "Remove from group removes a member; a second time it says it was not a member.") as f:
        new_user(f, "UT Mover")
        f.act(M + "add_group_member", desc="Add to UT Finance", **g(group="UT Finance", member="$s$"))
        f.act(M + "remove_group_member", desc="Remove", out={"removed": "b"}, **g(group="UT Finance", member="$s$"))
        ua.check_eq("b", True)
        f.act(M + "remove_group_member", desc="Remove again", out={"removed": "b"}, **g(group="UT Finance", member="$s$"))
        ua.check_eq("b", False)
    with ua.case(M + "find_groups", "security groups starting UT", "Find groups lists groups by the start of the name, security groups only.") as f:
        f.act(M + "find_groups", desc="Security groups UT*", out={"table": "t", "count": "n"}, **g(starts_with="UT", security_only=True))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][1]", "UT Finance", "group name")
    with ua.case(M + "sign_out_everywhere", "end Bob's sessions", "Sign out everywhere ends a user's sign-in sessions; the step answers without an error.") as f:
        f.act(M + "sign_out_everywhere", desc="Sign Bob out", **g(user="bob.ray@ut-m365.test"))
        f.set("b", True, desc="The sign-out went through")
        ua.check_eq("b", True)
