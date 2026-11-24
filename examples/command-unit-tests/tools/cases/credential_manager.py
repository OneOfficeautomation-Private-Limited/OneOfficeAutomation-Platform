"""Windows Credential Manager cases on the made-up target ooa-unit-test (deleted at the end). The secret is the made-up
vault value unit_tests/credman/password."""
from helpers_platform import renumber, credential_var

C = "ooa.credential_manager/"
T = "ooa-unit-test"


def add(ua):
    credential_var(ua, "cm_password", "unit_tests/credman/password")
    for v, t in (("cm_user", "String"), ("cm_comment", "String"), ("cm_targets", "List"), ("cm_table", "Table"),
                 ("cm_deleted", "Boolean"), ("cm_secret", "Credential"), ("cm_written", "DateTime")):
        ua.var(v, t)
    f = ua.f
    with ua.case(C + "write_credential", "store a generic credential",
                 "Write credential creates (or replaces) a generic credential for the agent's Windows account. "
                 "Reading it back gives the user name and the comment."):
        f.act(C + "delete_credential", desc="Start clean", target=T, missing_ok=True)
        f.act(C + "write_credential", target=T, username="ut_robot", password="$cm_password$",
              comment="OOA unit test, safe to delete", persist="local_machine")
        f.act(C + "read_credential", target=T, out={"username": "cm_user", "comment": "cm_comment"})
        ua.check_eq("cm_user", "ut_robot")
        ua.check_eq("cm_comment", "OOA unit test, safe to delete")
    with ua.case(C + "read_credential", "user name, secret and when it was written",
                 "Read credential gives the user name, the secret as a masked Credential and when it was last "
                 "written; the secret is used without being shown (checked not empty)."):
        f.act(C + "read_credential", target=T, out={"username": "cm_user", "password": "cm_secret",
                                                     "last_written": "cm_written"})
        ua.check_eq("cm_user", "ut_robot")
        ua.check("notEmpty", "$cm_secret$", what="the secret")
        ua.check("notEmpty", "$cm_written$", what="last written")
    with ua.case(C + "list_targets", "find the target with a wildcard",
                 "List targets gives the target names of generic credentials matching a * pattern, without secrets: "
                 "exactly one matches ooa-unit-test*."):
        f.act(C + "list_targets", filter=T + "*", out={"targets": "cm_targets", "credentials": "cm_table"})
        ua.check_eq("cm_targets", [T])
        ua.check("contains", "${cm_table}", "ut_robot", what="the user name in the table")
    ua.condition_case(C + "credential_exists", "the stored target",
                      "Credential exists is true for the target just written.", {"target": T}, True)
    with ua.case(C + "delete_credential", "remove it; afterwards it does not exist",
                 "Delete credential removes the target (deleted true); then Credential exists is false and deleting "
                 "again with missing_ok on gives deleted false."):
        ua.var("cm_exists", "Boolean", True)
        f.act(C + "delete_credential", target=T, out={"deleted": "cm_deleted"})
        ua.check_eq("cm_deleted", True)
        f.set("cm_exists", False)
        with f.if_({"condition": C + "credential_exists", "args": {"target": T}}):
            f.set("cm_exists", True)
        ua.check_eq("cm_exists", False)
        f.act(C + "delete_credential", target=T, missing_ok=True, out={"deleted": "cm_deleted"})
        ua.check_eq("cm_deleted", False)
    renumber(ua)
